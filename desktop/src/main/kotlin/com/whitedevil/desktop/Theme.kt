package com.whitedevil.desktop

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Palette lifted from the Forge Hub design (hub/static/venice, UX Pilot
 * "ForgeHub - Venice Agent") so the desktop app and the web screens read as one
 * product rather than two.
 */
val WhiteDevilColors = darkColorScheme(
    primary = Color(0xFF8F82FF),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFB3A9FF),
    onSecondary = Color(0xFF0A0B0F),
    background = Color(0xFF0A0B0F),
    onBackground = Color(0xFFECEEF5),
    surface = Color(0xFF141722),
    onSurface = Color(0xFFECEEF5),
    surfaceVariant = Color(0xFF1B1F2C),
    onSurfaceVariant = Color(0xFFA9AFC2),
    error = Color(0xFFF0645A),
)
