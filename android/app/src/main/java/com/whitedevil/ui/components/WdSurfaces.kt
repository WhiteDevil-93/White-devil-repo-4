package com.whitedevil.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val WdScreenGradient = Brush.verticalGradient(
    listOf(Color(0xFF141210), Color(0xFF0B0B0C), Color(0xFF080809)),
)

@Composable
fun WdScreenBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.background(WdScreenGradient)) { content() }
}

@Composable
fun WdGlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Color(0x331A1A1E))
            .border(1.dp, Color(0x18FFFFFF), RoundedCornerShape(corner)),
    ) { content() }
}

/** Single bottom dock: one surface for banners, attachments, and the input row. */
@Composable
fun WdComposerDock(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 22.dp, bottomEnd = 22.dp))
            .background(Color(0xF0121216))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 22.dp, bottomEnd = 22.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        content()
    }
}

@Composable
fun WdGlassBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xD9121216))
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(22.dp)),
    ) { content() }
}
