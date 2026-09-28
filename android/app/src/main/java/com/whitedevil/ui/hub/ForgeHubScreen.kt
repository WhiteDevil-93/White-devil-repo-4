package com.whitedevil.ui.hub

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.R
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.theme.WdPalette

@Composable
fun ForgeHubScreen(host: MainActivity) {
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            if (host.hubBannerVisiblePublic()) {
                Text(
                    host.hubBannerTextPublic(),
                    style = MaterialTheme.typography.labelMedium,
                    color = WdPalette.onAccent,
                    fontWeight = FontWeight.SemiBold,
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
                Column(Modifier.weight(1f)) {
                    Text("Hub", style = MaterialTheme.typography.titleLarge)
                    Text("Relay dashboards", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    host.hubConnectionLabelPublic(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (host.hubConnectionLabelPublic() == "Online") WdPalette.success else WdPalette.textTertiary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(WdPalette.surface)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                IconButton(onClick = { host.reloadCurrentHubScreen() }) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Reload", tint = WdPalette.textSecondary)
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                host.hubScreensUiPublic().forEach { screen ->
                    val active = screen.id == host.hubCurrentScreenIdPublic()
                    Text(
                        screen.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) WdPalette.text else WdPalette.textTertiary,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) WdPalette.surfaceHover else Color.Transparent)
                            .clickable { host.showHubScreen(screen.id) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
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
