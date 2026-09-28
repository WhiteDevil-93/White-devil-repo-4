package com.whitedevil.ui.hub

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.ui.components.WdHairline
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.components.WdTopBar
import com.whitedevil.ui.theme.WdPalette

@Composable
fun ForgeHubScreen(host: MainActivity) {
    val progress = host.hubLoadProgressPublic()
    val loading = progress in 0.01f..0.99f || host.hubScreensUiPublic().isEmpty()
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            WdTopBar(
                title = "Forge Hub",
                subtitle = host.hubConnectionLabelPublic(),
                trailing = {
                    IconButton(onClick = { host.reloadCurrentHubScreen() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Reload", tint = WdPalette.textSecondary)
                    }
                },
            )
            if (host.hubBannerVisiblePublic()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Update available",
                        style = MaterialTheme.typography.labelMedium,
                        color = WdPalette.textSecondary,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { host.onHubBannerClickPublic() },
                    )
                    IconButton(onClick = { host.dismissHubBannerPublic() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = WdPalette.textMetadata)
                    }
                }
            }
            if (loading) {
                LinearProgressIndicator(
                    progress = { if (progress > 0f) progress else 0.12f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = WdPalette.accent,
                    trackColor = Color.Transparent,
                )
            }
            LazyRow(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                items(host.hubScreensUiPublic(), key = { it.id }) { screen ->
                    val active = screen.id == host.hubCurrentScreenIdPublic()
                    Column(
                        Modifier
                            .clickable { host.showHubScreen(screen.id) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            screen.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) WdPalette.text else WdPalette.textMetadata,
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .height(2.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(1.dp))
                                .background(if (active) WdPalette.accent else Color.Transparent),
                        )
                    }
                }
            }
            WdHairline()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> host.obtainHubHostFrame(ctx) },
                    update = { frame ->
                        frame.layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    },
                )
                if (loading) {
                    HubLoadingSkeleton(Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun HubLoadingSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(WdPalette.bg.copy(alpha = 0.88f))
            .padding(20.dp),
    ) {
        Text("Loading…", style = MaterialTheme.typography.titleMedium)
        Text(
            "Preparing your hub",
            style = MaterialTheme.typography.labelMedium,
            color = WdPalette.textMetadata,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(20.dp))
        repeat(3) { i ->
            Box(
                Modifier
                    .fillMaxWidth(if (i == 1) 0.72f else 0.9f)
                    .height(10.dp)
                    .padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(WdPalette.surface),
            )
        }
    }
}
