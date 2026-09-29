package com.whitedevil.desktop

import com.jediterm.core.Color as JtColor
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.ColorPalette
import com.jediterm.terminal.ui.TerminalActionPresentation
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import javax.swing.UIManager

/**
 * Look and behaviour of the embedded JediTerm widget.
 *
 * Kept apart from Terminal.kt so the pty/session lifecycle there has no opinion about
 * colours, and so the pure parts (font choice, palette shape) can be unit-tested
 * without a display.
 */
internal object TerminalTheme {
    /** Same near-black the previous Text-based shell used, so the tab looks unchanged. */
    const val BACKGROUND_RGB = 0x0C0A09
    const val FOREGROUND_RGB = 0xD8D2C8
    private const val LINK_RGB = 0x3B8EEA

    /**
     * Scrollback bound, in lines. JediTerm keeps history in a capped deque and drops the
     * oldest line when a new one arrives past the cap, so a long build cannot grow the
     * buffer without bound (the same property the old 200_000-character cap gave, but
     * counted in lines because that is what the widget stores).
     */
    const val SCROLLBACK_LINES = 10_000

    const val FONT_SIZE = 14f

    /**
     * The 16 ANSI colours: the VS Code dark terminal palette. JediTerm's own XTERM and
     * WINDOWS palettes put pure/dark blue on this near-black background, which is barely
     * legible for `ls --color` directories and vim comments.
     */
    val ANSI_RGB: IntArray = intArrayOf(
        0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5, // normal
        0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF, // bright
    )

    val palette: ColorPalette = object : ColorPalette() {
        private val colors = Array(ANSI_RGB.size) { JtColor(ANSI_RGB[it]) }
        override fun getForegroundByColorIndex(colorIndex: Int): JtColor = colors[colorIndex]
        override fun getBackgroundByColorIndex(colorIndex: Int): JtColor = colors[colorIndex]
    }

    fun terminalColor(rgb: Int): TerminalColor =
        TerminalColor.rgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

    val defaultStyle: TextStyle get() = TextStyle(terminalColor(FOREGROUND_RGB), terminalColor(BACKGROUND_RGB))

    /** UIManager keys BasicScrollBarUI reads when it installs, mapped to a dark palette. */
    private val SCROLLBAR_COLORS = mapOf(
        "ScrollBar.background" to 0x0C0A09,
        "ScrollBar.foreground" to 0x3A3532,
        "ScrollBar.track" to 0x161312,
        "ScrollBar.trackHighlight" to 0x161312,
        "ScrollBar.thumb" to 0x3A3532,
        "ScrollBar.thumbHighlight" to 0x4A4541,
        "ScrollBar.thumbShadow" to 0x2A2624,
        "ScrollBar.thumbDarkShadow" to 0x1A1716,
        "ScrollBar.highlight" to 0x4A4541,
        "ScrollBar.shadow" to 0x1A1716,
    )

    /**
     * JediTermWidget's scrollbar is a stock Swing one: a bright grey bar down the side of a
     * near-black terminal. Its UI captures its colours from UIManager at construction, so
     * they are overridden for exactly the duration of [create] and then removed again,
     * leaving no lasting global change to Swing defaults. Call on the UI thread.
     */
    fun <T> withDarkScrollBars(create: () -> T): T {
        for ((key, rgb) in SCROLLBAR_COLORS) UIManager.put(key, java.awt.Color(rgb))
        try {
            return create()
        } finally {
            for (key in SCROLLBAR_COLORS.keys) UIManager.put(key, null)
        }
    }
}

/**
 * Monospace font selection.
 *
 * `Font("Consolas", ...)` for a font that is not installed silently yields the
 * proportional "Dialog" fallback, which wrecks a fixed-cell terminal grid. So the
 * choice is made against the installed family names, never assumed.
 */
internal object TerminalFonts {
    /** In order of preference; all ship with, or are common on, Windows 10/11. */
    val PREFERRED = listOf("Cascadia Mono", "Cascadia Code", "Consolas", "Lucida Console", "Courier New")

    /** Pure so it can be tested: [isInstalled] answers "is this family available?". */
    fun choose(isInstalled: (String) -> Boolean): String =
        PREFERRED.firstOrNull(isInstalled) ?: Font.MONOSPACED

    /**
     * Resolved once. Enumerating every installed family is slow on a Windows box with
     * many fonts, so callers warm this off the UI thread before first use.
     */
    fun warmUp(): String = family

    val family: String by lazy {
        val installed = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .availableFontFamilyNames.toHashSet()
        choose { it in installed }
    }
}

/** JediTerm settings: dark theme, our palette and font, bounded scrollback. */
internal class WhiteDevilTerminalSettings(private val fontFamily: String) : DefaultSettingsProvider() {
    override fun getTerminalColorPalette(): ColorPalette = TerminalTheme.palette

    override fun getTerminalFont(): Font = Font(fontFamily, Font.PLAIN, getTerminalFontSize().toInt())

    override fun getTerminalFontSize(): Float = TerminalTheme.FONT_SIZE

    // JediTermWidget seeds its StyleState from getDefaultStyle() (the foreground and
    // background getters derive from it), so this is the override that actually applies.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getDefaultStyle(): TextStyle = TerminalTheme.defaultStyle

    // The default is blue on white, which is a glaring bar in a dark terminal.
    override fun getHyperlinkColor(): TextStyle = TextStyle(
        TerminalColor.rgb(0x3B, 0x8E, 0xEA),
        TerminalTheme.terminalColor(TerminalTheme.BACKGROUND_RGB),
    )

    override fun getBufferMaxLinesCount(): Int = TerminalTheme.SCROLLBACK_LINES

    // JediTerm binds Ctrl+L to "Clear Buffer" and Ctrl+F to "Find" and swallows those key
    // events before they reach the shell. Ctrl+L is readline's clear-screen and vim's
    // redraw; Ctrl+F is readline's forward-char and vim's page-down. Both must reach the
    // program, so the menu entries stay (right-click) but lose their shortcut (Find moves
    // to Ctrl+Shift+F).
    override fun getClearBufferActionPresentation(): TerminalActionPresentation =
        TerminalActionPresentation("Clear Buffer", emptyList())

    override fun getFindActionPresentation(): TerminalActionPresentation =
        TerminalActionPresentation(
            "Find",
            KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
        )
}
