package com.whitedevil.desktop

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Wrapper around `wd-hello.exe` (desktop/hello-helper), which owns everything
 * native about Windows Hello: make a key, sign a nonce with it. The private key is
 * created and held by Windows and is unusable without the user's PIN or
 * fingerprint; this process never sees it.
 *
 * The helper's contract is settled (see hello-helper/Program.cs): one line of JSON
 * on stdout, and a failure exits non-zero AND reports ok:false. It signs RS256 over
 * the raw UTF-8 bytes of the nonce.
 *
 * `create` and `sign` put a Hello prompt in front of the user. Nothing in this file
 * decides *when* to call them — callers must do so only from an explicit user
 * action, never on startup or in a retry loop.
 */
interface HelloBackend {
    /** Does NOT prompt. */
    fun status(): Result<HelloStatus>

    /** Prompts. Returns the public key as PEM. Replaces any existing key of that name. */
    fun createKey(): Result<String>

    /** Prompts. Returns the base64 signature over the UTF-8 bytes of [nonce]. */
    fun sign(nonce: String): Result<String>
}

data class HelloStatus(val available: Boolean, val keyExists: Boolean, val detail: String)

/** The helper's stdout, one JSON object. Unknown fields are ignored so the helper can grow. */
@Serializable
internal data class HelloJson(
    val ok: Boolean = false,
    val error: String? = null,
    @SerialName("hello_available") val helloAvailable: Boolean? = null,
    @SerialName("key_exists") val keyExists: Boolean? = null,
    @SerialName("key_name") val keyName: String? = null,
    val detail: String? = null,
    val algorithm: String? = null,
    @SerialName("public_key_pem") val publicKeyPem: String? = null,
    @SerialName("signature_b64") val signatureB64: String? = null,
) {
    // A signature is a credential-adjacent value; keep it out of any log or
    // exception message that happens to print this object.
    override fun toString() = "HelloJson(ok=$ok)"
}

internal object HelloOutput {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Turns the helper's stdout and exit code into a result.
     *
     * Trust rules, each earning its keep:
     *  - only the last non-blank line is parsed, so a stray banner cannot hide the result;
     *  - `ok:false` is a failure whatever the exit code;
     *  - `ok:true` with a non-zero exit is ALSO a failure — the contract says
     *    failures exit non-zero, so a mismatch means the helper cannot be believed;
     *  - the raw stdout is never put in an error message, because on `sign` it
     *    contains the signature.
     */
    fun parse(stdout: String, exitCode: Int): Result<HelloJson> {
        val line = stdout.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            ?: return Result.failure(HelloException("The Windows Hello helper produced no output (exit code $exitCode)."))

        val parsed = try {
            json.decodeFromString<HelloJson>(line)
        } catch (_: SerializationException) {
            return Result.failure(HelloException("The Windows Hello helper returned output that is not valid JSON (exit code $exitCode)."))
        } catch (_: IllegalArgumentException) {
            return Result.failure(HelloException("The Windows Hello helper returned output that is not valid JSON (exit code $exitCode)."))
        }

        if (!parsed.ok) {
            return Result.failure(HelloException(parsed.error?.takeIf { it.isNotBlank() } ?: "The Windows Hello helper reported a failure (exit code $exitCode)."))
        }
        if (exitCode != 0) {
            return Result.failure(HelloException("The Windows Hello helper exited with code $exitCode but reported success; not trusting it."))
        }
        return Result.success(parsed)
    }
}

class HelloException(message: String) : Exception(message)

/**
 * Finds wd-hello.exe. Pure over its inputs so the search order is testable on a
 * machine that has neither the exe nor Windows.
 *
 * Order — explicit override, then next to the installed app, then the dev build:
 *  1. `WD_HELLO_PATH`
 *  2. Compose's `compose.application.resources.dir` (the packaged app's resources)
 *  3. beside the jpackage launcher: `<install>/`, `<install>/app/`, `<install>/app/resources/`
 *  4. `desktop/hello-helper/bin/Release/<tfm>/win-x64/` (and its `publish/`), searched
 *     upward from the working directory so `gradlew run` works from `desktop/` or the repo root
 */
class HelperLocator(
    private val env: Map<String, String> = System.getenv(),
    private val property: (String) -> String? = { System.getProperty(it) },
    private val isFile: (File) -> Boolean = { it.isFile },
    private val workingDir: File = File(System.getProperty("user.dir") ?: "."),
) {
    fun candidates(): List<File> {
        val out = LinkedHashSet<File>()

        env["WD_HELLO_PATH"]?.takeIf { it.isNotBlank() }?.let { out += File(it) }

        property("compose.application.resources.dir")?.takeIf { it.isNotBlank() }?.let {
            out += File(it, EXE_NAME)
        }

        property("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { launcher ->
            File(launcher).absoluteFile.parentFile?.let { install ->
                out += File(install, EXE_NAME)
                out += File(File(install, "app"), EXE_NAME)
                out += File(File(File(install, "app"), "resources"), EXE_NAME)
            }
        }

        var dir: File? = workingDir.absoluteFile
        repeat(MAX_ANCESTORS) {
            val d = dir ?: return@repeat
            for (helperRoot in listOf(File(d, "desktop/hello-helper"), File(d, "hello-helper"))) {
                val release = File(helperRoot, "bin/Release/$DEV_TFM/win-x64")
                out += File(release, EXE_NAME)
                out += File(release, "publish/$EXE_NAME")
            }
            dir = d.parentFile
        }
        return out.toList()
    }

    fun find(): File? = candidates().firstOrNull(isFile)

    companion object {
        const val EXE_NAME = "wd-hello.exe"
        const val DEV_TFM = "net8.0-windows10.0.17763.0"
        private const val MAX_ANCESTORS = 4
    }
}

/** Result of running a process. stderr is kept only to diagnose a crash. */
data class ProcessResult(val stdout: String, val stderr: String, val exitCode: Int)

fun interface ProcessRunner {
    /** Throws [HelloException] on timeout or if the process cannot be started. */
    fun run(command: List<String>, timeoutMs: Long): ProcessResult
}

/** Real process execution with a hard timeout — a prompt nobody answers must not hang the app. */
object SystemProcessRunner : ProcessRunner {
    override fun run(command: List<String>, timeoutMs: Long): ProcessResult {
        val process = try {
            ProcessBuilder(command).start()
        } catch (e: java.io.IOException) {
            throw HelloException("Could not start the Windows Hello helper: ${e.message}")
        }
        // Nothing to say to it; closing stdin means it can never block waiting.
        runCatching { process.outputStream.close() }

        // Drained on separate threads: a full stderr pipe would otherwise stall the
        // process while we wait on stdout, and the timeout would then misreport it.
        val out = StringBuilder()
        val err = StringBuilder()
        val outReader = thread(isDaemon = true, name = "wd-hello-out") {
            runCatching { out.append(process.inputStream.readBytes().toString(Charsets.UTF_8)) }
        }
        val errReader = thread(isDaemon = true, name = "wd-hello-err") {
            runCatching { err.append(process.errorStream.readBytes().toString(Charsets.UTF_8)) }
        }

        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw HelloException(
                "The Windows Hello helper did not finish within ${timeoutMs / 1000}s. " +
                    "A Windows Hello prompt may be waiting behind another window.",
            )
        }
        outReader.join(2_000)
        errReader.join(2_000)
        return ProcessResult(out.toString(), err.toString().take(500), process.exitValue())
    }
}

/** [HelloBackend] backed by the real wd-hello.exe. */
class ProcessHelloBackend(
    private val locator: HelperLocator = HelperLocator(),
    private val runner: ProcessRunner = SystemProcessRunner,
    private val keyName: String = DEFAULT_KEY_NAME,
) : HelloBackend {

    override fun status(): Result<HelloStatus> =
        invoke(listOf("status", keyName), STATUS_TIMEOUT_MS).map {
            HelloStatus(
                available = it.helloAvailable == true,
                keyExists = it.keyExists == true,
                detail = it.detail.orEmpty(),
            )
        }

    override fun createKey(): Result<String> =
        invoke(listOf("create", keyName), PROMPT_TIMEOUT_MS).mapCatching {
            it.publicKeyPem?.takeIf { pem -> pem.isNotBlank() }
                ?: throw HelloException("The helper reported success but returned no public key.")
        }

    override fun sign(nonce: String): Result<String> {
        if (nonce.isBlank()) return Result.failure(HelloException("Cannot sign an empty nonce."))
        return invoke(listOf("sign", keyName, nonce), PROMPT_TIMEOUT_MS).mapCatching {
            it.signatureB64?.takeIf { sig -> sig.isNotBlank() }
                ?: throw HelloException("The helper reported success but returned no signature.")
        }
    }

    private fun invoke(args: List<String>, timeoutMs: Long): Result<HelloJson> {
        val exe = locator.find() ?: return Result.failure(HelloException(notFoundMessage()))
        val result = try {
            runner.run(listOf(exe.absolutePath) + args, timeoutMs)
        } catch (e: HelloException) {
            return Result.failure(e)
        }
        return HelloOutput.parse(result.stdout, result.exitCode).onFailure {
            // A crash before any JSON: stderr is the only clue, and it holds no secrets.
            if (result.stdout.isBlank() && result.stderr.isNotBlank()) {
                return Result.failure(HelloException("${it.message} ${result.stderr.trim()}"))
            }
        }
    }

    private fun notFoundMessage(): String =
        "wd-hello.exe was not found. It ships next to the installed app; in development build it with " +
            "`dotnet build -c Release` in desktop/hello-helper, or set WD_HELLO_PATH."

    companion object {
        const val DEFAULT_KEY_NAME = "WhiteDevilDeviceKey"
        private const val STATUS_TIMEOUT_MS = 15_000L
        // Long enough for a person to find the prompt and use a fingerprint or PIN.
        private const val PROMPT_TIMEOUT_MS = 120_000L
    }
}
