package com.whitedevil.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.whitedevil.ui.theme.WdPalette

@Composable
fun WdScreenBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    WdStarkGridScreen(modifier.background(WdPalette.bg)) { content() }
}

@Composable
fun WdSurfaceCard(
    modifier: Modifier = Modifier,
    corner: Dp = 20.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(corner),
        color = WdPalette.surface,
        content = content,
    )
}

@Composable
fun WdInputDock(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = WdPalette.bgElevated,
        shadowElevation = 0.dp,
        content = {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                content()
            }
        },
    )
}

@Composable
fun WdInlineField(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(WdPalette.surface)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        content()
    }
}

@Composable
fun WdGlassCard(modifier: Modifier = Modifier, corner: Dp = 16.dp, content: @Composable () -> Unit) =
    WdSurfaceCard(modifier, corner, content)

@Composable
fun WdComposerDock(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    WdInputDock(modifier, content)

@Composable
fun WdGlassBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    WdInlineField(modifier, content)
