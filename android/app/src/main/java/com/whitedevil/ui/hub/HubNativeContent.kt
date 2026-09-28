package com.whitedevil.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.ui.theme.WdPalette
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun HubNativeContent(host: MainActivity, screenId: String?) {
    when {
        host.hubBlockedByUpdatePublic() -> HubForceUpdate(host)
        host.hubScreenErrorPublic() != null && host.hubScreensUiPublic().isEmpty() ->
            HubMessage(host.hubScreenErrorPublic()!!, "Retry") { host.loadHubManifestPublic() }
        host.hubScreenLoadingPublic() -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = WdPalette.accent)
            Text("Syncing relay…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
        }
        host.hubScreenErrorPublic() != null ->
            HubMessage(host.hubScreenErrorPublic()!!, "Retry") { host.refreshHubNativeScreenPublic() }
        else -> when (screenId) {
            "home" -> HubHomeBody(host.hubScreenJsonPublic())
            "renders", "gallery" -> HubMediaBody(host.hubScreenJsonPublic())
            "setup" -> HubJsonSections(host.hubScreenJsonPublic(), title = "Setup")
            "thunder" -> HubThunderBody(host.hubScreenJsonPublic())
            "colab" -> HubColabBody(host.hubScreenJsonPublic())
            "hypno" -> HubJsonSections(host.hubScreenJsonPublic(), title = "HypnoForge")
            "ltx" -> HubGenJobsBody(host.hubScreenJsonPublic())
            else -> HubJsonSections(host.hubScreenJsonPublic(), title = screenId ?: "Hub")
        }
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

@Composable
private fun HubHomeBody(json: String) {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return HubJsonSections(json, "Home")
    val status = root.optJSONObject("status")
    val colab = root.optJSONObject("colab")
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Welcome back", style = MaterialTheme.typography.titleMedium)
            Text("Render farm status from relay APIs.", style = MaterialTheme.typography.bodySmall)
        }
        if (status != null) {
            item { StatRow("Clips on relay", status.optInt("clips", 0).toString()) }
            item { StatRow("Newest clip", status.optString("newest", "—")) }
        }
        if (colab != null) {
            item { StatRow("Colab jobs", (colab.optJSONArray("jobs")?.length() ?: 0).toString()) }
            colab.optJSONObject("usage")?.let { u ->
                item { StatRow("Colab balance", u.optDouble("balance").toString()) }
            }
        }
        val groups = root.optJSONArray("library") ?: JSONArray()
        item { Text("Library groups", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)) }
        items((0 until groups.length()).map { groups.getJSONObject(it) }) { g ->
            Text(
                "${g.optString("title")} · ${g.optInt("count")} clips",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WdPalette.surface, RoundedCornerShape(2.dp))
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun HubMediaBody(json: String) {
    val arr = runCatching { JSONArray(json) }.getOrNull()
    if (arr == null) return HubJsonSections(json, "Media")
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items((0 until arr.length()).map { arr.getJSONObject(it) }) { g ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(WdPalette.surface, RoundedCornerShape(2.dp))
                    .padding(12.dp),
            ) {
                Text(g.optString("title"), fontWeight = FontWeight.SemiBold)
                Text("${g.optInt("count")} clips · ${g.optString("kind")}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun HubThunderBody(json: String) {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return HubJsonSections(json, "Thunder")
    val inst = root.optJSONArray("instances") ?: JSONArray()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Thunder Compute", style = MaterialTheme.typography.titleMedium) }
        if (inst.length() == 0) {
            item { Text("No instances", style = MaterialTheme.typography.bodySmall) }
        }
        items((0 until inst.length()).map { inst.getJSONObject(it) }) { i ->
            Text(
                "${i.optString("id")} · ${i.optString("status")} · ${i.optString("gpu_type", "")}",
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WdPalette.surface, RoundedCornerShape(2.dp))
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun HubColabBody(json: String) {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return HubJsonSections(json, "Colab")
    val jobs = root.optJSONArray("jobs") ?: JSONArray()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Colab runtime", style = MaterialTheme.typography.titleMedium) }
        items((0 until jobs.length()).map { jobs.getJSONObject(it) }) { j ->
            Text(
                "${j.optString("name", j.optString("id"))} · ${j.optString("status")} · ${j.optInt("progress")}%",
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WdPalette.surface, RoundedCornerShape(2.dp))
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun HubGenJobsBody(json: String) {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return HubJsonSections(json, "LTX / Gen")
    val jobs = when {
        root.has("jobs") -> root.optJSONArray("jobs")
        root is JSONArray -> root as JSONArray
        else -> JSONArray()
    } ?: JSONArray()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Generation jobs", style = MaterialTheme.typography.titleMedium) }
        items((0 until jobs.length()).map { jobs.getJSONObject(it) }) { j ->
            Text(
                j.optString("id", j.optString("name", "job")),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WdPalette.surface, RoundedCornerShape(2.dp))
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun HubJsonSections(json: String, title: String) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            Text(
                json.take(8000),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = WdPalette.textSecondary,
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
