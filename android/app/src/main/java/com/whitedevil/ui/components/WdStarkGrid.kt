package com.whitedevil.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.whitedevil.ui.theme.WdPalette

@Composable
fun WdStarkGridScreen(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 40.dp.toPx()
            val line = Color(0x331F2937)
            var x = 0f
            while (x <= size.width) {
                drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += step
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}
