package com.whitedevil.desktop

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.io.IOException
import java.nio.charset.StandardCharsets
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
 */
class WslTerminal(
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit = {},
) {
    private var process: PtyProcess? = null
    private val lock = Any()

    val isRunning: Boolean get() = synchronized(lock) { process?.isAlive == true }

    /**
     * Starts `wsl.exe` in the user's default distribution.
     *
     * [distribution] selects one explicitly; null uses the default. [startingDir]
     * is a WSL-side path (e.g. "~/venice_run"), not a Windows one.
     */
    fun start(distribution: String? = null, startingDir: String? = null): Result<Unit> = runCatching {
        synchronized(lock) {
            check(process?.isAlive != true) { "terminal already running" }

            val command = buildList {
                add("wsl.exe")
                if (!distribution.isNullOrBlank()) { add("-d"); add(distribution) }
                if (!startingDir.isNullOrBlank()) {
                    // --cd needs a real path; ~ is not expanded by wsl.exe itself.
                    add("--cd"); add(startingDir)
                }
            }.toTypedArray()

            val env = HashMap(System.getenv())
            // Without TERM, readline and anything curses-based degrades badly.
            env["TERM"] = "xterm-256color"

            val proc = PtyProcessBuilder(command)
                .setEnvironment(env)
                .setConsole(false)
                .setRedirectErrorStream(true)
                .start()
            process = proc

            // One reader thread; pty output is a stream, not messages, so partial
            // reads are normal and must be forwarded as they arrive rather than
            // buffered until a newline.
            thread(isDaemon = true, name = "wsl-pty-reader") {
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val n = proc.inputStream.read(buffer)
                        if (n < 0) break
                        if (n > 0) onOutput(String(buffer, 0, n, StandardCharsets.UTF_8))
                    }
                } catch (_: IOException) {
                    // Expected when the process is killed from close().
                } finally {
                    val code = runCatching { proc.waitFor() }.getOrDefault(-1)
                    onExit(code)
                }
            }
        }
    }

    /** Sends input. Callers append "\n" themselves; a bare command does not run without it. */
    fun write(text: String): Result<Unit> = runCatching {
        val proc = synchronized(lock) { process } ?: error("terminal is not running")
        check(proc.isAlive) { "terminal has exited" }
        proc.outputStream.write(text.toByteArray(StandardCharsets.UTF_8))
        proc.outputStream.flush()
    }

    /** Tells the pty its new size, so full-screen programs redraw correctly. */
    fun resize(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        synchronized(lock) {
            runCatching { process?.winSize = WinSize(columns, rows) }
        }
    }

    fun close() {
        synchronized(lock) {
            process?.let { runCatching { it.destroy() } }
            process = null
        }
    }

    companion object {
        /** Whether wsl.exe is present at all, so the UI can say so instead of failing oddly. */
        fun isWslAvailable(): Boolean = runCatching {
            ProcessBuilder("wsl.exe", "--status")
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        }.getOrDefault(false)
    }
}
