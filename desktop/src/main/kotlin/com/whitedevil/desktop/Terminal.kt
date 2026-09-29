package com.whitedevil.desktop

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A real pty into WSL, replacing ttyd.
 *
 * ttyd existed because a *browser* needed a terminal on another machine. This app
 * runs on the laptop itself, so it can talk to the shell directly — which removes
 * the whole /api/term/paste queue, its silent eviction of pastes when full, and
 * the "every paste reported success" bug that came from awaiting nothing.
 *
 * A pty rather than a plain pipe: with pipes, wsl.exe gives no prompt, line
 * editing does not work, and anything curses-based breaks.
 *
 * This class owns the *process* — spawning it with TERM set, and killing it. It
 * does not read the output: the JediTerm widget does, through [PtyTtyConnector],
 * because an emulator has to consume the byte stream itself (escape sequences
 * can straddle reads).
 */
class WslTerminal(
    private val onExit: (Int) -> Unit = {},
) {
    private var process: PtyProcess? = null
    private var closed = false
    private val lock = Any()

    val isRunning: Boolean get() = synchronized(lock) { process?.isAlive == true }

    /**
     * Starts `wsl.exe` in the user's default distribution and returns the connector
     * a terminal emulator should read from and write to.
     *
     * [distribution] selects one explicitly; null uses the default. [startingDir]
     * is a WSL-side path (e.g. "~/venice_run"), not a Windows one. [columns] and
     * [rows] are only the size the process is born with — the emulator resizes it
     * as soon as it knows its real dimensions.
     */
    fun start(
        distribution: String? = null,
        startingDir: String? = null,
        columns: Int = 80,
        rows: Int = 24,
    ): Result<TtyConnector> = runCatching {
        synchronized(lock) {
            check(process?.isAlive != true) { "terminal already running" }
            closed = false

            val command = buildList {
                add("wsl.exe")
                if (!distribution.isNullOrBlank()) { add("-d"); add(distribution) }
                if (!startingDir.isNullOrBlank()) {
                    // --cd needs a real path; ~ is not expanded by wsl.exe itself.
                    add("--cd"); add(startingDir)
                }
            }.toTypedArray()

            val env = HashMap(System.getenv())
            // Without TERM, readline and anything curses-based degrades badly. The
            // emulator implements xterm, so say so.
            env["TERM"] = "xterm-256color"

            val proc = PtyProcessBuilder(command)
                .setEnvironment(env)
                .setConsole(false)
                .setRedirectErrorStream(true)
                .setInitialColumns(columns)
                .setInitialRows(rows)
                .start()
            process = proc

            // Exit is watched here rather than inferred from the emulator's read
            // loop ending, so the UI hears about it exactly once and only when the
            // shell died on its own — not when close() killed it.
            thread(isDaemon = true, name = "wsl-pty-exit") {
                val code = runCatching { proc.waitFor() }.getOrDefault(-1)
                val deliberate = synchronized(lock) { closed || process !== proc }
                if (!deliberate) onExit(code)
            }

            PtyTtyConnector(proc)
        }
    }

    /** Kills the child. Safe to call repeatedly, and when nothing was ever started. */
    fun close() {
        val proc = synchronized(lock) {
            closed = true
            process.also { process = null }
        } ?: return
        runCatching { proc.destroy() }
        // destroy() is a polite request; an orphaned wsl.exe holds the distro
        // awake, so make sure.
        val stillAlive = runCatching { !proc.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(true)
        if (stillAlive) runCatching { proc.destroyForcibly() }
    }

    companion object {
        /** Whether wsl.exe is present at all, so the UI can say so instead of failing oddly. */
        fun isWslAvailable(): Boolean = runCatching {
            val probe = ProcessBuilder("wsl.exe", "--status")
                .redirectErrorStream(true)
                .start()
            // `wsl --status` can hang while the WSL service is starting; do not let
            // that freeze the app.
            if (!probe.waitFor(10, TimeUnit.SECONDS)) {
                probe.destroyForcibly()
                false
            } else {
                probe.exitValue() == 0
            }
        }.getOrDefault(false)
    }
}

/**
 * Adapts a pty4j process to JediTerm's [TtyConnector]: the emulator calls [read]
 * from its own thread and [write] on keystrokes, and tells us the size via [resize].
 *
 * Output is decoded through a streaming UTF-8 reader, not `String(bytes)` per read:
 * a multi-byte character split across two reads must not turn into two U+FFFD.
 * [read] returns whatever has arrived, so partial reads are forwarded as they
 * come rather than held until a line ends.
 */
internal class PtyTtyConnector(private val process: PtyProcess) : TtyConnector {
    private val reader = InputStreamReader(process.inputStream, StandardCharsets.UTF_8)
    private val output = process.outputStream

    override fun read(buf: CharArray, offset: Int, length: Int): Int =
        reader.read(buf, offset, length)

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun write(string: String) = write(string.toByteArray(StandardCharsets.UTF_8))

    override fun isConnected(): Boolean = process.isAlive

    override fun ready(): Boolean = reader.ready()

    override fun waitFor(): Int = process.waitFor()

    override fun getName(): String = "wsl"

    /** Tells the pty its new size, so full-screen programs redraw correctly. */
    override fun resize(termSize: TermSize) {
        if (termSize.columns <= 0 || termSize.rows <= 0) return
        if (!process.isAlive) return
        runCatching { process.winSize = WinSize(termSize.columns, termSize.rows) }
    }

    // Killing the child is WslTerminal.close()'s job (it owns the escalation to
    // destroyForcibly); the widget closing us must not race that with a second
    // destroy, so this only makes sure a blocked read wakes up.
    override fun close() {
        runCatching { process.inputStream.close() }
    }
}
