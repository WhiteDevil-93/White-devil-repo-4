package com.whitedevil.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

/*
 * Client side of wd-hello.exe (desktop/hello-helper), the tiny .NET exe that owns
 * Windows Hello. The JVM cannot reach Hello, so everything native goes through it:
 *
 *   wd-hello.exe status [keyName]            -> {"ok":true,"hello_available":..,"key_exists":..,..}
 *   wd-hello.exe create [keyName]            -> {"ok":true,"public_key_pem":"..","algorithm":"RS256"}
 *   wd-hello.exe sign   [keyName] <nonce>    -> {"ok":true,"signature_b64":"..","algorithm":"RS256"}
 *
 * One line of JSON on stdout. Failures exit non-zero AND print {"ok":false,"error":".."}.
 * `create` and `sign` raise a PIN/fingerprint prompt; `status` does not.
 *
 * NOTE the argument order for sign: the key name comes BEFORE the nonce (Program.cs
 * reads keyName = args[1], nonce = args[2]). `wd-hello.exe sign <nonce>` would make the
 * helper treat the nonce as the key name and fail with "sign needs a nonce".
 *
 * Nothing in this file ever throws for a bad helper: every failure is a typed value.
 */

/** Where the helper exe lives, and the name of the Hello key it manages. */
object HelloHelperLocation {
    const val EXE_NAME = "wd-hello.exe"

    /** The Windows Hello key the app owns. Must match Program.cs DefaultKeyName. */
    const val DEFAULT_KEY_NAME = "WhiteDevilDeviceKey"

    /**
     * INSTALLED PATH: the packaging contract.
     *
     * The installer must place `wd-hello.exe` in the app's resources directory,
     * i.e. Compose Desktop's `compose.application.resources.dir`, which in an MSI
     * install is
     *
     *     <install dir>\app\resources\wd-hello.exe        (%ProgramFiles%\WhiteDevil\app\resources\wd-hello.exe)
     *
     * With the Compose Gradle plugin: put the exe at
     * `desktop/appResources/windows-x64/wd-hello.exe` and set
     * `nativeDistributions { appResourcesRootDir.set(layout.projectDirectory.dir("appResources")) }`.
     *
     * [locateHelper] also accepts, in this order, `<appDir>\wd-hello.exe`,
     * `<appDir>\hello\wd-hello.exe` and `<appDir>\resources\wd-hello.exe`, so a
     * packager that lands the exe beside the jars or in a `hello` folder still works.
     */
    const val INSTALLED_RELATIVE_PATH = "app/resources/wd-hello.exe"

    /** Subfolders of the app dir that are searched, in order. "" is the dir itself. */
    val APP_DIR_SUBFOLDERS: List<String> = listOf("", "hello", "resources")

    /** `dotnet build/publish -c Release` output, relative to the desktop/ module or the repo root. */
    private const val DEV_BUILD_DIR = "hello-helper/bin/Release/net8.0-windows10.0.17763.0/win-x64"

    /** Repo-root-relative first (cwd = repo), then desktop-relative (cwd = desktop/, as `gradlew run` uses). */
    val DEV_PREFIXES: List<String> = listOf("desktop/$DEV_BUILD_DIR", DEV_BUILD_DIR)

    /** `dotnet build` puts the exe directly in win-x64; `dotnet publish` puts it in publish/. */
    val DEV_SUBFOLDERS: List<String> = listOf("", "publish")

    /**
     * The directory the running app lives in: Compose's resources dir when set
     * (packaged apps, and `run` when appResourcesRootDir is configured), otherwise
     * the directory holding the running jar (or the classes dir under Gradle).
     */
    fun defaultAppDir(): File? {
        System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        return runCatching {
            val location = HelloHelperLocation::class.java.protectionDomain?.codeSource?.location?.toURI()
                ?: return@runCatching null
            val file = File(location)
            if (file.isFile) file.parentFile else file
        }.getOrNull()
    }

    /**
     * The repo/dev working directory, or null in a packaged app.
     *
     * `jpackage.app-path` is set only by the jpackage launcher, so a packaged app
     * never goes hunting through the current directory for an exe to run: a
     * planted `hello-helper\bin\...\wd-hello.exe` beside wherever the user happened
     * to launch from must not be picked up in production.
     */
    fun defaultDevRoot(): File? {
        if (System.getProperty("jpackage.app-path") != null) return null
        return System.getProperty("user.dir")?.takeIf { it.isNotBlank() }?.let { File(it) }
    }
}

/**
 * Every place [locateHelper] will look, most preferred first. Exposed so the
 * ordering is testable and so a "helper not found" message can list where it looked.
 */
fun helperCandidates(appDir: File?, devFallbackRoot: File?): List<File> = buildList {
    if (appDir != null) {
        for (sub in HelloHelperLocation.APP_DIR_SUBFOLDERS) {
            val dir = if (sub.isEmpty()) appDir else File(appDir, sub)
            add(File(dir, HelloHelperLocation.EXE_NAME))
        }
    }
    if (devFallbackRoot != null) {
        for (prefix in HelloHelperLocation.DEV_PREFIXES) {
            for (sub in HelloHelperLocation.DEV_SUBFOLDERS) {
                val base = File(devFallbackRoot, prefix)
                val dir = if (sub.isEmpty()) base else File(base, sub)
                add(File(dir, HelloHelperLocation.EXE_NAME))
            }
        }
    }
}

/**
 * The first candidate for which [exists] is true: the installed location beats the
 * dev build output, and the null result means "no helper here" rather than an error.
 * Pure: the filesystem check is injected.
 */
fun locateHelper(
    appDir: File?,
    devFallbackRoot: File?,
    exists: (File) -> Boolean = { it.isFile },
): File? = helperCandidates(appDir, devFallbackRoot).firstOrNull(exists)

// ---------------------------------------------------------------------------
// Typed results
// ---------------------------------------------------------------------------

enum class HelperFailureKind {
    /** No helper exe, or not on Windows: nothing was run. */
    UNAVAILABLE,

    /** The OS refused to start the process. */
    START_FAILED,

    /** It did not finish in time and was destroyed. */
    TIMED_OUT,

    /** It ran and said `ok:false`: a refusal, e.g. the user cancelled the Hello prompt. */
    REPORTED_ERROR,

    /** It exited non-zero and said nothing usable, or said ok:true yet exited non-zero. */
    EXIT_CODE,

    /** stdout was empty, garbled, oversized or missing the fields the command promises. */
    BAD_OUTPUT,
}

sealed interface HelperOutcome<out T> {
    data class Success<T>(val value: T) : HelperOutcome<T>
    data class Failure(val kind: HelperFailureKind, val message: String) : HelperOutcome<Nothing>
}

data class HelloStatus(
    val helloAvailable: Boolean,
    val keyExists: Boolean,
    val keyName: String?,
    val detail: String?,
)

/** The public half of the new Hello key, PEM SubjectPublicKeyInfo. Not secret, but not log fodder either. */
data class CreatedKey(val publicKeyPem: String) {
    override fun toString() = "CreatedKey(<pem>)"
}

/** A signature over the nonce. Redacted from toString so it cannot reach a log by accident. */
data class SignedNonce(val signatureB64: String) {
    override fun toString() = "SignedNonce(<redacted>)"
}

/** What the rest of the app depends on, so flows can be tested with a fake. */
interface HelloHelper {
    /** Null when the helper can be run, else a user-facing reason. Spawns nothing. */
    fun unavailableReason(): String?

    /** Never prompts. */
    suspend fun status(): HelperOutcome<HelloStatus>

    /** PROMPTS the user (PIN/fingerprint) and REPLACES any existing Hello key. Button presses only. */
    suspend fun createKey(): HelperOutcome<CreatedKey>

    /** PROMPTS the user (PIN/fingerprint). Button presses only. */
    suspend fun sign(nonce: String): HelperOutcome<SignedNonce>
}

// ---------------------------------------------------------------------------
// Parsing: kotlinx-serialization only. No hand-rolled JSON handling anywhere.
// ---------------------------------------------------------------------------

@Serializable
internal data class HelperWire(
    val ok: Boolean? = null,
    val error: String? = null,
    @SerialName("hello_available") val helloAvailable: Boolean? = null,
    @SerialName("key_exists") val keyExists: Boolean? = null,
    @SerialName("key_name") val keyName: String? = null,
    val detail: String? = null,
    val algorithm: String? = null,
    @SerialName("public_key_pem") val publicKeyPem: String? = null,
    @SerialName("signature_b64") val signatureB64: String? = null,
)

/** Interprets what the helper printed and how it exited. Pure: no I/O. */
object HelloHelperOutput {
    /** Extra fields are the helper's business; a newer helper must not break an older app. */
    private val json = Json { ignoreUnknownKeys = true }

    private const val MAX_MESSAGE_CHARS = 300

    /**
     * The one JSON object on stdout, or null if there is none we can read.
     *
     * The contract is exactly one line. If a runtime prints a banner or warning
     * first, the object is the last non-blank line; anything else (pretty-printed
     * JSON, prose) is refused rather than guessed at.
     */
    internal fun parseWire(stdout: String): HelperWire? {
        val line = stdout.removePrefix("﻿")
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?: return null
        return try {
            json.decodeFromString(HelperWire.serializer(), line)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** A message safe to show: control characters flattened, length capped. */
    internal fun clean(text: String): String {
        val flat = text.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
        return if (flat.length <= MAX_MESSAGE_CHARS) flat else flat.take(MAX_MESSAGE_CHARS - 1) + "…"
    }

    private fun <T> interpret(
        exitCode: Int,
        stdout: String,
        what: String,
        extract: (HelperWire) -> T?,
    ): HelperOutcome<T> {
        val wire = parseWire(stdout)
        if (wire == null) {
            return if (exitCode != 0) {
                HelperOutcome.Failure(
                    HelperFailureKind.EXIT_CODE,
                    "The Windows Hello helper exited with code $exitCode and printed nothing readable.",
                )
            } else {
                HelperOutcome.Failure(
                    HelperFailureKind.BAD_OUTPUT,
                    "The Windows Hello helper printed something this app cannot read.",
                )
            }
        }
        when (wire.ok) {
            null -> return HelperOutcome.Failure(
                HelperFailureKind.BAD_OUTPUT,
                "The Windows Hello helper's reply did not say whether it worked.",
            )
            false -> return HelperOutcome.Failure(
                HelperFailureKind.REPORTED_ERROR,
                wire.error?.let(::clean)?.takeIf { it.isNotEmpty() }
                    ?: "The Windows Hello helper reported a failure without saying why.",
            )
            true -> Unit
        }
        if (exitCode != 0) {
            // A crash must not be mistaken for success just because something JSON-shaped got out first.
            return HelperOutcome.Failure(
                HelperFailureKind.EXIT_CODE,
                "The Windows Hello helper exited with code $exitCode, so its reply was ignored.",
            )
        }
        val value = extract(wire)
            ?: return HelperOutcome.Failure(
                HelperFailureKind.BAD_OUTPUT,
                "The Windows Hello helper's reply did not include the $what.",
            )
        return HelperOutcome.Success(value)
    }

    fun status(exitCode: Int, stdout: String): HelperOutcome<HelloStatus> =
        interpret(exitCode, stdout, "Windows Hello status") { w ->
            val available = w.helloAvailable ?: return@interpret null
            HelloStatus(
                helloAvailable = available,
                keyExists = w.keyExists ?: false,
                keyName = w.keyName?.let(::clean),
                detail = w.detail?.let(::clean)?.takeIf { it.isNotEmpty() },
            )
        }

    fun createdKey(exitCode: Int, stdout: String): HelperOutcome<CreatedKey> =
        interpret(exitCode, stdout, "public key") { w ->
            w.publicKeyPem?.takeIf { it.isNotBlank() }?.let(::CreatedKey)
        }

    fun signature(exitCode: Int, stdout: String): HelperOutcome<SignedNonce> =
        interpret(exitCode, stdout, "signature") { w ->
            val sig = w.signatureB64?.trim()?.takeIf { it.isNotEmpty() } ?: return@interpret null
            // The hub rejects invalid base64, and it does so AFTER consuming the challenge and
            // spending rate-limit budget. Do not send what we can see is garbage.
            val decodable = runCatching { Base64.getDecoder().decode(sig) }.isSuccess
            if (decodable) SignedNonce(sig) else null
        }
}

// ---------------------------------------------------------------------------
// Running the process
// ---------------------------------------------------------------------------

sealed interface ProcessOutcome {
    /** [truncated] means stdout exceeded the cap; the rest was drained and dropped. */
    data class Finished(val exitCode: Int, val stdout: String, val truncated: Boolean) : ProcessOutcome
    data object TimedOut : ProcessOutcome
    data class StartFailed(val message: String) : ProcessOutcome
}

fun interface HelloProcessRunner {
    suspend fun run(command: List<String>, timeoutMs: Long): ProcessOutcome
}

/**
 * Runs a command with a hard timeout and captures a bounded amount of stdout.
 *
 * - On timeout, or if the calling coroutine is cancelled (the user leaves the
 *   screen mid-prompt), the process AND its descendants are destroyed. Leaving
 *   a Hello prompt orphaned on screen is worse than a cancelled sign-in.
 * - stdout is read on its own thread so a chatty child can never block on a full
 *   pipe (Windows pipe buffers are small), and is capped so a runaway child
 *   cannot exhaust memory.
 * - stderr is discarded; the helper's contract is stdout-only.
 */
object JvmHelloProcessRunner : HelloProcessRunner {
    const val MAX_STDOUT_BYTES = 64 * 1024
    private const val READER_GRACE_MS = 5_000L

    override suspend fun run(command: List<String>, timeoutMs: Long): ProcessOutcome =
        runInterruptible(Dispatchers.IO) { execute(command, timeoutMs) }

    internal fun execute(command: List<String>, timeoutMs: Long): ProcessOutcome {
        val process = try {
            ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (e: IOException) {
            return ProcessOutcome.StartFailed(e.message ?: e.javaClass.simpleName)
        } catch (e: SecurityException) {
            return ProcessOutcome.StartFailed(e.message ?: "Not permitted to start the helper.")
        } catch (e: UnsupportedOperationException) {
            return ProcessOutcome.StartFailed(e.message ?: "Cannot start processes on this platform.")
        }

        try {
            // The helper never reads stdin; closing it means nothing can wait on it.
            runCatching { process.outputStream.close() }
            val reader = BoundedReader(process.inputStream, MAX_STDOUT_BYTES).also { it.start() }

            // InterruptedException (coroutine cancellation) propagates to the finally below.
            val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!exited) {
                destroyTree(process)
                return ProcessOutcome.TimedOut
            }
            reader.join(READER_GRACE_MS)
            return ProcessOutcome.Finished(
                exitCode = process.exitValue(),
                stdout = String(reader.bytes(), Charsets.UTF_8),
                truncated = reader.truncated,
            )
        } finally {
            if (process.isAlive) destroyTree(process)
        }
    }

    private fun destroyTree(process: Process) {
        // Descendants first: a child that inherited our stdout pipe would otherwise keep it open.
        runCatching { process.descendants().forEach { it.destroyForcibly() } }
        process.destroyForcibly()
        runCatching { process.waitFor(2, TimeUnit.SECONDS) }
    }

    private class BoundedReader(private val input: InputStream, private val max: Int) : Thread("wd-hello-stdout") {
        private val buffer = ByteArrayOutputStream()

        @Volatile
        var truncated = false
            private set

        init {
            isDaemon = true
        }

        override fun run() {
            val chunk = ByteArray(4096)
            try {
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    synchronized(buffer) {
                        val room = max - buffer.size()
                        if (room > 0) buffer.write(chunk, 0, minOf(n, room))
                        if (n > room) truncated = true
                    }
                    // Keep draining past the cap without storing, so the child never blocks on a full pipe.
                }
            } catch (_: IOException) {
                // Process destroyed under us: what we have is what we have.
            }
        }

        fun bytes(): ByteArray = synchronized(buffer) { buffer.toByteArray() }
    }
}

// ---------------------------------------------------------------------------
// The real helper
// ---------------------------------------------------------------------------

/**
 * [HelloHelper] backed by wd-hello.exe.
 *
 * Timeouts are generous for `create` and `sign` because a human is answering a
 * PIN/fingerprint prompt, but `sign` stays under the hub's challenge lifetime
 * (CHALLENGE_TTL_S = 120 in hub/auth.py): a signature that arrives after the
 * challenge expired is wasted, and it would still burn rate-limit budget.
 */
class ProcessHelloHelper(
    private val locate: () -> File?,
    private val runner: HelloProcessRunner = JvmHelloProcessRunner,
    private val keyName: String = HelloHelperLocation.DEFAULT_KEY_NAME,
    private val isWindows: () -> Boolean = { System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true) },
    private val statusTimeoutMs: Long = STATUS_TIMEOUT_MS,
    private val createTimeoutMs: Long = CREATE_TIMEOUT_MS,
    private val signTimeoutMs: Long = SIGN_TIMEOUT_MS,
) : HelloHelper {

    /** Where the helper was found, or null. Cheap: only checks files. */
    fun locatedExecutable(): File? = locate()

    override fun unavailableReason(): String? = when {
        !isWindows() -> "Windows Hello is only available on Windows."
        locate() == null -> "The Windows Hello helper (${HelloHelperLocation.EXE_NAME}) was not found next to the app."
        else -> null
    }

    override suspend fun status(): HelperOutcome<HelloStatus> =
        run(listOf("status", keyName), statusTimeoutMs, HelloHelperOutput::status)

    override suspend fun createKey(): HelperOutcome<CreatedKey> =
        run(listOf("create", keyName), createTimeoutMs, HelloHelperOutput::createdKey)

    override suspend fun sign(nonce: String): HelperOutcome<SignedNonce> =
        // Key name FIRST, then the nonce: see the note at the top of this file.
        run(listOf("sign", keyName, nonce), signTimeoutMs, HelloHelperOutput::signature)

    private suspend fun <T> run(
        args: List<String>,
        timeoutMs: Long,
        parse: (Int, String) -> HelperOutcome<T>,
    ): HelperOutcome<T> {
        unavailableReason()?.let { return HelperOutcome.Failure(HelperFailureKind.UNAVAILABLE, it) }
        val exe = locate()
            ?: return HelperOutcome.Failure(HelperFailureKind.UNAVAILABLE, "The Windows Hello helper was not found.")
        return when (val outcome = runner.run(listOf(exe.absolutePath) + args, timeoutMs)) {
            is ProcessOutcome.StartFailed -> HelperOutcome.Failure(
                HelperFailureKind.START_FAILED,
                "Could not start the Windows Hello helper: ${HelloHelperOutput.clean(outcome.message)}",
            )
            ProcessOutcome.TimedOut -> HelperOutcome.Failure(
                HelperFailureKind.TIMED_OUT,
                "Windows Hello did not answer within ${timeoutMs / 1000} seconds, so the helper was stopped.",
            )
            is ProcessOutcome.Finished ->
                if (outcome.truncated) {
                    HelperOutcome.Failure(HelperFailureKind.BAD_OUTPUT, "The Windows Hello helper printed far more than expected.")
                } else {
                    parse(outcome.exitCode, outcome.stdout)
                }
        }
    }

    companion object {
        const val STATUS_TIMEOUT_MS = 20_000L
        const val CREATE_TIMEOUT_MS = 120_000L

        /** Under the hub's 120 s challenge lifetime, with room for the two network hops. */
        const val SIGN_TIMEOUT_MS = 100_000L

        fun createDefault(): ProcessHelloHelper = ProcessHelloHelper(
            locate = { locateHelper(HelloHelperLocation.defaultAppDir(), HelloHelperLocation.defaultDevRoot()) },
        )
    }
}
