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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.whitedevil.ui.theme.WdPalette

private val topGlow = Brush.verticalGradient(
    0f to Color(0xFF121214),
    0.35f to WdPalette.bg,
    1f to WdPalette.bg,
)

@Composable
fun WdScreenBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(topGlow)) { content() }
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

/** Chat / input dock — one elevated surface, no double borders. */
@Composable
fun WdInputDock(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = WdPalette.bgElevated,
        shadowElevation = 8.dp,
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

/** @deprecated use WdSurfaceCard */
@Composable
fun WdGlassCard(modifier: Modifier = Modifier, corner: Dp = 16.dp, content: @Composable () -> Unit) =
    WdSurfaceCard(modifier, corner, content)

/** @deprecated use WdInputDock */
@Composable
fun WdComposerDock(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    WdInputDock(modifier, content)

/** @deprecated */
@Composable
fun WdGlassBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    WdInlineField(modifier, content)
