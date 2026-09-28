package com.whitedevil.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 2025-style neutral dark — no brown vignette. */
object WdPalette {
    val bg = Color(0xFF09090A)
    val bgElevated = Color(0xFF111113)
    val surface = Color(0xFF161618)
    val surfaceHover = Color(0xFF1E1E22)
    val stroke = Color(0x14FFFFFF)
    val strokeStrong = Color(0x22FFFFFF)
    val accent = Color(0xFFE8D5B5)
    val accentDim = Color(0xFF6B5D48)
    val onAccent = Color(0xFF141210)
    val text = Color(0xFFF4F4F5)
    val textSecondary = Color(0xFF9CA3AF)
    val textTertiary = Color(0xFF6B7280)
    val danger = Color(0xFFF87171)
    val success = Color(0xFF86EFAC)
    val link = Color(0xFF93C5FD)
}

private val scheme = darkColorScheme(
    primary = WdPalette.accent,
    onPrimary = WdPalette.onAccent,
    background = WdPalette.bg,
    surface = WdPalette.surface,
    surfaceVariant = WdPalette.surfaceHover,
    onBackground = WdPalette.text,
    onSurface = WdPalette.text,
    onSurfaceVariant = WdPalette.textSecondary,
    outline = WdPalette.strokeStrong,
    error = WdPalette.danger,
)

private val wdTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
        color = WdPalette.text,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp,
        color = WdPalette.text,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = WdPalette.text,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = WdPalette.text,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = WdPalette.text,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = WdPalette.textSecondary,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        color = WdPalette.textSecondary,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp,
        color = WdPalette.textTertiary,
    ),
)

@Composable
fun WhiteDevilTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = wdTypography,
        content = content,
    )
}

/** Legacy accessors — map to new palette. */
object WdColors {
    val accent @Composable get() = WdPalette.accent
    val muted @Composable get() = WdPalette.textSecondary
    val strong @Composable get() = WdPalette.text
    val fg @Composable get() = WdPalette.text
    val line @Composable get() = WdPalette.strokeStrong
}
