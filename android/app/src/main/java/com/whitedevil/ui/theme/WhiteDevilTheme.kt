package com.whitedevil.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.whitedevil.MainActivity

private val WhiteDevilColors = darkColorScheme(
    primary = Color(0xFFCDB88F),
    onPrimary = Color(0xFF111111),
    background = Color(0xFF080809),
    surface = Color(0xFF1A1A1E),
    onBackground = Color(0xFFEDEDEA),
    onSurface = Color(0xFFEDEDEA),
    secondary = Color(0xFF82B6E8),
    error = Color(0xFFE85D5D),
)

@Composable
fun WhiteDevilTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WhiteDevilColors,
        content = content,
    )
}

object WdColors {
    val accent @Composable get() = Color(MainActivity.ACCENT)
    val muted @Composable get() = Color(MainActivity.MUTED)
    val strong @Composable get() = Color(MainActivity.STRONG)
    val fg @Composable get() = Color(MainActivity.FG)
    val line @Composable get() = Color(MainActivity.LINE)
}
