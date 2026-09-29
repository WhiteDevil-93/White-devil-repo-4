package com.whitedevil.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure parts of the Windows Hello helper client. Sample lines are shaped exactly like
 * what hello-helper/Program.cs prints: System.Text.Json's default encoder escapes every
 * non-ASCII character and '+' (as +), so a real PEM and real messages arrive escaped.
 *
 * None of this runs wd-hello.exe or touches Windows Hello.
 */
class HelloHelperTest {

    // -- fixtures ----------------------------------------------------------------------

    private val statusReady =
        """{"hello_available":true,"key_exists":true,"key_name":"WhiteDevilDeviceKey","detail":"Ready.","ok":true}"""

    private val createOk =
        """{"key_name":"WhiteDevilDeviceKey","algorithm":"RS256","public_key_pem":"-----BEGIN PUBLIC KEY-----\nMIIBIjANBg+ab\n-----END PUBLIC KEY-----\n","ok":true}"""

    private val signOk =
        """{"key_name":"WhiteDevilDeviceKey","algorithm":"RS256","signature_b64":"AAECAw+/AA==","ok":true}"""

    private fun HelperOutcome<*>.failure(kind: HelperFailureKind): HelperOutcome.Failure {
        val f = assertIs<HelperOutcome.Failure>(this)
        assertEquals(kind, f.kind, f.message)
        return f
    }

    // -- status ------------------------------------------------------------------------

    @Test
    fun `status parses a ready helper`() {
        val s = assertIs<HelperOutcome.Success<HelloStatus>>(HelloHelperOutput.status(0, statusReady)).value
        assertTrue(s.helloAvailable)
        assertTrue(s.keyExists)
        assertEquals("WhiteDevilDeviceKey", s.keyName)
        assertEquals("Ready.", s.detail)
    }

    @Test
    fun `status parses hello not set up`() {
        val line = """{"hello_available":false,"key_exists":false,"key_name":"k","detail":"Windows Hello is not set up on this account.","ok":true}"""
        val s = assertIs<HelperOutcome.Success<HelloStatus>>(HelloHelperOutput.status(0, line)).value
        assertFalse(s.helloAvailable)
        assertFalse(s.keyExists)
    }

    @Test
    fun `status without hello_available is bad output`() {
        HelloHelperOutput.status(0, """{"ok":true,"key_exists":true}""").failure(HelperFailureKind.BAD_OUTPUT)
    }

    // -- create / sign -----------------------------------------------------------------

    @Test
    fun `create returns the decoded PEM`() {
        val key = assertIs<HelperOutcome.Success<CreatedKey>>(HelloHelperOutput.createdKey(0, createOk)).value
        assertEquals("-----BEGIN PUBLIC KEY-----\nMIIBIjANBg+ab\n-----END PUBLIC KEY-----\n", key.publicKeyPem)
    }

    @Test
    fun `sign returns the decoded signature`() {
        val sig = assertIs<HelperOutcome.Success<SignedNonce>>(HelloHelperOutput.signature(0, signOk)).value
        assertEquals("AAECAw+/AA==", sig.signatureB64)
    }

    @Test
    fun `create without a key is bad output`() {
        HelloHelperOutput.createdKey(0, """{"ok":true,"algorithm":"RS256"}""").failure(HelperFailureKind.BAD_OUTPUT)
        HelloHelperOutput.createdKey(0, """{"ok":true,"public_key_pem":"   "}""").failure(HelperFailureKind.BAD_OUTPUT)
    }

    @Test
    fun `a signature that is not base64 is refused before the hub can burn a challenge on it`() {
        HelloHelperOutput.signature(0, """{"ok":true,"signature_b64":"not base64 !!!"}""").failure(HelperFailureKind.BAD_OUTPUT)
        HelloHelperOutput.signature(0, """{"ok":true,"signature_b64":""}""").failure(HelperFailureKind.BAD_OUTPUT)
    }

    @Test
    fun `secrets are redacted from toString`() {
        assertFalse(HelloHelperOutput.signature(0, signOk).toString().contains("AAECAw"))
        assertFalse(HelloHelperOutput.createdKey(0, createOk).toString().contains("MIIBIjANBg"))
    }

    // -- failures the helper reports ---------------------------------------------------

    @Test
    fun `ok false carries the helpers own message`() {
        val line = """{"ok":false,"error":"Signing was not completed: you cancelled the Windows Hello prompt"}"""
        val f = HelloHelperOutput.signature(1, line).failure(HelperFailureKind.REPORTED_ERROR)
        assertEquals("Signing was not completed: you cancelled the Windows Hello prompt", f.message)
    }

    @Test
    fun `ok false is a failure even when the exit code says zero`() {
        HelloHelperOutput.createdKey(0, """{"ok":false,"error":"nope"}""").failure(HelperFailureKind.REPORTED_ERROR)
    }

    @Test
    fun `ok false without an error still says something`() {
        val f = HelloHelperOutput.status(1, """{"ok":false}""").failure(HelperFailureKind.REPORTED_ERROR)
        assertTrue(f.message.isNotBlank())
    }

    @Test
    fun `ok true with a non zero exit is not trusted`() {
        HelloHelperOutput.signature(3, signOk).failure(HelperFailureKind.EXIT_CODE)
    }

    @Test
    fun `non zero exit with no readable output is an exit code failure`() {
        val f = HelloHelperOutput.status(-2147450749, "").failure(HelperFailureKind.EXIT_CODE)
        assertTrue(f.message.contains("-2147450749"))
        HelloHelperOutput.status(1, "Unhandled exception. System.IO...").failure(HelperFailureKind.EXIT_CODE)
    }

    // -- garbage in --------------------------------------------------------------------

    @Test
    fun `empty and whitespace only output is bad output not a crash`() {
        for (junk in listOf("", "   ", "\n\n", "\r\n", "﻿")) {
            HelloHelperOutput.status(0, junk).failure(HelperFailureKind.BAD_OUTPUT)
        }
    }

    @Test
    fun `garbled output is bad output`() {
        for (junk in listOf(
            "hello",
            "{",
            """{"ok":true""",
            """{"ok":true,}""",
            "[]",
            "[true]",
            "null",
            "42",
            "\"ok\"",
            """{"ok":"true"}""",     // wrong type: strings are not booleans
            """{"ok":true,"error":5}""",
            "\u0000\u0001\u0002",
            "{'ok':true}",
        )) {
            HelloHelperOutput.status(0, junk).failure(HelperFailureKind.BAD_OUTPUT)
        }
    }

    @Test
    fun `a reply that does not say ok is bad output`() {
        HelloHelperOutput.status(0, """{"hello_available":true}""").failure(HelperFailureKind.BAD_OUTPUT)
        HelloHelperOutput.status(0, """{"ok":null,"hello_available":true}""").failure(HelperFailureKind.BAD_OUTPUT)
    }

    @Test
    fun `extra and unknown fields are ignored`() {
        val line = """{"ok":true,"hello_available":true,"key_exists":false,"future_field":{"a":[1,2,3]},"another":null,"n":1.5}"""
        val s = assertIs<HelperOutcome.Success<HelloStatus>>(HelloHelperOutput.status(0, line)).value
        assertTrue(s.helloAvailable)
    }

    @Test
    fun `a banner before the json line is tolerated but json is taken from the last line only`() {
        val out = "Welcome to .NET 8\nwarning: something\n$statusReady\n\n"
        assertIs<HelperOutcome.Success<HelloStatus>>(HelloHelperOutput.status(0, out))
        // JSON that is not the LAST line is not guessed at.
        HelloHelperOutput.status(0, "$statusReady\ntrailing junk").failure(HelperFailureKind.BAD_OUTPUT)
    }

    @Test
    fun `pretty printed json is refused rather than guessed at`() {
        HelloHelperOutput.status(0, "{\n  \"ok\": true,\n  \"hello_available\": true\n}").failure(HelperFailureKind.BAD_OUTPUT)
    }

    @Test
    fun `windows line endings and a byte order mark are tolerated`() {
        val out = "﻿$statusReady\r\n"
        assertIs<HelperOutcome.Success<HelloStatus>>(HelloHelperOutput.status(0, out))
    }

    // -- unicode -----------------------------------------------------------------------

    @Test
    fun `escaped unicode in messages decodes`() {
        // What .NET really prints for an em dash: "—".
        val line = """{"ok":false,"error":"the security device is locked — sign in to Windows again"}"""
        val f = HelloHelperOutput.signature(1, line).failure(HelperFailureKind.REPORTED_ERROR)
        assertEquals("the security device is locked — sign in to Windows again", f.message)
    }

    @Test
    fun `raw unicode and astral characters decode`() {
        val line = "{\"ok\":false,\"error\":\"café 日本語 🔒 locked\"}"
        val f = HelloHelperOutput.signature(1, line).failure(HelperFailureKind.REPORTED_ERROR)
        assertEquals("café 日本語 🔒 locked", f.message)
    }

    @Test
    fun `surrogate pair escapes decode`() {
        val line = """{"ok":false,"error":"lock 🔒"}"""
        val f = HelloHelperOutput.signature(1, line).failure(HelperFailureKind.REPORTED_ERROR)
        assertEquals("lock 🔒", f.message)
    }

    @Test
    fun `control characters and huge messages are cleaned before display`() {
        val f = HelloHelperOutput.signature(
            1, """{"ok":false,"error":"line1\nline2\u0007bell ${"x".repeat(2000)}"}""",
        ).failure(HelperFailureKind.REPORTED_ERROR)
        assertFalse(f.message.any { it.isISOControl() })
        assertTrue(f.message.length <= 300)
    }

    // -- locating the exe --------------------------------------------------------------

    private val appDir = File("/opt/WhiteDevil/app")
    private val devRoot = File("/work/repo")
    private val devBuild = "hello-helper/bin/Release/net8.0-windows10.0.17763.0/win-x64"

    private fun existsOnly(vararg present: File): (File) -> Boolean {
        val set = present.toSet()
        return { it in set }
    }

    @Test
    fun `the candidate order is app dir then hello then resources then dev outputs`() {
        val expected = listOf(
            File(appDir, "wd-hello.exe"),
            File(File(appDir, "hello"), "wd-hello.exe"),
            File(File(appDir, "resources"), "wd-hello.exe"),
            File(File(devRoot, "desktop/$devBuild"), "wd-hello.exe"),
            File(File(devRoot, "desktop/$devBuild/publish"), "wd-hello.exe"),
            File(File(devRoot, devBuild), "wd-hello.exe"),
            File(File(devRoot, "$devBuild/publish"), "wd-hello.exe"),
        )
        assertEquals(expected, helperCandidates(appDir, devRoot))
    }

    @Test
    fun `the installed helper beats a dev build`() {
        val installed = File(File(appDir, "resources"), "wd-hello.exe")
        val dev = File(File(devRoot, "desktop/$devBuild"), "wd-hello.exe")
        assertEquals(installed, locateHelper(appDir, devRoot, existsOnly(installed, dev)))
    }

    @Test
    fun `the app dir itself beats its hello and resources subfolders`() {
        val direct = File(appDir, "wd-hello.exe")
        val hello = File(File(appDir, "hello"), "wd-hello.exe")
        val res = File(File(appDir, "resources"), "wd-hello.exe")
        assertEquals(direct, locateHelper(appDir, devRoot, existsOnly(direct, hello, res)))
        assertEquals(hello, locateHelper(appDir, devRoot, existsOnly(hello, res)))
        assertEquals(res, locateHelper(appDir, devRoot, existsOnly(res)))
    }

    @Test
    fun `falls back to the dev build only when nothing is installed`() {
        val dev = File(File(devRoot, "desktop/$devBuild"), "wd-hello.exe")
        assertEquals(dev, locateHelper(appDir, devRoot, existsOnly(dev)))
        // cwd = desktop/ (what gradlew run uses): the desktop-relative build dir.
        val fromDesktopCwd = File(File(devRoot, devBuild), "wd-hello.exe")
        assertEquals(fromDesktopCwd, locateHelper(appDir, devRoot, existsOnly(fromDesktopCwd)))
        // publish output is found too, after the plain build dir.
        val published = File(File(devRoot, "$devBuild/publish"), "wd-hello.exe")
        assertEquals(published, locateHelper(appDir, devRoot, existsOnly(published)))
    }

    @Test
    fun `a build output beats the publish output in the same tree`() {
        val build = File(File(devRoot, devBuild), "wd-hello.exe")
        val published = File(File(devRoot, "$devBuild/publish"), "wd-hello.exe")
        assertEquals(build, locateHelper(null, devRoot, existsOnly(published, build)))
    }

    @Test
    fun `null when the helper is nowhere`() {
        assertNull(locateHelper(appDir, devRoot, existsOnly()))
        assertNull(locateHelper(null, null, { true }))
    }

    @Test
    fun `a null app dir or a null dev root simply drops that source`() {
        val installed = File(appDir, "wd-hello.exe")
        val dev = File(File(devRoot, devBuild), "wd-hello.exe")
        assertEquals(installed, locateHelper(appDir, null, existsOnly(installed, dev)))
        assertEquals(dev, locateHelper(null, devRoot, existsOnly(installed, dev)))
        assertEquals(3, helperCandidates(appDir, null).size)
        assertEquals(4, helperCandidates(null, devRoot).size)
    }

    @Test
    fun `the documented installed path is one the locator actually finds`() {
        // The packaging contract: <install dir>\app\resources\wd-hello.exe.
        val installRoot = File("/opt/WhiteDevil")
        val installed = File(installRoot, HelloHelperLocation.INSTALLED_RELATIVE_PATH)
        assertEquals("app/resources/wd-hello.exe", HelloHelperLocation.INSTALLED_RELATIVE_PATH)
        // compose.application.resources.dir points at <install>/app/resources.
        assertEquals(installed, locateHelper(File(installRoot, "app/resources"), null, existsOnly(installed)))
        // If only the directory holding the jars (<install>/app) is known, resources/ beneath it is searched too.
        assertEquals(installed, locateHelper(File(installRoot, "app"), null, existsOnly(installed)))
    }

    // -- ProcessHelloHelper: what gets run, and how ------------------------------------

    private class FakeRunner(private val outcome: ProcessOutcome) : HelloProcessRunner {
        val commands = mutableListOf<List<String>>()
        val timeouts = mutableListOf<Long>()
        override suspend fun run(command: List<String>, timeoutMs: Long): ProcessOutcome {
            commands += command
            timeouts += timeoutMs
            return outcome
        }
    }

    private val exe = File("/opt/WhiteDevil/app/resources/wd-hello.exe")

    private fun helper(runner: HelloProcessRunner, windows: Boolean = true, located: File? = exe) =
        ProcessHelloHelper(locate = { located }, runner = runner, isWindows = { windows })

    @Test
    fun `sign puts the key name before the nonce`() = runBlocking {
        val runner = FakeRunner(ProcessOutcome.Finished(0, signOk, false))
        val out = helper(runner).sign("nonce-123_ABC")
        assertIs<HelperOutcome.Success<SignedNonce>>(out)
        assertEquals(
            listOf(exe.absolutePath, "sign", "WhiteDevilDeviceKey", "nonce-123_ABC"),
            runner.commands.single(),
        )
    }

    @Test
    fun `status and create run the right subcommands with the key name`() = runBlocking {
        val statusRunner = FakeRunner(ProcessOutcome.Finished(0, statusReady, false))
        helper(statusRunner).status()
        assertEquals(listOf(exe.absolutePath, "status", "WhiteDevilDeviceKey"), statusRunner.commands.single())

        val createRunner = FakeRunner(ProcessOutcome.Finished(0, createOk, false))
        helper(createRunner).createKey()
        assertEquals(listOf(exe.absolutePath, "create", "WhiteDevilDeviceKey"), createRunner.commands.single())
    }

    @Test
    fun `prompting calls get a long timeout and sign stays under the hub challenge lifetime`() = runBlocking {
        assertTrue(ProcessHelloHelper.SIGN_TIMEOUT_MS < 120_000, "hub CHALLENGE_TTL_S is 120")
        assertTrue(ProcessHelloHelper.STATUS_TIMEOUT_MS < ProcessHelloHelper.SIGN_TIMEOUT_MS)
        val runner = FakeRunner(ProcessOutcome.Finished(0, signOk, false))
        helper(runner).sign("n")
        assertEquals(ProcessHelloHelper.SIGN_TIMEOUT_MS, runner.timeouts.single())
    }

    @Test
    fun `nothing is run when the helper is missing or the OS is not windows`() = runBlocking {
        val runner = FakeRunner(ProcessOutcome.Finished(0, signOk, false))

        val missing = helper(runner, located = null)
        assertNotNull(missing.unavailableReason())
        missing.sign("n").failure(HelperFailureKind.UNAVAILABLE)

        val linux = helper(runner, windows = false)
        assertNotNull(linux.unavailableReason())
        linux.createKey().failure(HelperFailureKind.UNAVAILABLE)

        assertTrue(runner.commands.isEmpty(), "must not spawn anything")
        assertNull(helper(runner).unavailableReason())
    }

    @Test
    fun `process level failures become typed failures`() = runBlocking {
        helper(FakeRunner(ProcessOutcome.TimedOut)).sign("n").failure(HelperFailureKind.TIMED_OUT)
        helper(FakeRunner(ProcessOutcome.StartFailed("Access is denied"))).sign("n").failure(HelperFailureKind.START_FAILED)
        helper(FakeRunner(ProcessOutcome.Finished(0, signOk, truncated = true))).sign("n").failure(HelperFailureKind.BAD_OUTPUT)
        helper(FakeRunner(ProcessOutcome.Finished(1, "", false))).sign("n").failure(HelperFailureKind.EXIT_CODE)
        Unit
    }

    // -- the real process runner, against /bin/sh --------------------------------------
    // These prove the timeout/destroy/cancel/cap logic with a stand-in child. They say
    // nothing about wd-hello.exe itself.

    private fun requireSh() = assumeTrue(File("/bin/sh").canExecute(), "needs /bin/sh")

    private fun waitDead(pid: Long, timeoutMs: Long = 5_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) != true) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun readPid(file: File, timeoutMs: Long = 5_000): Long {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            file.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()?.let { return it }
            Thread.sleep(25)
        }
        error("pid file never appeared")
    }

    private inline fun <T> withTempDir(block: (File) -> T): T {
        val dir = Files.createTempDirectory("wdhello")
        try {
            return block(dir.toFile())
        } finally {
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `the runner captures stdout and the exit code`() {
        requireSh()
        val out = runBlocking {
            JvmHelloProcessRunner.run(listOf("/bin/sh", "-c", """printf '%s\n' '{"ok":false,"error":"x"}'; exit 1"""), 10_000)
        }
        val fin = assertIs<ProcessOutcome.Finished>(out)
        assertEquals(1, fin.exitCode)
        assertEquals("""{"ok":false,"error":"x"}""" + "\n", fin.stdout)
        assertFalse(fin.truncated)
    }

    @Test
    fun `a helper that hangs is destroyed on timeout`() {
        requireSh()
        withTempDir { dir ->
            val pidFile = File(dir, "pid")
            val started = System.currentTimeMillis()
            val out = runBlocking {
                JvmHelloProcessRunner.run(listOf("/bin/sh", "-c", """echo $$ > "$1"; exec sleep 60""", "sh", pidFile.absolutePath), 400)
            }
            assertEquals(ProcessOutcome.TimedOut, out)
            assertTrue(System.currentTimeMillis() - started < 15_000, "timeout must not wait for the child")
            assertTrue(waitDead(readPid(pidFile)), "the hung process must be dead")
        }
    }

    @Test
    fun `descendants of a hung helper are destroyed too`() {
        requireSh()
        withTempDir { dir ->
            val pidFile = File(dir, "grandchild")
            val out = runBlocking {
                JvmHelloProcessRunner.run(
                    listOf("/bin/sh", "-c", """sleep 60 & echo $! > "$1"; wait""", "sh", pidFile.absolutePath), 400,
                )
            }
            assertEquals(ProcessOutcome.TimedOut, out)
            assertTrue(waitDead(readPid(pidFile)), "the grandchild holding our pipe must be dead")
        }
    }

    @Test
    fun `cancelling the caller destroys the process`() {
        requireSh()
        withTempDir { dir ->
            val pidFile = File(dir, "pid")
            runBlocking {
                val job = launch(Dispatchers.Default) {
                    JvmHelloProcessRunner.run(listOf("/bin/sh", "-c", """echo $$ > "$1"; exec sleep 60""", "sh", pidFile.absolutePath), 120_000)
                }
                val pid = readPid(pidFile)
                delay(50)
                job.cancelAndJoin()
                assertTrue(waitDead(pid), "leaving the screen mid-prompt must not orphan the helper")
            }
        }
    }

    @Test
    fun `a helper that cannot be started is a typed failure`() {
        val out = runBlocking { JvmHelloProcessRunner.run(listOf("/definitely/not/a/real/wd-hello.exe", "status"), 5_000) }
        assertIs<ProcessOutcome.StartFailed>(out)
    }

    @Test
    fun `runaway output is capped and flagged not buffered`() {
        requireSh()
        val out = runBlocking {
            JvmHelloProcessRunner.run(listOf("/bin/sh", "-c", "head -c 500000 /dev/zero | tr '\\0' a"), 20_000)
        }
        val fin = assertIs<ProcessOutcome.Finished>(out)
        assertTrue(fin.truncated)
        assertEquals(JvmHelloProcessRunner.MAX_STDOUT_BYTES, fin.stdout.length)
    }

    @Test
    fun `end to end through the real runner a fake helper script is parsed`() {
        requireSh()
        withTempDir { dir ->
            val script = File(dir, "fake-wd-hello").apply {
                writeText("#!/bin/sh\nprintf '%s\\n' '$signOk'\n")
                setExecutable(true)
            }
            val helper = ProcessHelloHelper(locate = { script }, isWindows = { true })
            val out = runBlocking { helper.sign("abc") }
            assertEquals("AAECAw+/AA==", assertIs<HelperOutcome.Success<SignedNonce>>(out).value.signatureB64)
        }
    }
}
