package com.whitedevil.desktop

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Palette lifted from the Forge Hub design (hub/static/venice, UX Pilot
 * "ForgeHub - Venice Agent") so the desktop app and the web screens read as one
 * product rather than two.
 */
val WhiteDevilColors = darkColorScheme(
    primary = Color(0xFF9B2C2C),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFFDF2D6),
    onSecondary = Color(0xFF805500),
    background = Color(0xFF141110),
    onBackground = Color(0xFFF0E9E0),
    surface = Color(0xFF1B1714),
    onSurface = Color(0xFFF0E9E0),
    surfaceVariant = Color(0xFF211C18),
    onSurfaceVariant = Color(0xFFA89E93),
    error = Color(0xFFD99393),
)
