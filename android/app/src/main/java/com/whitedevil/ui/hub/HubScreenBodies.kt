package com.whitedevil.ui.hub

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun HubHomeBody(json: String, host: MainActivity) {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return
    val status = root.optJSONObject("status")
    val colab = root.optJSONObject("colab")
    val library = root.optJSONArray("library") ?: JSONArray()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { HubSectionTitle("Welcome back", "Relay farm at a glance") }
        if (status != null) {
            item {
                HubCard {
                    HubStatRow("Clips on relay", status.optInt("clips", 0).toString())
                    HubStatRow("Newest clip", status.optString("newest", "—"))
                    status.optInt("newest_age_min", -1).takeIf { it >= 0 }?.let {
                        HubStatRow("Newest age", "${it}m")
                    }
                    status.optString("last_beat").takeIf { it.isNotBlank() }?.let {
                        HubStatRow("Last heartbeat", it)
                    }
                    val laptop = status.optJSONObject("laptop")
                    if (laptop != null && laptop.length() > 0) {
                        HubStatRow("Laptop", laptop.optString("status", laptop.toString().take(80)))
                    }
                }
            }
            val alerts = status.optJSONArray("alerts")
            if (alerts != null && alerts.length() > 0) {
                item { HubSectionTitle("Alerts") }
                items((0 until alerts.length()).map { alerts.optString(it) }) { line ->
                    HubCard { Text(line, style = MaterialTheme.typography.bodySmall, color = WdPalette.accentLight) }
                }
            }
        }
        if (colab != null) {
            item {
                HubCard {
                    HubSectionTitle("Colab runner", null)
                    HubStatRow("Online", if (colab.optBoolean("runner_online")) "yes" else "no")
                    colab.optJSONObject("gpu")?.let { g ->
                        HubStatRow("GPU", g.optString("name", g.toString()))
                    }
                    HubStatRow("Active jobs", (colab.optJSONArray("jobs")?.length() ?: 0).toString())
                }
            }
        }
        item { HubSectionTitle("Library", "${library.length()} collections") }
        items((0 until library.length()).map { library.getJSONObject(it) }) { g ->
            HubCard {
                Text(g.optString("title"), fontWeight = FontWeight.SemiBold)
                Text(
                    "${g.optInt("count")} clips · ${g.optString("kind")} · ${g.optString("source", "colab")}",
                    style = MaterialTheme.typography.labelMedium,
                    color = WdPalette.textMetadata,
                )
            }
        }
        item {
            HubPrimaryButton("Open terminal") {
                host.selectTabPublic(MainActivity.Tab.YOU)
                host.showYouSub(MainActivity.YouSub.TERMINAL)
            }
        }
    }
}

@Composable
fun HubRendersBody(json: String, host: MainActivity) {
    val arr = parseLibrary(json) ?: return
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("all") }
    var source by remember { mutableStateOf("all") }
    val relay = host.relayBasePublic()
    val auth = host.relayAuthPublic()
    val filtered = remember(arr, query, kind, source) {
        arr.filter { g ->
            (kind == "all" || g.optString("kind") == kind) &&
                (source == "all" || g.optString("source", "colab") == source) &&
                (query.isBlank() || g.optString("title").contains(query, ignoreCase = true))
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            HubSectionTitle("Renders", "${arr.sumOf { it.optInt("count") }} clips · ${arr.size} collections")
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = WdPalette.text),
                decorationBox = {
                    if (query.isEmpty()) Text("Search packs and chains", color = WdPalette.textMetadata)
                    it()
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "All", "colab" to "Colab", "thunder" to "Thunder").forEach { (id, label) ->
                    HubPill(label, ok = source == id, onClick = { source = id })
                }
            }
        }
        items(filtered) { g ->
            val clips = g.optJSONArray("clips") ?: JSONArray()
            HubCard {
                Text(g.optString("title"), fontWeight = FontWeight.SemiBold)
                Text(
                    "${g.optInt("count")} clips · ${formatAgo(g.optDouble("updated"))}",
                    style = MaterialTheme.typography.labelMedium,
                    color = WdPalette.textMetadata,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (0 until minOf(clips.length(), 16)).map { clips.getJSONObject(it) }.forEach { c ->
                        HubRelayThumb(
                            relay,
                            auth,
                            c.optString("name"),
                            c.optInt("idx").takeIf { it > 0 }?.let { "Clip $it" },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HubGalleryBody(json: String, host: MainActivity) {
    val arr = parseLibrary(json) ?: return
    val relay = host.relayBasePublic()
    val auth = host.relayAuthPublic()
    val latest = remember(arr) {
        arr.flatMap { g ->
            val clips = g.optJSONArray("clips") ?: JSONArray()
            (0 until clips.length()).map { i ->
                clips.getJSONObject(i) to g.optString("title")
            }
        }.sortedByDescending { it.first.optDouble("mtime") }.take(48)
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { HubSectionTitle("Gallery", "${latest.size} recent clips") }
        items(latest.chunked(3)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (clip, group) ->
                    HubRelayThumb(
                        relay,
                        auth,
                        clip.optString("name"),
                        group + clip.optInt("idx").takeIf { it > 0 }?.let { " · $it" }.orEmpty(),
                    )
                }
            }
        }
    }
}

@Composable
fun HubSetupBody(json: String, host: MainActivity) {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return
    val loras = root.optJSONArray("loras") ?: JSONArray()
    var enabled by remember(json) {
        mutableStateOf((0 until loras.length()).map { i -> loras.getJSONObject(i) }.associate { it.getString("id") to it.optBoolean("enabled") })
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HubSectionTitle("LTX 2.5 Setup", "All ${root.optInt("count", loras.length())} Lightricks LoRAs")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                HubPill(
                    if (root.optBoolean("saved")) "${root.optInt("enabled")} / ${root.optInt("count")} on LTX" else "Not saved",
                    ok = root.optBoolean("saved") && root.optInt("enabled") == root.optInt("count"),
                )
            }
        }
        item {
            HubPrimaryButton("Save selection to relay") {
                val ids = enabled.filterValues { it }.keys.toList()
                val body = JSONObject().put("enabled", JSONArray(ids)).toString()
                host.hubRelayPostPublic("/api/setup", body)
            }
        }
        items((0 until loras.length()).map { loras.getJSONObject(it) }) { r ->
            val id = r.getString("id")
            HubCard {
                Row {
                    Checkbox(
                        checked = enabled[id] == true,
                        onCheckedChange = { enabled = enabled + (id to (it == true)) },
                    )
                    Column {
                        Text(r.optString("name"), fontWeight = FontWeight.SemiBold)
                        Text(
                            "${r.optString("kind")} · ${r.optString("filename")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = WdPalette.textMetadata,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HubThunderBody(json: String) {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return
    val inst = root.optJSONArray("instances") ?: JSONArray()
    val snaps = root.optJSONArray("snapshots") ?: JSONArray()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { HubSectionTitle("Thunder Compute", "${inst.length()} instances") }
        if (inst.length() == 0) {
            item { HubCard { Text("No instances", color = WdPalette.textSecondary) } }
        }
        items((0 until inst.length()).map { inst.getJSONObject(it) }) { i ->
            HubCard {
                Text("Instance ${i.optString("id")}", fontWeight = FontWeight.SemiBold)
                HubStatRow("Status", i.optString("status", i.optString("state", "—")))
                HubStatRow("GPU", i.optString("gpu_type", i.optString("gpu", "—")))
                HubStatRow("IP", i.optString("ip", "—"))
            }
        }
        item { HubSectionTitle("Snapshots", "${snaps.length()} saved") }
        items((0 until snaps.length()).map { snaps.getJSONObject(it) }) { s ->
            HubCard {
                Text(s.optString("name", s.optString("id", "snapshot")), fontWeight = FontWeight.SemiBold)
                Text(s.optString("created", ""), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun HubColabBody(json: String, host: MainActivity) {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return
    val jobs = root.optJSONArray("jobs") ?: JSONArray()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HubCard {
                HubSectionTitle("Colab runtime", null)
                HubStatRow("Runner", if (root.optBoolean("runner_online")) "online" else "offline")
                root.optJSONObject("usage")?.let { u ->
                    HubStatRow("Balance", u.optDouble("balance").toString())
                }
                root.optJSONObject("gpu")?.let { g ->
                    HubStatRow("GPU", g.optString("name", "—"))
                }
            }
        }
        if (!root.optBoolean("runner_online")) {
            item {
                HubPrimaryButton("Start runtime (recover queue)") {
                    host.hubRelayPostPublic("/api/colab/recover", "{}")
                }
            }
        }
        item { HubSectionTitle("Jobs", "${jobs.length()} tracked") }
        items((0 until jobs.length()).map { jobs.getJSONObject(it) }) { j ->
            HubCard {
                Text(j.optString("name", j.optString("id")), fontWeight = FontWeight.SemiBold)
                HubProgressRow(j.optString("status"), j.optInt("progress", -1).takeIf { it >= 0 })
                j.optString("error").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = WdPalette.accentLight)
                }
            }
        }
    }
}

@Composable
fun HubHypnoBody(json: String, host: MainActivity) {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return
    val overview = root.optJSONObject("overview") ?: root
    val jobs = root.optJSONArray("jobs") ?: JSONArray()
    var tab by remember { mutableStateOf("make") }
    var project by remember { mutableStateOf("") }
    var chatMsg by remember { mutableStateOf("") }
    var chatLog by remember { mutableStateOf(listOf<String>()) }
    var capTheme by remember { mutableStateOf("") }
    var capStyle by remember { mutableStateOf("filthy_short") }
    var ingestUrls by remember { mutableStateOf("") }
    val projects = overview.optJSONArray("projects") ?: JSONArray()
    if (project.isEmpty() && projects.length() > 0) {
        project = projects.getJSONObject(0).optString("path", projects.getJSONObject(0).optString("name"))
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("make", "renders", "write", "ingest", "jobs", "chat").forEach { t ->
                    HubPill(
                        t.replaceFirstChar { it.uppercase() },
                        ok = tab == t,
                        onClick = { tab = t },
                    )
                }
            }
        }
        when (tab) {
            "make" -> {
                item {
                    HubCard {
                        HubSectionTitle("Render project", "Runs hf on your laptop")
                        if (projects.length() == 0) {
                            Text("No projects found on laptop", color = WdPalette.textSecondary)
                        } else {
                            (0 until minOf(projects.length(), 12)).map { projects.getJSONObject(it) }.forEach { p ->
                                val path = p.optString("path", p.optString("name"))
                                Row {
                                    Checkbox(checked = project == path, onCheckedChange = { if (it) project = path })
                                    Text(path, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        HubPrimaryButton("Render selected", enabled = project.isNotBlank()) {
                            val body = JSONObject().put("project", project).toString()
                            host.hubRelayPostPublic("/api/laptop/hypno/render", body)
                        }
                    }
                }
            }
            "renders" -> {
                val renders = overview.optJSONArray("renders") ?: JSONArray()
                items((0 until renders.length()).map { renders.getJSONObject(it) }) { r ->
                    HubCard {
                        Text(r.optString("name", r.optString("path", "render")), fontWeight = FontWeight.SemiBold)
                        Text(formatAgo(r.optDouble("mtime")), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            "write" -> {
                val styles = overview.optJSONArray("styles") ?: JSONArray()
                item {
                    HubCard {
                        HubSectionTitle("Caption pack")
                        BasicTextField(
                            value = capTheme,
                            onValueChange = { capTheme = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = WdPalette.text),
                            decorationBox = {
                                if (capTheme.isEmpty()) Text("Theme / focus", color = WdPalette.textMetadata)
                                it()
                            },
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp)) {
                            (0 until minOf(styles.length(), 8)).map { styles.optString(it) }.forEach { s ->
                                HubPill(s.take(16), ok = capStyle == s, onClick = { capStyle = s })
                            }
                        }
                        HubPrimaryButton("Write pack", enabled = capTheme.isNotBlank()) {
                            val body = JSONObject()
                                .put("theme", capTheme)
                                .put("style", capStyle)
                                .toString()
                            host.hubRelayPostPublic("/api/laptop/hypno/captions", body)
                        }
                    }
                }
            }
            "ingest" -> {
                item {
                    HubCard {
                        HubSectionTitle("Download media")
                        BasicTextField(
                            value = ingestUrls,
                            onValueChange = { ingestUrls = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = WdPalette.text),
                            minLines = 4,
                        )
                        HubPrimaryButton("Start ingest", enabled = ingestUrls.isNotBlank()) {
                            val urls = ingestUrls.lines().map { it.trim() }.filter { it.startsWith("http") }
                            val body = JSONObject().put("urls", JSONArray(urls)).toString()
                            host.hubRelayPostPublic("/api/laptop/hypno/ingest", body)
                        }
                    }
                }
            }
            "jobs" -> {
                items((0 until jobs.length()).map { jobs.getJSONObject(it) }) { j ->
                    HubCard {
                        Text(j.optString("title", j.optString("id")), fontWeight = FontWeight.SemiBold)
                        HubStatRow("Status", j.optString("status"))
                        HubStatRow("Kind", j.optString("kind"))
                    }
                }
            }
            "chat" -> {
                item {
                    HubCard {
                        BasicTextField(
                            value = chatMsg,
                            onValueChange = { chatMsg = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = WdPalette.text),
                            minLines = 3,
                        )
                        HubPrimaryButton("Ask HypnoForge", enabled = chatMsg.isNotBlank()) {
                            val msg = chatMsg
                            chatMsg = ""
                            chatLog = chatLog + "You: $msg"
                            val body = JSONObject().put("message", msg).toString()
                            host.hubRelayPostWithResponsePublic("/api/laptop/hypno/chat", body) { resp ->
                                val reply = runCatching { JSONObject(resp).optString("reply", resp) }.getOrDefault(resp)
                                chatLog = chatLog + "HF: $reply"
                            }
                        }
                    }
                }
                items(chatLog) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp))
                }
            }
        }
    }
}

@Composable
fun HubLtxBody(json: String, host: MainActivity) {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return
    val setup = root.optJSONObject("setup")
    val jobs = root.optJSONArray("jobs") ?: JSONArray()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HubCard {
                HubSectionTitle("LTX 2.5", "LoRAs from Setup")
                if (setup != null) {
                    HubPill(
                        if (setup.optBoolean("saved")) "${setup.optInt("enabled")}/${setup.optInt("count")} enabled" else "Setup empty",
                        ok = setup.optBoolean("saved"),
                    )
                }
            }
        }
        setup?.optJSONArray("loras")?.let { loras ->
            items((0 until loras.length()).map { loras.getJSONObject(it) }) { r ->
                HubCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(r.optString("name"), fontWeight = FontWeight.SemiBold)
                        HubPill(if (r.optBoolean("enabled")) "on" else "off", ok = r.optBoolean("enabled"))
                    }
                    Text(r.optString("filename"), style = MaterialTheme.typography.labelSmall, color = WdPalette.textMetadata)
                }
            }
        }
        item { HubSectionTitle("Generation jobs") }
        items((0 until jobs.length()).map { jobs.getJSONObject(it) }) { j ->
            HubCard {
                Text(j.optString("idea", j.optString("id")), fontWeight = FontWeight.SemiBold)
                HubStatRow("Status", j.optString("status"))
                HubStatRow("Clips", "${j.optInt("clips", j.optInt("total", 0))}")
                j.optString("error").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = WdPalette.accentLight)
                }
            }
        }
    }
}

@Composable
fun HubVastBody(json: String, host: MainActivity) {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return
    val jobs = root.optJSONArray("jobs") ?: JSONArray()
    val comfy = root.optJSONObject("comfy")
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HubCard {
                HubSectionTitle("Vast.ai · Remix v3", "ComfyUI box")
                Text(
                    "LoRAs for Remix live in Setup snapshots. LTX 2.5 uses the 11-file pack on Setup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = WdPalette.textSecondary,
                )
                HubPrimaryButton("Open Setup tab") {
                    host.showHubScreenPublic("setup")
                }
            }
        }
        item {
            HubCard {
                HubSectionTitle("14B runner", null)
                HubStatRow("Runner up", if (root.optBoolean("runner")) "yes" else "no")
                comfy?.let {
                    HubStatRow("Comfy online", if (it.optBoolean("online")) "yes" else "no")
                    HubStatRow("Queue", "${it.optInt("pending")} pending")
                }
            }
        }
        item { HubSectionTitle("Wanbot jobs") }
        items((0 until jobs.length()).map { jobs.getJSONObject(it) }) { j ->
            HubCard {
                Text(j.optString("name", j.optString("chain_id", j.optString("id"))), fontWeight = FontWeight.SemiBold)
                HubStatRow("Status", j.optString("status"))
            }
        }
    }
}

@Composable
fun HubFilesBody(json: String, host: MainActivity) {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return
    val ping = root.optJSONObject("ping")
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HubCard {
                HubSectionTitle("Laptop files", "Via relay SSH")
                HubStatRow("Laptop online", ping?.optBoolean("online")?.toString() ?: "unknown")
            }
        }
        listOf("home" to "Home (~)", "paths" to "venice_run & civitai_dl").forEach { (key, title) ->
            val block = root.optJSONObject(key)
            val out = block?.optString("output").orEmpty()
            if (out.isNotBlank()) {
                item {
                    HubCard {
                        HubSectionTitle(title)
                        Text(
                            out.take(4000),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = WdPalette.textSecondary,
                        )
                    }
                }
            }
        }
        item {
            HubPrimaryButton("Refresh listing") { host.refreshHubNativeScreenPublic() }
        }
    }
}

@Composable
fun HubShotwriterBody(json: String, host: MainActivity) {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return
    val jobs = root.optJSONArray("jobs") ?: JSONArray()
    var idea by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("5") }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HubSectionTitle("Shotwriter", "Prompt chains on the relay (OpenRouter)")
            HubCard {
                BasicTextField(
                    value = idea,
                    onValueChange = { idea = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = WdPalette.text),
                    minLines = 4,
                    decorationBox = {
                        if (idea.isEmpty()) Text("Describe your video idea…", color = WdPalette.textMetadata)
                        it()
                    },
                )
                BasicTextField(
                    value = total,
                    onValueChange = { total = it.filter { c -> c.isDigit() }.take(3) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    textStyle = MaterialTheme.typography.labelMedium.copy(color = WdPalette.text),
                    decorationBox = {
                        Text("Clip count: ", color = WdPalette.textMetadata)
                        it()
                    },
                )
                HubPrimaryButton("Start chain on relay", enabled = idea.isNotBlank()) {
                    val n = total.toIntOrNull() ?: 5
                    val body = JSONObject()
                        .put("idea", idea)
                        .put("system", "You write WAN video prompt chains.")
                        .put("shared", JSONArray(listOf("Write vivid, cinematic prompts.")))
                        .put("total", n)
                        .put("chunk", minOf(n, 5))
                        .put("model", "google/gemini-2.0-flash-001")
                        .put("link", "continuous")
                        .toString()
                    host.hubRelayPostPublic("/api/gen/chain", body)
                }
            }
        }
        item { HubSectionTitle("Recent jobs") }
        items((0 until jobs.length()).map { jobs.getJSONObject(it) }) { j ->
            HubCard {
                Text(j.optString("idea", j.optString("id")), fontWeight = FontWeight.SemiBold)
                HubStatRow("Status", j.optString("status"))
            }
        }
    }
}

@Composable
fun HubBotBody(json: String) {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return
    val bot = root.optJSONObject("bot")
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            HubSectionTitle(root.optString("title", "Bot"), root.optString("id"))
        }
        item {
            HubCard {
                if (bot != null && bot.length() > 0) {
                    Text(bot.optString("title", "Bot"), fontWeight = FontWeight.Bold)
                    bot.optString("description").takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                    bot.optJSONArray("tools")?.let { tools ->
                        HubSectionTitle("Tools", "${tools.length()} available")
                        (0 until tools.length()).map { tools.optString(it) }.forEach { t ->
                            Text("· $t", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                } else {
                    Text("Bot metadata not on relay yet.", color = WdPalette.textSecondary)
                }
            }
        }
    }
}

private fun parseLibrary(json: String): List<JSONObject>? {
    val arr = runCatching { JSONArray(json) }.getOrNull()
    if (arr != null) return (0 until arr.length()).map { arr.getJSONObject(it) }
    val root = runCatching { JSONObject(json) }.getOrNull()
    val lib = root?.optJSONArray("library")
    if (lib != null) return (0 until lib.length()).map { lib.getJSONObject(it) }
    return null
}
