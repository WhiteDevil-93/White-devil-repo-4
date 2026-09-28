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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.R
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.theme.WdColors

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
                    color = Color(0xFF111111),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WdColors.strong)
                        .clickable { host.onHubBannerClickPublic() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Forge Hub", color = WdColors.strong, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Relay tools & dashboards", color = WdColors.muted, fontSize = 12.sp)
                }
                Text(
                    host.hubConnectionLabelPublic(),
                    color = if (host.hubConnectionLabelPublic() == "Online") WdColors.accent else WdColors.muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x331A1A1E))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                IconButton(onClick = { host.reloadCurrentHubScreen() }) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Reload current screen", tint = WdColors.accent)
                }
            }
            val progress = host.hubLoadProgressPublic()
            if (progress in 0.01f..0.99f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = WdColors.accent,
                    trackColor = Color(0x331A1A1E),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                host.hubScreensUiPublic().forEach { screen ->
                    val active = screen.id == host.hubCurrentScreenIdPublic()
                    Text(
                        screen.title,
                        color = if (active) WdColors.strong else WdColors.muted,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (active) Color(0x26FFFFFF) else Color(0x331A1A1E))
                            .clickable { host.showHubScreen(screen.id) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
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
