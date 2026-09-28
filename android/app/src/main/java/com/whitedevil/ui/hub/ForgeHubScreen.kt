package com.whitedevil.ui.hub

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (host.hubBannerVisiblePublic()) {
                Text(
                    host.hubBannerTextPublic(),
                    style = MaterialTheme.typography.labelMedium,
                    color = WdPalette.onAccent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WdPalette.accent)
                        .clickable { host.onHubBannerClickPublic() }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Hub", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                Text(
                    host.hubConnectionLabelPublic(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (host.hubConnectionLabelPublic() == "Online") WdPalette.accent else WdPalette.textTertiary,
                )
                IconButton(onClick = { host.reloadCurrentHubScreen() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Reload", tint = WdPalette.textSecondary)
                }
            }
            val progress = host.hubLoadProgressPublic()
            if (progress in 0.01f..0.99f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = WdPalette.accent,
                    trackColor = Color.Transparent,
                )
            }
            LazyRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                items(host.hubScreensUiPublic(), key = { it.id }) { screen ->
                    val active = screen.id == host.hubCurrentScreenIdPublic()
                    Text(
                        screen.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (active) WdPalette.text else WdPalette.textTertiary,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clickable { host.showHubScreen(screen.id) },
                    )
                }
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx -> host.obtainHubHostFrame(ctx) },
                update = { frame ->
                    frame.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                },
            )
        }
    }
}
