package com.whitedevil.ui.hub

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.ui.theme.WdPalette

@Composable
fun HubNativeContent(host: MainActivity, screenId: String?) {
    val json = host.hubScreenJsonPublic()
    when {
        host.hubBlockedByUpdatePublic() -> HubForceUpdate(host)
        host.hubScreenErrorPublic() != null && host.hubScreensUiPublic().isEmpty() ->
            HubMessage(host.hubScreenErrorPublic()!!, "Retry") { host.loadHubManifestPublic() }
        host.hubScreenLoadingPublic() && json.isBlank() -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = WdPalette.accent)
            Text("Syncing relay…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
        }
        host.hubScreenErrorPublic() != null && json.isBlank() ->
            HubMessage(host.hubScreenErrorPublic()!!, "Retry") { host.refreshHubNativeScreenPublic() }
        else -> when (screenId) {
            "home" -> HubHomeBody(json, host)
            "renders" -> HubRendersBody(json, host)
            "gallery" -> HubGalleryBody(json, host)
            // Full GPU kit planner (Where / recipe / options / runs) — native LoRA-only body lost those.
            "setup" -> HubRelayWebBody(host, "/app/setup/")
            "thunder" -> HubThunderBody(json, host)
            "colab" -> HubColabBody(json, host)
            "hypno" -> HubHypnoBody(json, host)
            "ltx" -> HubRelayWebBody(host, "/app/ltx/")
            "loratrain" -> HubLoraTrainBody(host)
            "vast" -> HubVastBody(json, host)
            "files" -> HubFilesBody(json, host)
            "shotwriter" -> HubShotwriterBody(json, host)
            else -> {
                // Any other screen the hub lists (e.g. the Caretaker domain) is a page on the relay: open it,
                // so a new domain works without an app update. Only the JSON dump is left for screens with no path.
                val path = host.hubScreenUrlPublic(screenId)
                when {
                    screenId?.startsWith("bot-") == true -> HubBotBody(json)
                    path != null && path.startsWith("/") -> HubRelayWebBody(host, path)
                    else -> HubFallbackBody(json, screenId ?: "Hub", host)
                }
            }
        }
    }
}

@Composable
private fun HubFallbackBody(json: String, title: String, host: MainActivity) {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item { HubSectionTitle(title, "Native hub screen") }
        item {
            HubCard {
                Text(
                    json.take(6000),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = WdPalette.textSecondary,
                )
            }
        }
        item { HubPrimaryButton("Reload") { host.refreshHubNativeScreenPublic() } }
    }
}

@Composable
private fun HubMessage(text: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = WdPalette.textSecondary)
        Text(
            action,
            style = MaterialTheme.typography.labelMedium,
            color = WdPalette.accentLight,
            modifier = Modifier
                .padding(top = 16.dp)
                .clickable(onClick = onAction),
        )
    }
}

@Composable
private fun HubForceUpdate(host: MainActivity) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Update required", style = MaterialTheme.typography.titleMedium)
        Text(
            "Install v${host.hubForceUpdateVersionPublic()} to use Forge Hub (you have v${host.hubAppVersionPublic()}).",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Text(
            "Download update",
            color = WdPalette.accentLight,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .padding(top = 8.dp)
                .clickable { host.downloadHubUpdatePublic() },
        )
    }
}
