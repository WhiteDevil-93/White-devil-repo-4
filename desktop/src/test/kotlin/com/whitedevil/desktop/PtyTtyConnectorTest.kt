package com.whitedevil.desktop

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ui.JediTermWidget
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs [PtyTtyConnector] against a real pty on the build machine. Windows-specific
 * behaviour (wsl.exe, ConPTY) is out of reach here; what is checked is the part
 * that is platform-independent: decoding, resize, and the emulator wiring.
 */
class PtyTtyConnectorTest {
    private val started = mutableListOf<PtyProcess>()

    @AfterTest
    fun reap() { started.forEach { it.destroyForcibly() } }

    private fun spawn(vararg command: String): PtyProcess {
        val env = HashMap(System.getenv()).apply { put("TERM", "xterm-256color") }
        return PtyProcessBuilder(command as Array<String>)
            .setEnvironment(env)
            .setConsole(false)
            .setRedirectErrorStream(true)
            .start()
            .also { started += it }
    }

    /**
     * Reads until [predicate] holds or [timeoutMs] passes; returns everything read.
     * Blocking reads happen on a daemon thread, as the emulator does them — polling
     * ready() is not reliable on a pty stream.
     */
    private fun readUntil(c: PtyTtyConnector, timeoutMs: Long = 5_000, predicate: (String) -> Boolean): String {
        val sb = StringBuffer()
        val reader = Thread {
            val buf = CharArray(256)
            try {
                while (true) {
                    val n = c.read(buf, 0, buf.size)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
            } catch (_: java.io.IOException) {
            }
        }.apply { isDaemon = true; start() }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(sb.toString())) return sb.toString()
            Thread.sleep(20)
        }
        reader.interrupt()
        fail("timed out; got: ${sb.toString().replace("\u001b", "ESC")}")
    }

    @Test
    fun `a UTF-8 character split across two reads is not corrupted`() {
        // U+00E9 is 0xC3 0xA9. Emit the halves 300ms apart so they cannot share a read.
        val proc = spawn("sh", "-c", "printf '\\303'; sleep 0.3; printf '\\251\\n'")
        val out = readUntil(PtyTtyConnector(proc)) { "é" in it }
        assertFalse('�' in out, "replacement character in: $out")
    }

    @Test
    fun `output is forwarded as it arrives, not held until the process ends`() {
        val proc = spawn("sh", "-c", "printf first; sleep 3; printf second")
        val started = System.currentTimeMillis()
        readUntil(PtyTtyConnector(proc)) { "first" in it }
        assertTrue(System.currentTimeMillis() - started < 2_000, "first chunk was buffered")
    }

    @Test
    fun `TERM reaches the child`() {
        val proc = spawn("sh", "-c", "echo TERM=\$TERM")
        readUntil(PtyTtyConnector(proc)) { "TERM=xterm-256color" in it }
    }

    @Test
    fun `resize reaches the pty`() {
        val proc = spawn("sh")
        val c = PtyTtyConnector(proc)
        c.resize(TermSize(100, 40))
        c.write("stty size\n")
        readUntil(c) { Regex("(?m)^40 100\\s*$").containsMatchIn(it) }
    }

    @Test
    fun `non-positive sizes are ignored rather than thrown`() {
        val c = PtyTtyConnector(spawn("sh"))
        c.resize(TermSize(0, 0))
        c.resize(TermSize(0, 24))
        c.resize(TermSize(80, 0))
    }

    @Test
    fun `colour reaches the emulator buffer instead of appearing as literal escapes`() {
        System.setProperty("java.awt.headless", "true")
        // Yellow foreground (SGR 33), then reset.
        val proc = spawn("sh", "-c", "printf '\\033[0;33mHELLO\\033[0m plain'; sleep 2")
        val widget = JediTermWidget(80, 24, ShellSettings())
        widget.ttyConnector = PtyTtyConnector(proc)
        widget.start()
        try {
            val deadline = System.currentTimeMillis() + 5_000
            var text = ""
            while (System.currentTimeMillis() < deadline) {
                text = widget.terminalTextBuffer.getScreenLines()
                if ("plain" in text) break
                Thread.sleep(50)
            }
            assertTrue("HELLO plain" in text, "screen was: $text")
            assertFalse("[0;33m" in text, "escape sequence rendered literally: $text")
            // ...and the SGR colour was applied to the cells, not just stripped.
            val buffer = widget.terminalTextBuffer
            buffer.lock()
            try {
                val line = buffer.getLine(0)
                assertEquals(3, line.getStyleAt(0)?.foreground?.colorIndex, "H should be yellow (ANSI 3)")
                assertTrue(line.getStyleAt(7)?.foreground?.colorIndex != 3, "colour leaked past the reset")
            } finally {
                buffer.unlock()
            }
        } finally {
            widget.close()
        }
    }
}
