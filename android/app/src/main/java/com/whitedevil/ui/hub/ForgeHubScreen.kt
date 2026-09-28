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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.theme.WdPalette

@Composable
fun ForgeHubScreen(host: MainActivity) {
    val progress = host.hubLoadProgressPublic()
    val loading = progress in 0.01f..0.99f || host.hubScreensUiPublic().isEmpty()
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Forge Hub", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        host.hubConnectionLabelPublic(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (host.hubBannerVisiblePublic()) {
                    TextButton(onClick = { host.onHubBannerClickPublic() }) {
                        Text("Update", style = MaterialTheme.typography.labelMedium, color = WdPalette.accent)
                    }
                    IconButton(onClick = { host.dismissHubBannerPublic() }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Dismiss update notice", tint = WdPalette.textSecondary)
                    }
                }
                IconButton(onClick = { host.reloadCurrentHubScreen() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Reload", tint = WdPalette.textSecondary)
                }
            }
            if (loading) {
                LinearProgressIndicator(
                    progress = { if (progress > 0f) progress else 0.15f },
                    modifier = Modifier.fillMaxWidth(),
                    color = WdPalette.accent,
                    trackColor = WdPalette.surface,
                )
            }
            Text(
                "SCREENS",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
            )
            LazyRow(Modifier.padding(horizontal = 16.dp)) {
                items(host.hubScreensUiPublic(), key = { it.id }) { screen ->
                    val active = screen.id == host.hubCurrentScreenIdPublic()
                    Column(
                        Modifier
                            .clickable { host.showHubScreen(screen.id) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            screen.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) WdPalette.text else WdPalette.textMetadata,
                            fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .height(2.dp)
                                .fillMaxWidth()
                                .background(if (active) WdPalette.accent else Color.Transparent),
                        )
                    }
                }
            }
            HorizontalDivider(color = WdPalette.stroke, modifier = Modifier.padding(top = 4.dp))
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
            .background(WdPalette.bg.copy(alpha = 0.92f))
            .padding(24.dp),
    ) {
        Text("Loading Hub…", style = MaterialTheme.typography.titleMedium)
        Text(
            "Connecting to your relay and preparing dashboards.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
        repeat(4) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .padding(vertical = 6.dp)
                    .background(WdPalette.surface),
            )
        }
    }
}
