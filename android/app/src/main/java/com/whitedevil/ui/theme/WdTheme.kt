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

/** Forge Hub tokens, shared with hub/static/ui/tokens.css and desktop/Theme.kt. */
object WdPalette {
    val bg = Color(0xFF0A0B0F)
    val surface = Color(0xFF141722)
    val bgElevated = Color(0xFF141722)
    val surfaceHover = Color(0xFF1B1F2C)
    val stroke = Color(0xFF646D8C)
    val accent = Color(0xFF8F82FF)
    val accentLight = Color(0xFFB3A9FF)
    val onAccent = Color(0xFF0A0B0F)
    val onLightButton = Color(0xFF0B0E14)
    val text = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFA9AFC2)
    val textMetadata = Color(0xFF9097AD)
    val userBubble = accent
    val success = Color(0xFF3FBF8A)

    val accentDim @Composable get() = accent.copy(alpha = 0.65f)
    val errorText @Composable get() = Color(0xFFF0645A)
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
    error = Color(0xFFF0645A),
)

private val wdTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        color = WdPalette.text,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        color = WdPalette.text,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = WdPalette.text,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = WdPalette.text,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        color = WdPalette.text,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = WdPalette.textSecondary,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.2.sp,
        color = WdPalette.textMetadata,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = WdPalette.textSecondary,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
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
