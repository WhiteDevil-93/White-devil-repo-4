package com.whitedevil.ui.hub

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
import androidx.compose.runtime.*
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.ui.components.WdHairline
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.theme.WdDimens
import com.whitedevil.ui.theme.WdPalette

private const val HUB_HERO =
    "https://storage.googleapis.com/uxpilot-auth.appspot.com/gen_a293a29b9d_c7bea915ab298bde.png"

@Composable
fun ForgeHubScreen(host: MainActivity) {
    var destinationsOpen by remember { mutableStateOf(false) }
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(120.dp)) {
                AsyncImage(
                    model = HUB_HERO,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, WdPalette.bg))),
                )
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("WHITEDEVIL SYSTEM", style = MaterialTheme.typography.labelLarge, color = WdPalette.accentLight)
                        Text("Forge Hub", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(host.hubConnectionLabelPublic(), style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = { host.reloadCurrentHubScreen() }, modifier = Modifier.size(WdDimens.iconTap)) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Reload", tint = WdPalette.textSecondary)
                    }
                }
            }
            if (host.hubBannerVisiblePublic()) {
                Row(
                    Modifier.padding(horizontal = WdDimens.screenHorizontal),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        host.hubBannerTextPublic().ifBlank { "Update available" },
                        style = MaterialTheme.typography.labelMedium,
                        color = WdPalette.textSecondary,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { host.onHubBannerClickPublic() },
                    )
                    IconButton(onClick = { host.dismissHubBannerPublic() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = WdPalette.textMetadata)
                    }
                }
            }
            if (host.hubScreenLoadingPublic()) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = WdPalette.accent,
                    trackColor = Color.Transparent,
                )
            }
            Box {
                TextButton(onClick = { destinationsOpen = true }) { Text("All destinations ▾ · swipe tabs below") }
                DropdownMenu(expanded = destinationsOpen, onDismissRequest = { destinationsOpen = false }) {
                    val groups = host.hubScreensUiPublic().groupBy { screen ->
                        when (screen.id) {
                            "shotwriter", "ltx" -> "Create"
                            "renders", "gallery", "colab", "thunder", "vast" -> "Monitor"
                            else -> "Operate"
                        }
                    }
                    groups.forEach { (group, screens) ->
                        Text(group, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
                        screens.forEach { screen -> DropdownMenuItem(text = { Text(screen.title) }, onClick = {
                            destinationsOpen = false
                            host.showHubScreenPublic(screen.id)
                        }) }
                    }
                }
            }
            if (host.hubScreenError != null && host.hubScreenJson.isNotBlank()) {
                Text("Refresh failed — showing previous data: ${host.hubScreenError}", color = WdPalette.errorText, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (host.hubLastUpdated > 0L) {
                Text("Last updated ${java.text.DateFormat.getTimeInstance().format(java.util.Date(host.hubLastUpdated))}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (host.hubPendingActions.isNotEmpty()) Text("Submitting request…", modifier = Modifier.padding(horizontal = 16.dp))
            LazyRow(Modifier.padding(horizontal = WdDimens.screenHorizontal, vertical = 6.dp)) {
                items(host.hubScreensUiPublic(), key = { it.id }) { screen ->
                    val active = screen.id == host.hubCurrentScreenIdPublic()
                    Column(
                        Modifier
                            .clickable { host.showHubScreenPublic(screen.id) }
                            .semantics { selected = active }
                            .padding(horizontal = 8.dp, vertical = 14.dp),
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
                HubNativeContent(host, host.hubCurrentScreenIdPublic())
            }
        }
    }
}
