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

object WdPalette {
    val bg = Color(0xFF000000)
    val surface = Color(0xFF1C1C1E)
    val bgElevated = Color(0xFF0A0A0A)
    val surfaceHover = Color(0xFF2C2C2E)
    val stroke = Color(0xFF3A3A3C)
    val accent = Color(0xFFD9BF8C)
    val onAccent = Color(0xFF000000)
    val text = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFAEAEB2)
    val textMetadata = Color(0xFF8E8E93)
    val userBubble = Color(0xFF2C2C2E)

    val accentDim @Composable get() = accent.copy(alpha = 0.65f)
    val errorText @Composable get() = accent
}

private val scheme = darkColorScheme(
    primary = WdPalette.accent,
    onPrimary = WdPalette.onAccent,
    background = WdPalette.bg,
    surface = WdPalette.surface,
    onBackground = WdPalette.text,
    onSurface = WdPalette.text,
    onSurfaceVariant = WdPalette.textSecondary,
    outline = WdPalette.stroke,
    error = WdPalette.accent,
)

private val wdTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
        color = WdPalette.text,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        color = WdPalette.text,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        color = WdPalette.text,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        color = WdPalette.text,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        color = WdPalette.text,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        color = WdPalette.textSecondary,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.6.sp,
        color = WdPalette.textMetadata,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = WdPalette.textSecondary,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = WdPalette.textMetadata,
    ),
)

@Composable
fun WhiteDevilTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = wdTypography, content = content)
}

object WdColors {
    val accent @Composable get() = WdPalette.accent
    val muted @Composable get() = WdPalette.textSecondary
    val strong @Composable get() = WdPalette.text
    val fg @Composable get() = WdPalette.text
    val line @Composable get() = WdPalette.stroke
}
