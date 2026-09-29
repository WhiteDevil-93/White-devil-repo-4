package com.whitedevil.desktop

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The parts of the Shell tab that can be checked without a pty, a display or WSL: how the
 * command line and environment are built, font choice, palette shape and process teardown.
 * Nothing here proves the terminal renders or resizes; that needs the real app on Windows.
 */
class TerminalLaunchTest {

    // ---- command line -------------------------------------------------------------

    @Test
    fun defaultCommandIsBareWslExe() {
        assertEquals(listOf("wsl.exe"), WslLaunch.commandLine())
    }

    @Test
    fun distributionAndStartingDirAreForwardedInOrder() {
        assertEquals(
            listOf("wsl.exe", "-d", "Ubuntu-22.04", "--cd", "~/venice_run"),
            WslLaunch.commandLine(distribution = "Ubuntu-22.04", startingDir = "~/venice_run"),
        )
    }

    @Test
    fun blankDistributionAndDirAreIgnored() {
        assertEquals(listOf("wsl.exe"), WslLaunch.commandLine(distribution = "  ", startingDir = ""))
        assertEquals(listOf("wsl.exe", "--cd", "/tmp"), WslLaunch.commandLine(distribution = null, startingDir = "/tmp"))
    }

    // ---- environment --------------------------------------------------------------

    @Test
    fun environmentForcesXterm256colorAndKeepsTheRest() {
        val base = mapOf("Path" to "C:\\Windows", "USERNAME" to "me")
        val env = WslLaunch.environment(base)
        assertEquals("xterm-256color", env["TERM"])
        assertEquals("C:\\Windows", env["Path"])
        assertEquals("me", env["USERNAME"])
    }

    @Test
    fun environmentReplacesAnyExistingTermSpellingInsteadOfDuplicatingIt() {
        val env = WslLaunch.environment(mapOf("Term" to "dumb", "TERM" to "vt100", "X" to "1"))
        assertEquals(listOf("TERM"), env.keys.filter { it.equals("term", ignoreCase = true) })
        assertEquals("xterm-256color", env["TERM"])
        assertEquals("1", env["X"])
    }

    @Test
    fun environmentDoesNotMutateItsInput() {
        val base = mapOf("TERM" to "dumb")
        WslLaunch.environment(base)
        assertEquals(mapOf("TERM" to "dumb"), base)
    }

    // ---- size validation (what WslTtyConnector.resize accepts) ---------------------

    @Test
    fun onlyPositiveSizesAreValid() {
        assertTrue(WslLaunch.isValidSize(80, 24))
        assertFalse(WslLaunch.isValidSize(0, 24))
        assertFalse(WslLaunch.isValidSize(80, 0))
        assertFalse(WslLaunch.isValidSize(-1, -1))
    }

    // ---- fonts and palette --------------------------------------------------------

    @Test
    fun fontChoiceFollowsPreferenceOrder() {
        val installed = setOf("Courier New", "Consolas", "Arial")
        assertEquals("Consolas", TerminalFonts.choose { it in installed })
        assertEquals("Cascadia Mono", TerminalFonts.choose { true })
    }

    @Test
    fun fontChoiceFallsBackToLogicalMonospacedNeverAProportionalFont() {
        assertEquals("Monospaced", TerminalFonts.choose { false })
    }

    @Test
    fun paletteCoversAllSixteenAnsiColours() {
        // JediTerm indexes the palette 0..15 and asserts on anything else.
        assertEquals(16, TerminalTheme.ANSI_RGB.size)
        assertEquals(0xCD3131, TerminalTheme.ANSI_RGB[1]) // ANSI red, as SGR 31 selects
    }

    // ---- process teardown ---------------------------------------------------------

    @Test
    fun terminateProcessLeavesAnAlreadyDeadProcessAlone() {
        val p = FakeProcess(alive = false)
        terminateProcess(p, graceMillis = 20)
        assertEquals(0, p.destroyCalls)
        assertEquals(0, p.forcedCalls)
    }

    @Test
    fun terminateProcessDoesNotForceAProcessThatExitsOnDestroy() {
        val p = FakeProcess(alive = true, diesOnDestroy = true)
        terminateProcess(p, graceMillis = 200)
        assertEquals(1, p.destroyCalls)
        assertEquals(0, p.forcedCalls)
        assertFalse(p.isAlive)
    }

    @Test
    fun terminateProcessForceKillsAProcessThatIgnoresDestroy() {
        val p = FakeProcess(alive = true, diesOnDestroy = false)
        terminateProcess(p, graceMillis = 20)
        assertEquals(1, p.destroyCalls)
        assertEquals(1, p.forcedCalls)
        assertFalse(p.isAlive)
    }

    @Test
    fun terminateProcessSurvivesADestroyThatThrows() {
        val p = FakeProcess(alive = true, diesOnDestroy = false, destroyThrows = true)
        terminateProcess(p, graceMillis = 20)
        assertEquals(1, p.forcedCalls)
    }

    /** A java.lang.Process that records how it was stopped. */
    private class FakeProcess(
        alive: Boolean,
        private val diesOnDestroy: Boolean = false,
        private val destroyThrows: Boolean = false,
    ) : Process() {
        @Volatile private var alive = alive
        var destroyCalls = 0
        var forcedCalls = 0

        override fun isAlive(): Boolean = alive
        override fun destroy() {
            destroyCalls++
            if (destroyThrows) throw IllegalStateException("boom")
            if (diesOnDestroy) alive = false
        }
        override fun destroyForcibly(): Process {
            forcedCalls++
            alive = false
            return this
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            val deadline = System.nanoTime() + unit.toNanos(timeout)
            while (alive && System.nanoTime() < deadline) Thread.sleep(2)
            return !alive
        }
        override fun waitFor(): Int { while (alive) Thread.sleep(2); return 0 }
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = InputStream.nullInputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
    }
}
