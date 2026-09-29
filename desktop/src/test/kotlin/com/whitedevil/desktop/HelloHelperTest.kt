package com.whitedevil.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class HelloOutputTest {
    private fun parse(stdout: String, exit: Int = 0) = HelloOutput.parse(stdout, exit)

    @Test
    fun `create output yields the public key`() {
        val r = parse("""{"key_name":"K","algorithm":"RS256","public_key_pem":"-----BEGIN PUBLIC KEY-----\nAAA\n-----END PUBLIC KEY-----\n","ok":true}""")
        assertTrue(r.getOrThrow().publicKeyPem!!.startsWith("-----BEGIN PUBLIC KEY-----\n"))
    }

    @Test
    fun `sign output yields the signature`() {
        assertEquals("c2ln", parse("""{"signature_b64":"c2ln","ok":true}""").getOrThrow().signatureB64)
    }

    @Test
    fun `status output carries availability and key presence`() {
        val j = parse("""{"hello_available":true,"key_exists":false,"key_name":"K","detail":"d","ok":true}""").getOrThrow()
        assertEquals(true, j.helloAvailable)
        assertEquals(false, j.keyExists)
    }

    @Test
    fun `ok false is a failure carrying the helper's reason`() {
        val r = parse("""{"ok":false,"error":"you cancelled the Windows Hello prompt"}""", exit = 1)
        assertEquals("you cancelled the Windows Hello prompt", r.exceptionOrNull()?.message)
    }

    @Test
    fun `ok false is a failure even when the exit code says success`() {
        assertTrue(parse("""{"ok":false,"error":"nope"}""", exit = 0).isFailure)
    }

    @Test
    fun `ok true with a non-zero exit is not trusted`() {
        val r = parse("""{"ok":true,"signature_b64":"c2ln"}""", exit = 3)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("not trusting"))
    }

    @Test
    fun `only the last line is parsed so a banner cannot hide the result`() {
        assertTrue(parse("some banner\n\n{\"ok\":true}\n").isSuccess)
    }

    @Test
    fun `empty output is a failure`() {
        assertTrue(parse("", exit = 1).exceptionOrNull()!!.message!!.contains("no output"))
    }

    @Test
    fun `garbage is a failure and never echoes the raw output`() {
        val r = parse("""{"signature_b64":"SECRETSIGNATURE" this is not json""", exit = 0)
        assertTrue(r.isFailure)
        assertFalse(r.exceptionOrNull()!!.message!!.contains("SECRETSIGNATURE"))
    }

    @Test
    fun `a missing ok field is a failure not a success`() {
        assertTrue(parse("""{"signature_b64":"c2ln"}""").isFailure)
    }

    @Test
    fun `unknown fields are ignored`() {
        assertTrue(parse("""{"ok":true,"future_field":[1,2,3]}""").isSuccess)
    }

    @Test
    fun `toString does not leak the signature`() {
        val j = parse("""{"signature_b64":"SECRETSIGNATURE","ok":true}""").getOrThrow()
        assertFalse(j.toString().contains("SECRETSIGNATURE"))
    }
}

class HelperLocatorTest {
    private val exe = HelperLocator.EXE_NAME
    private val tfm = HelperLocator.DEV_TFM

    private fun locator(
        env: Map<String, String> = emptyMap(),
        props: Map<String, String> = emptyMap(),
        cwd: String = "/repo/desktop",
        present: Set<File> = emptySet(),
    ) = HelperLocator(env, { props[it] }, { it in present }, File(cwd))

    private val devBuild = File("/repo/desktop/hello-helper/bin/Release/$tfm/win-x64/$exe")

    @Test
    fun `explicit override wins over everything`() {
        val override = File("/custom/$exe")
        val l = locator(
            env = mapOf("WD_HELLO_PATH" to override.path),
            props = mapOf("compose.application.resources.dir" to "/install/app/resources"),
            present = setOf(override, File("/install/app/resources/$exe"), devBuild),
        )
        assertEquals(override, l.find())
    }

    @Test
    fun `the installed app's resources beat the dev build`() {
        val installed = File("/install/app/resources/$exe")
        val l = locator(props = mapOf("compose.application.resources.dir" to "/install/app/resources"), present = setOf(installed, devBuild))
        assertEquals(installed, l.find())
    }

    @Test
    fun `finds it beside the jpackage launcher`() {
        for (rel in listOf(exe, "app/$exe", "app/resources/$exe")) {
            val f = File("/Program Files/WhiteDevil/$rel")
            val l = locator(props = mapOf("jpackage.app-path" to "/Program Files/WhiteDevil/WhiteDevil.exe"), present = setOf(f))
            assertEquals(f, l.find(), "layout $rel")
        }
    }

    @Test
    fun `falls back to the dev build when run from desktop`() {
        assertEquals(devBuild, locator(cwd = "/repo/desktop", present = setOf(devBuild)).find())
    }

    @Test
    fun `falls back to the dev build when run from the repo root`() {
        assertEquals(devBuild, locator(cwd = "/repo", present = setOf(devBuild)).find())
    }

    @Test
    fun `falls back to the dev build from a subdirectory`() {
        assertEquals(devBuild, locator(cwd = "/repo/desktop/build", present = setOf(devBuild)).find())
    }

    @Test
    fun `finds a published single-file build`() {
        val published = File("/repo/desktop/hello-helper/bin/Release/$tfm/win-x64/publish/$exe")
        assertEquals(published, locator(present = setOf(published)).find())
    }

    @Test
    fun `nothing found gives null and a non-empty candidate list to report`() {
        val l = locator()
        assertNull(l.find())
        assertTrue(l.candidates().isNotEmpty())
    }
}

class ProcessHelloBackendTest {
    private val exe = File("/x/wd-hello.exe")
    // A locator that always resolves to [exe].
    private val found = HelperLocator(mapOf("WD_HELLO_PATH" to exe.path), { null }, { f -> f == exe }, File("/nowhere"))

    private class Recorder(private val reply: (List<String>) -> ProcessResult) : ProcessRunner {
        val calls = mutableListOf<List<String>>()
        override fun run(command: List<String>, timeoutMs: Long): ProcessResult {
            calls += command
            return reply(command)
        }
    }

    private fun ok(json: String) = ProcessResult(json, "", 0)

    @Test
    fun `status passes the key name and parses the answer`() {
        val r = Recorder { ok("""{"hello_available":true,"key_exists":true,"detail":"Ready.","ok":true}""") }
        val st = ProcessHelloBackend(found, r).status().getOrThrow()
        assertEquals(listOf(exe.absolutePath, "status", "WhiteDevilDeviceKey"), r.calls.single())
        assertTrue(st.available && st.keyExists)
    }

    @Test
    fun `sign passes key name then nonce as separate arguments`() {
        val r = Recorder { ok("""{"signature_b64":"c2ln","ok":true}""") }
        val sig = ProcessHelloBackend(found, r).sign("abc_-123").getOrThrow()
        assertEquals("c2ln", sig)
        assertEquals(listOf(exe.absolutePath, "sign", "WhiteDevilDeviceKey", "abc_-123"), r.calls.single())
    }

    @Test
    fun `a blank nonce is refused without running the helper`() {
        val r = Recorder { fail("must not run") }
        assertTrue(ProcessHelloBackend(found, r).sign(" ").isFailure)
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun `create returns the pem`() {
        val r = Recorder { ok("""{"public_key_pem":"PEM","ok":true}""") }
        assertEquals("PEM", ProcessHelloBackend(found, r).createKey().getOrThrow())
    }

    @Test
    fun `success without the expected field is a failure`() {
        val r = Recorder { ok("""{"ok":true}""") }
        assertTrue(ProcessHelloBackend(found, r).createKey().isFailure)
        assertTrue(ProcessHelloBackend(found, r).sign("n").isFailure)
    }

    @Test
    fun `a missing helper is reported without running anything`() {
        val r = Recorder { fail("must not run") }
        val none = HelperLocator(emptyMap(), { null }, { false }, File("/nowhere"))
        val e = ProcessHelloBackend(none, r).status().exceptionOrNull()
        assertNotNull(e)
        assertTrue(e.message!!.contains("wd-hello.exe was not found"))
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun `a runner timeout becomes a failure`() {
        val r = Recorder { throw HelloException("did not finish within 120s") }
        assertTrue(ProcessHelloBackend(found, r).sign("n").exceptionOrNull()!!.message!!.contains("did not finish"))
    }

    @Test
    fun `a crash with no json surfaces stderr`() {
        val r = Recorder { ProcessResult("", "the runtime is missing", 1) }
        assertTrue(ProcessHelloBackend(found, r).status().exceptionOrNull()!!.message!!.contains("the runtime is missing"))
    }
}

class SystemProcessRunnerTest {
    private val linux = System.getProperty("os.name").startsWith("Linux")

    @Test
    fun `captures stdout and the exit code`() {
        if (!linux) return
        val r = SystemProcessRunner.run(listOf("sh", "-c", "echo hello; exit 3"), 5_000)
        assertEquals("hello", r.stdout.trim())
        assertEquals(3, r.exitCode)
    }

    @Test
    fun `kills a process that outlives the timeout`() {
        if (!linux) return
        val started = System.currentTimeMillis()
        val e = runCatching { SystemProcessRunner.run(listOf("sh", "-c", "sleep 30"), 300) }.exceptionOrNull()
        assertTrue(e is HelloException, "expected a timeout, got $e")
        assertTrue(System.currentTimeMillis() - started < 5_000, "timeout did not fire promptly")
    }

    @Test
    fun `a missing executable is a clear failure not a crash`() {
        val e = runCatching { SystemProcessRunner.run(listOf("/definitely/not/here/wd-hello.exe"), 1_000) }.exceptionOrNull()
        assertTrue(e is HelloException)
    }
}
