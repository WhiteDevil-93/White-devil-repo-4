package com.whitedevil.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lines of scrollback the emulator keeps; beyond this the oldest are dropped from the front. */
private const val SCROLLBACK_LINES = 5_000

private const val BACKGROUND = 0x0C0A09
private const val FOREGROUND = 0xD8D2C8

/**
 * One WSL shell and the JediTerm widget that renders it.
 *
 * Owned by the window, not by [TerminalScreen]: the Shell tab leaves composition
 * whenever another tab is selected, and tying the process to the screen would kill
 * a running build or an open `vim` on every tab switch. The screen only borrows
 * [widget] to display it.
 */
class ShellSession {
    /** The widget to display, or null before the first successful start. */
    var widget: JediTermWidget? by mutableStateOf(null)
        private set
    var running: Boolean by mutableStateOf(false)
        private set
    var status: String by mutableStateOf("")
        private set

    private var terminal: WslTerminal? = null

    /** Starts the shell if none is running. Call from the UI thread — it builds Swing components. */
    suspend fun ensureStarted() {
        if (running) return
        val available = withContext(Dispatchers.IO) { WslTerminal.isWslAvailable() }
        if (!available) {
            status = "wsl.exe not available on this machine."
            return
        }
        restart()
    }

    /** Discards any current shell and starts a fresh one. Call from the UI thread. */
    fun restart() {
        stop()

        val shell = WslTerminal(onExit = { code ->
            running = false
            status = "Shell exited (code $code)."
        })
        val connector = shell.start().getOrElse {
            status = "Could not start the shell: ${it.message}"
            return
        }

        // A widget cannot be restarted once its session has ended, so each start
        // gets a new one.
        val next = JediTermWidget(80, 24, ShellSettings())
        next.ttyConnector = connector
        next.start()

        terminal = shell
        widget = next
        running = true
        status = ""
    }

    /** Wipes the scrollback and asks the shell to redraw its prompt. */
    fun clear() {
        val w = widget ?: return
        w.terminalTextBuffer.clearHistory()
        // Ctrl-L: bash/zsh clear the screen and repaint the prompt. Anything else
        // that owns the screen (vim, the agent CLI) treats it as its own redraw.
        if (running) runCatching { w.ttyConnector?.write("\u000c") }
    }

    /** Kills the shell and drops the widget. Idempotent. */
    fun stop() {
        val w = widget
        val shell = terminal
        widget = null
        terminal = null
        running = false
        // Kill the child first so the widget's reader is unblocked by EOF rather
        // than sitting on a live process.
        shell?.close()
        if (w != null) runCatching { w.close() }
    }
}

/** App colours instead of JediTerm's white-on-black defaults; everything else is stock. */
internal class ShellSettings : DefaultSettingsProvider() {
    override fun getTerminalFontSize(): Float = 13f

    override fun getBufferMaxLinesCount(): Int = SCROLLBACK_LINES

    override fun getDefaultStyle(): TextStyle = TextStyle(terminalColor(FOREGROUND), terminalColor(BACKGROUND))
}

private fun terminalColor(rgb: Int): TerminalColor =
    TerminalColor.rgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
