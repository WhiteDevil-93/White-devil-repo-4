package com.whitedevil.desktop

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Forge Hub design tokens, copied from hub/static/ui/tokens.css (the single source of truth for
 * the web screens) so the laptop app and the phone/web screens are one product. If a colour here
 * disagrees with tokens.css, tokens.css wins.
 */
object Forge {
    val Bg = Color(0xFF0A0B0F)
    val Side = Color(0xFF0F1117)
    val Panel = Color(0xFF141722)
    val Panel2 = Color(0xFF1B1F2C)
    val Well = Color(0xFF0C0E14)
    val Line = Color(0xFF232838)
    val Control = Color(0xFF646D8C)

    val Fg = Color(0xFFECEEF5)
    val Mut = Color(0xFFA9AFC2)
    val Dim = Color(0xFF9097AD)

    val Acc = Color(0xFF8F82FF)
    val Acc2 = Color(0xFF5B4BE8)
    val Acc3 = Color(0xFFB3A9FF)
    val AccSoft = Color(0xFF5B4BE8).copy(alpha = 0.14f)

    val Ok = Color(0xFF3FBF8A)
    val Warn = Color(0xFFE5A23A)
    val Bad = Color(0xFFF0645A)
    val Info = Color(0xFF58A6FF)
}

val WhiteDevilColors = darkColorScheme(
    primary = Forge.Acc2,
    onPrimary = Color.White,
    primaryContainer = Forge.AccSoft,
    onPrimaryContainer = Forge.Fg,
    secondary = Forge.Acc,
    onSecondary = Color.White,
    background = Forge.Bg,
    onBackground = Forge.Fg,
    surface = Forge.Panel,
    onSurface = Forge.Fg,
    surfaceVariant = Forge.Panel2,
    onSurfaceVariant = Forge.Mut,
    outline = Forge.Control,
    outlineVariant = Forge.Line,
    error = Forge.Bad,
)
