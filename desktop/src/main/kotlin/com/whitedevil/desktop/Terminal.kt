package com.whitedevil.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalWidgetListener
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * How the WSL shell is launched. Pure (no pty, no Swing), so it is unit-tested.
 */
internal object WslLaunch {
    /** Without TERM, readline and anything curses-based degrades badly. */
    const val TERM = "xterm-256color"

    /** JediTermWidget's own initial grid; used until the real component size is known. */
    const val DEFAULT_COLUMNS = 80
    const val DEFAULT_ROWS = 24

    /**
     * `wsl.exe` in the user's default distribution; [distribution] selects one explicitly
     * and [startingDir] is a WSL-side path (e.g. "~/venice_run"), not a Windows one.
     */
    fun commandLine(distribution: String? = null, startingDir: String? = null): List<String> = buildList {
        add("wsl.exe")
        if (!distribution.isNullOrBlank()) { add("-d"); add(distribution) }
        if (!startingDir.isNullOrBlank()) {
            // --cd needs a real path; ~ is not expanded by wsl.exe itself.
            add("--cd"); add(startingDir)
        }
    }

    /**
     * The child's environment: [base] with TERM forced to [TERM]. Windows environment
     * names are case-insensitive, so any existing spelling of TERM is dropped rather than
     * left beside ours. Returns a copy; [base] is never modified.
     */
    fun environment(base: Map<String, String>): Map<String, String> {
        val env = HashMap<String, String>(base.size + 1)
        for ((k, v) in base) if (!k.equals("TERM", ignoreCase = true)) env[k] = v
        env["TERM"] = TERM
        return env
    }

    fun isValidSize(columns: Int, rows: Int): Boolean = columns > 0 && rows > 0

    /** Whether wsl.exe is present at all, so the UI can say so instead of failing oddly. */
    fun isWslAvailable(timeoutSeconds: Long = 10): Boolean = runCatching {
        val probe = ProcessBuilder("wsl.exe", "--status")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (probe.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            probe.exitValue() == 0
        } else {
            probe.destroyForcibly() // a hung wsl.exe must not hang the Shell tab too
            false
        }
    }.getOrDefault(false)
}

/**
 * Stops [p]: polite destroy first, then a forced kill if it is still alive after
 * [graceMillis]. Never throws; a process that is already gone is left alone.
 *
 * The second step matters on Unix, where pty4j's destroy() is a SIGHUP that a child can
 * ignore (an interactive /bin/sh did, in a Linux check). On Windows, pty4j's ConPTY
 * destroy() is TerminateProcess; its winpty destroy() frees the agent, which closes the
 * console and the processes on it.
 */
internal fun terminateProcess(p: Process, graceMillis: Long = 750) {
    if (!p.isAlive) return
    runCatching { p.destroy() }
    val exited = runCatching { p.waitFor(graceMillis, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    if (!exited) runCatching { p.destroyForcibly() }
}

/**
 * Adapts a pty4j process to JediTerm.
 *
 * JediTerm's own pty connector lives in separate artifacts (jediterm-pty 2.69,
 * jediterm-core-pty 3.0) that stop far behind jediterm-core 3.53, so they are not used.
 * Owning these few lines keeps pty4j at the version this app already uses, and keeps
 * process teardown (and its Windows/Unix differences) in our hands.
 *
 * The base class decodes with an InputStreamReader, so partial reads are forwarded as
 * they arrive and a UTF-8 sequence split across two reads is reassembled rather than
 * turned into U+FFFD (the old String(buffer, 0, n) did corrupt those).
 */
internal class WslTtyConnector(
    private val pty: PtyProcess,
    commandLine: List<String>,
    /** "ConPTY" or "winpty" on Windows; shown in the header so a degraded backend is visible. */
    val backend: String,
) : ProcessTtyConnector(pty, StandardCharsets.UTF_8, commandLine) {

    /** Set when a newer session (restart) or app shutdown has taken over; suppresses stale status updates. */
    @Volatile
    var superseded = false

    override fun getName(): String = "WSL"

    /**
     * Called by JediTerm whenever the terminal grid changes size (window drag, first
     * layout). This is what tells the pty its new size so vim/htop redraw correctly.
     */
    override fun resize(termSize: TermSize) {
        if (!WslLaunch.isValidSize(termSize.columns, termSize.rows)) return
        runCatching { pty.winSize = WinSize(termSize.columns, termSize.rows) }
    }

    /**
     * Idempotent and synchronized: the emulator thread, JediTerm's starter and the JVM
     * shutdown hook can all reach this, and a second caller must not return (and let the
     * JVM exit) while the first is still mid-kill.
     */
    @Synchronized
    override fun close() {
        terminateProcess(pty)
        runCatching { myOutputStream.close() }
        runCatching { myInputStream.close() }
    }
}

/**
 * The one WSL shell session: pty, JediTerm widget and status, owned by the process rather
 * than by any composable.
 *
 * Why process-scoped: Main.kt renders `when (screen) { Screen.Terminal -> TerminalScreen() }`,
 * so TerminalScreen leaves composition on every tab switch. State or a DisposableEffect
 * inside it would kill the shell each time you looked at another tab. Here the pty and
 * widget survive; the composable only re-parents [view] into a fresh SwingPanel.
 *
 * The child dies with the app via a JVM shutdown hook (see [shared]). Compose's
 * `application {}` ends in System.exit, which runs hooks, whichever composable is or is
 * not on screen at that moment. Nothing kills the child on a tab switch.
 */
internal class WslShell(
    /** Seams for tests, so the lifecycle can run against /bin/sh on a machine with no wsl.exe. */
    private val commandLine: (distribution: String?, startingDir: String?) -> List<String> =
        { d, s -> WslLaunch.commandLine(d, s) },
    private val isAvailable: () -> Boolean = { WslLaunch.isWslAvailable() },
) {
    enum class Phase { Idle, Starting, Running, Exited, Failed }

    data class Status(
        val phase: Phase = Phase.Idle,
        val message: String = "",
        val backend: String = "",
    ) {
        val running: Boolean get() = phase == Phase.Running
    }

    /** Observed by TerminalScreen. Written on the UI thread only. */
    var status by mutableStateOf(Status())
        private set

    /**
     * Stable Swing host for whichever JediTermWidget is current. TerminalScreen hands this
     * same component to a new SwingPanel every time the tab is opened; a restart swaps the
     * widget inside it without the composable noticing.
     */
    val view: JPanel = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = Color(TerminalTheme.BACKGROUND_RGB)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lock = Any()

    /** UI thread only. */
    private var widget: JediTermWidget? = null

    /** Guarded by [lock]: also read by the shutdown hook, off the UI thread. */
    private var connector: WslTtyConnector? = null

    @Volatile
    private var closed = false

    /** Starts the shell the first time the Shell tab is opened; never restarts one that exited. */
    fun ensureStarted() {
        if (status.phase == Phase.Idle) start()
    }

    /**
     * Starts a shell unless one is already starting or running. Returns immediately; the
     * work runs on this object's own scope, so leaving the tab mid-start cannot cancel it
     * and strand a half-created process.
     */
    fun start(distribution: String? = null, startingDir: String? = null) {
        scope.launch {
            if (closed || status.phase == Phase.Starting || status.phase == Phase.Running) return@launch
            status = Status(Phase.Starting)
            try {
                launchShell(distribution, startingDir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = Status(Phase.Failed, "Could not start the shell: ${e.message}")
            }
        }
    }

    /** Kills the current shell, if any, and starts a fresh one in a clean widget. */
    fun restart() {
        scope.launch {
            // A start already in flight owns the widget and pty; restarting under it
            // would create a second shell.
            if (closed || status.phase == Phase.Starting) return@launch
            val old = takeConnector()
            if (old != null) {
                old.superseded = true
                withContext(Dispatchers.IO) { old.close() }
            }
            status = Status(Phase.Idle)
            start()
        }
    }

    /** Clears the visible screen and scrollback; the same action as the widget's own "Clear Buffer". */
    fun clear() {
        SwingUtilities.invokeLater {
            widget?.terminalPanel?.clearBuffer()
            widget?.terminalPanel?.requestFocusInWindow()
        }
    }

    fun focusTerminal() {
        SwingUtilities.invokeLater { widget?.terminalPanel?.requestFocusInWindow() }
    }

    /**
     * Kills the child. Safe from any thread and does not touch Swing, so it can run in the
     * JVM shutdown hook. Idempotent.
     */
    fun shutdown() {
        val c = synchronized(lock) {
            closed = true
            connector.also { connector = null }
        }
        c?.let { it.superseded = true; it.close() }
        runCatching { scope.cancel() }
    }

    // ---- internals ----------------------------------------------------------------

    private suspend fun launchShell(distribution: String?, startingDir: String?) {
        // Off the UI thread: both probe the OS (wsl.exe --status; font enumeration).
        val wslPresent = withContext(Dispatchers.IO) {
            TerminalFonts.warmUp()
            isAvailable()
        }
        if (!wslPresent) {
            status = Status(Phase.Failed, "wsl.exe not available on this machine.")
            return
        }

        val w = installFreshWidget()
        // Spawn at the size the component already has, so the shell does not start at
        // 80x24 inside a wide window. JediTerm re-syncs the size once the connector is
        // attached and on every later layout change either way.
        val size = w.terminalPanel.terminalSizeFromComponent
            ?: TermSize(WslLaunch.DEFAULT_COLUMNS, WslLaunch.DEFAULT_ROWS)

        val c = withContext(Dispatchers.IO) { spawn(size, distribution, startingDir) }

        // Back on the UI thread.
        if (closed || widget !== w) {
            c.superseded = true
            c.close()
            return
        }
        attach(w, c)
    }

    /** Runs off the UI thread. Registers the connector under [lock] so shutdown can always find it. */
    private fun spawn(size: TermSize, distribution: String?, startingDir: String?): WslTtyConnector {
        val command = commandLine(distribution, startingDir)
        val env = WslLaunch.environment(System.getenv())
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

        // ConPTY passes the child's VT stream through unmodified, which full-screen
        // programs need. pty4j defaults to winpty on Windows, which screen-scrapes the
        // console and mangles alternate-screen and colour output, so it is only the
        // fallback (old Windows, or ConPTY failing to start).
        val backends = if (windows) listOf(true, false) else listOf(false)
        var firstFailure: Exception? = null
        for (conPty in backends) {
            if (closed) break
            try {
                val pty = PtyProcessBuilder(command.toTypedArray())
                    .setEnvironment(env)
                    .setConsole(false)
                    .setRedirectErrorStream(true)
                    .setInitialColumns(size.columns)
                    .setInitialRows(size.rows)
                    .setUseWinConPty(conPty)
                    .setWindowsAnsiColorEnabled(!conPty)
                    .start()
                val c = WslTtyConnector(pty, command, if (!windows) "pty" else if (conPty) "ConPTY" else "winpty")
                val registered = synchronized(lock) {
                    if (closed) false else { connector = c; true }
                }
                if (!registered) {
                    // Shutdown began while the process was starting: kill it here, since
                    // the shutdown hook could not have seen it.
                    c.superseded = true
                    c.close()
                    break
                }
                return c
            } catch (e: IOException) {
                if (firstFailure == null) firstFailure = e
            } catch (e: RuntimeException) {
                if (firstFailure == null) firstFailure = e
            }
        }
        throw firstFailure
            ?: IllegalStateException(if (closed) "application is shutting down" else "could not start wsl.exe")
    }

    /** UI thread. Replaces any previous widget, so a restart begins with a clean screen. */
    private fun installFreshWidget(): JediTermWidget {
        widget?.let { old -> runCatching { old.close() } }
        val w = TerminalTheme.withDarkScrollBars {
            JediTermWidget(
                WslLaunch.DEFAULT_COLUMNS,
                WslLaunch.DEFAULT_ROWS,
                WhiteDevilTerminalSettings(TerminalFonts.family),
            )
        }
        widget = w
        view.removeAll()
        view.add(w, BorderLayout.CENTER)
        view.revalidate()
        view.repaint()
        return w
    }

    /** UI thread. */
    private fun attach(w: JediTermWidget, c: WslTtyConnector) {
        w.addListener(TerminalWidgetListener { onSessionClosed(w, c) })
        // Hooks the connector into the widget: input, output, and resize (widget size
        // changes call c.resize(...), which sets the pty window size).
        w.setTtyConnector(c)
        w.start()
        status = Status(Phase.Running, backend = c.backend)
        focusTerminal()
    }

    /** Called by JediTerm on its emulator thread once the child has gone away. */
    private fun onSessionClosed(w: JediTermWidget, c: WslTtyConnector) {
        val code = runCatching {
            if (c.process.waitFor(2, TimeUnit.SECONDS)) c.process.exitValue() else null
        }.getOrNull()
        synchronized(lock) { if (connector === c) connector = null }
        scope.launch {
            if (!c.superseded && !closed && widget === w) {
                status = Status(
                    Phase.Exited,
                    if (code != null) "Shell exited (code $code)." else "Shell exited.",
                )
            }
        }
    }

    private fun takeConnector(): WslTtyConnector? =
        synchronized(lock) { connector.also { connector = null } }

    companion object {
        /**
         * The Shell tab's session, created on first use. The shutdown hook is what makes the
         * "no orphan wsl.exe on exit" property hold regardless of which tab is showing when
         * the window closes.
         */
        val shared: WslShell by lazy {
            WslShell().also { shell ->
                runCatching {
                    Runtime.getRuntime().addShutdownHook(Thread({ shell.shutdown() }, "wsl-shell-shutdown"))
                }
            }
        }
    }
}
