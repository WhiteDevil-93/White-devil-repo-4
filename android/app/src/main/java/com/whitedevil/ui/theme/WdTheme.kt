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

/** UX Pilot “Deep Space” — https://uxpilot.ai/a/ui-design?page=KWVlind9DyhFkDfRaXEf */
object WdPalette {
    val bg = Color(0xFF0B0E14)
    val surface = Color(0xFF151921)
    val bgElevated = Color(0xFF151921)
    val surfaceHover = Color(0xFF1F2937)
    val stroke = Color(0xFF1F2937)
    val accent = Color(0xFF7C3AED)
    val accentLight = Color(0xFFC084FC)
    val onAccent = Color(0xFFFFFFFF)
    val onLightButton = Color(0xFF0B0E14)
    val text = Color(0xFFFFFFFF)
    val textSecondary = Color(0x99FFFFFF)
    val textMetadata = Color(0x66FFFFFF)
    val userBubble = Color(0xFF7C3AED)
    val success = Color(0xFF22C55E)

    val accentDim @Composable get() = accent.copy(alpha = 0.65f)
    val errorText @Composable get() = accentLight
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
    error = WdPalette.accentLight,
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
        fontSize = 10.sp,
        lineHeight = 12.sp,
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
        fontSize = 9.sp,
        lineHeight = 11.sp,
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
