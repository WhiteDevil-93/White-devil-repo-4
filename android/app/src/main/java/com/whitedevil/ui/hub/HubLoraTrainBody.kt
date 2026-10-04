package com.whitedevil.ui.hub

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.whitedevil.MainActivity
import com.whitedevil.relay.RelayHttp
import com.whitedevil.relayAuthPublic
import com.whitedevil.relayBasePublic
import com.whitedevil.ui.theme.WdPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

private const val API = "/api/loratrain"

/** A pending confirmation: what the dialog says, and the one call it allows. */
private class LtConfirm(val title: String, val lines: List<String>, val run: suspend () -> Unit)

/**
 * Native Train LoRA screen: datasets on the relay, files from the phone (any type, streamed, converted on the relay),
 * auto-captions and caption edits, training on the Colab G4 behind a confirm that states time, compute units and the
 * render pause, and run progress with cancel.
 */
@Composable
fun HubLoraTrainBody(host: MainActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val base = remember { host.relayBasePublic() }
    val auth = remember { host.relayAuthPublic() }
    var rows by remember { mutableStateOf<List<LtRow>>(emptyList()) }
    var runs by remember { mutableStateOf<List<LtRun>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var ds by remember { mutableStateOf<LtDataset?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var uploadPct by remember { mutableStateOf<Float?>(null) }
    var confirm by remember { mutableStateOf<LtConfirm?>(null) }
    val edits = remember { mutableStateMapOf<String, String>() }

    suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    suspend fun reload() {
        runCatching {
            rows = LoraTrainJson.rows(io { RelayHttp.get(base, auth, "$API/datasets") }) ?: emptyList()
            runs = LoraTrainJson.runs(io { RelayHttp.get(base, auth, "$API/runs") }) ?: emptyList()
            ds = selected?.let { id -> LoraTrainJson.dataset(io { RelayHttp.get(base, auth, "$API/datasets/$id") }) }
        }.onFailure { message = it.message ?: "Couldn't reach the hub" }
    }

    fun act(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label; message = null
        scope.launch {
            runCatching { block() }.onFailure { message = it.message ?: "$label failed" }
            busy = null; uploadPct = null
            reload()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val d = ds ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        act("Uploading") {
            var added = 0; var caps = 0
            val skipped = mutableListOf<String>()
            uris.forEachIndexed { n, uri ->
                var name = "file"; var size = -1L
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { name = c.getString(0) ?: name; size = if (c.isNull(1)) -1 else c.getLong(1) }
                }
                val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                busy = "Uploading ${n + 1} of ${uris.size}: $name"
                val reply = runCatching {
                    io {
                        val input = ctx.contentResolver.openInputStream(uri) ?: error("can't open $name")
                        RelayHttp.upload(base, auth, "$API/datasets/${d.id}/files", "files", name, mime, input) { sent ->
                            if (size > 0) uploadPct = (sent.toFloat() / size).coerceIn(0f, 1f)
                            if (size > 0 && sent >= size) busy = "Converting $name on the relay…"
                        }
                    }
                }
                reply.onSuccess { LoraTrainJson.upload(it)?.let { u -> added += u.added; caps += u.captions; skipped += u.skipped } }
                reply.onFailure { skipped += "$name: ${it.message}" }
            }
            message = "$added added" + (if (caps > 0) ", $caps captions from .txt files" else "") +
                (if (skipped.isNotEmpty()) ". Skipped: " + skipped.joinToString("; ") else "")
        }
    }

    LaunchedEffect(selected) { reload() }
    val moving = ds?.captioning == "running" || runs.any { it.active }
    LaunchedEffect(moving, selected) { while (moving) { delay(10_000); reload() } }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { HubSectionTitle("Train LoRA", "Datasets on the relay · training on the Colab G4") }
        message?.let { m -> item { HubCard { Text(m, style = MaterialTheme.typography.bodySmall, color = WdPalette.accentLight) } } }
        busy?.let { b ->
            item {
                HubCard {
                    Text(b, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { uploadPct ?: 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                        color = WdPalette.accent, trackColor = WdPalette.stroke,
                    )
                }
            }
        }
        item { NewDatasetCard(enabled = busy == null) { name, kind, trigger ->
            act("Creating") {
                val made = LoraTrainJson.dataset(io { RelayHttp.post(base, auth, "$API/datasets", LoraTrainJson.newDataset(name, kind, trigger)) })
                selected = made?.id; edits.clear()
            }
        } }
        item {
            HubCard {
                HubSectionTitle("Datasets", if (rows.isEmpty()) "None yet" else "${rows.size}")
                rows.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontWeight = FontWeight.SemiBold)
                            Text("${r.kind} · ${r.items} items · ${if (r.ready) "ready" else "not ready"}",
                                style = MaterialTheme.typography.labelSmall, color = WdPalette.textMetadata)
                        }
                        HubPill(if (r.id == selected) "Open ✓" else "Open", ok = r.id == selected) { selected = r.id; edits.clear() }
                    }
                }
            }
        }
        ds?.takeIf { it.id == selected }?.let { d ->
            item {
                HubCard {
                    HubSectionTitle(d.name, "${d.lora}.safetensors · ${d.kind}${d.trigger?.let { " · trigger $it" } ?: ""}")
                    HubPill(if (d.ready) "ready to train" else "not ready", ok = d.ready)
                    HubStatRow("Items", "${d.items.size} (${d.images} pictures · ${d.videos} clips)")
                    HubStatRow("Captioned", "${d.captioned} / ${d.items.size}" + if (d.captioning == "running") " · auto ${d.captionDone}/${d.captionTotal}" else "")
                    HubStatRow("Time (estimate)", d.estimate?.let { ltDuration(it.minutes) } ?: "?")
                    HubStatRow("Cost (estimate)", d.estimate?.costUnits?.let { "~$it Colab units" } ?: "shows once Colab ran")
                    d.recommended?.let { Text("Recommended: $it", style = MaterialTheme.typography.labelSmall, color = WdPalette.textSecondary) }
                    d.problems.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = WdPalette.accentLight, modifier = Modifier.padding(top = 4.dp)) }
                    d.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = WdPalette.textSecondary, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
            item { SettingsCard(d, busy == null) { trig, steps, rank ->
                act("Saving settings") { io { RelayHttp.post(base, auth, "$API/datasets/${d.id}/settings", LoraTrainJson.settings(trig, steps, rank)) } }
            } }
            item {
                HubCard {
                    HubPrimaryButton("Add files (any type, up to 25 GB each)", enabled = busy == null) { picker.launch(arrayOf("*/*")) }
                    Text("Pictures and clips are converted on the relay (clips to 24 fps, first 30 s). A .txt file captions the item with the same name; a .zip is unpacked.",
                        style = MaterialTheme.typography.labelSmall, color = WdPalette.textMetadata, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HubPill("Auto-caption", ok = null) {
                            if (d.captioning != "running" && d.items.isNotEmpty()) act("Starting auto-captions") { io { RelayHttp.post(base, auth, "$API/datasets/${d.id}/caption", "{}") } }
                        }
                        HubPill("Save ${edits.size} caption edit(s)", ok = edits.isNotEmpty()) {
                            if (edits.isNotEmpty()) act("Saving captions") {
                                io { RelayHttp.send(base, auth, "$API/datasets/${d.id}/captions", "PUT", LoraTrainJson.captions(edits.toMap())) }
                                edits.clear()
                            }
                        }
                        HubPill("Delete dataset", ok = false) {
                            confirm = LtConfirm("Delete ${d.name}?", listOf("Deletes its ${d.items.size} converted files and captions on the relay. Trained LoRAs are kept.")) {
                                io { RelayHttp.send(base, auth, "$API/datasets/${d.id}", "DELETE") }
                                selected = null; ds = null
                            }
                        }
                    }
                    Box(Modifier.padding(top = 10.dp)) {
                        HubPrimaryButton("Train on Colab", enabled = d.ready && busy == null) {
                            confirm = LtConfirm("Train ${d.lora} on Colab?", ltTrainConsequences(d)) {
                                io { RelayHttp.post(base, auth, "$API/datasets/${d.id}/train", "{}") }
                                message = "Training queued"
                            }
                        }
                    }
                }
            }
            items(d.items, key = { it.file }) { it -> ItemRow(it, d, base, auth, edits, enabled = busy == null) { file ->
                act("Removing $file") { io { RelayHttp.send(base, auth, "$API/datasets/${d.id}/files/" + URLEncoder.encode(file, "UTF-8").replace("+", "%20"), "DELETE") } }
            } }
        }
        item {
            HubCard {
                HubSectionTitle("Training runs", null)
                val shown = runs.filter { selected == null || it.dataset == selected }.take(10)
                if (shown.isEmpty()) Text("No runs yet.", style = MaterialTheme.typography.bodySmall, color = WdPalette.textMetadata)
                shown.forEach { r ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            HubPill(r.status, ok = when (r.status) { "done" -> true; "failed" -> false; else -> null })
                            if (r.active) {
                                Box(Modifier.width(8.dp))
                                HubPill("Cancel", ok = false) {
                                    confirm = LtConfirm("Cancel training ${r.name}?", listOf("Stops training on Colab. Checkpoints already on the relay are kept.")) {
                                        io { RelayHttp.post(base, auth, "$API/runs/${r.id}/cancel", "{}") }
                                    }
                                }
                            }
                        }
                        Text(r.step, style = MaterialTheme.typography.labelSmall, color = WdPalette.textSecondary)
                        if (r.stepNow != null && r.stepTotal != null && r.stepTotal > 0)
                            HubProgressRow("Step ${r.stepNow} of ${r.stepTotal}", r.stepNow * 100 / r.stepTotal)
                        if (r.pulled.isNotEmpty()) Text("Checkpoints on the relay: ${r.pulled.size} (${r.pulled.count { it.verified }} size-verified)",
                            style = MaterialTheme.typography.labelSmall, color = WdPalette.textMetadata)
                        r.final?.let { Text("LoRA ${it.size / 1_048_576} MB on the relay, ${if (it.verified) "size verified" else "NOT verified"}",
                            style = MaterialTheme.typography.labelSmall, color = if (it.verified) WdPalette.success else WdPalette.accentLight) }
                    }
                }
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.title) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { c.lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } } },
            confirmButton = { TextButton(onClick = { confirm = null; act(c.title) { c.run() } }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Back") } },
        )
    }
}

@Composable
private fun NewDatasetCard(enabled: Boolean, onCreate: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("character") }
    var trigger by remember { mutableStateOf("") }
    HubCard {
        HubSectionTitle("New dataset", null)
        Text("Character: 25-40 pictures and short clips of one person (different angles, light, outfits, backgrounds) and a made-up trigger word. " +
            "Motion: 30-50 clips of the movement with different people. Anatomy / pose: 30-60 pictures and some clips of ONE body part " +
            "or ONE position across many different people. A real person needs their consent (LTX licence).",
            style = MaterialTheme.typography.labelSmall, color = WdPalette.textSecondary)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("LoRA name") })
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HubPill("Character", ok = kind == "character") { kind = "character" }
            HubPill("Motion", ok = kind == "motion") { kind = "motion" }
            HubPill("Anatomy / pose", ok = kind == "concept") { kind = "concept" }
        }
        OutlinedTextField(trigger, { trigger = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Trigger word (e.g. ohwx_man)") })
        Box(Modifier.padding(top = 10.dp)) {
            HubPrimaryButton("Create", enabled = enabled && name.isNotBlank()) { onCreate(name, kind, trigger); name = ""; trigger = "" }
        }
    }
}

@Composable
private fun SettingsCard(d: LtDataset, enabled: Boolean, onSave: (String?, Int?, Int?) -> Unit) {
    var trig by remember(d.id, d.trigger) { mutableStateOf(d.trigger ?: "") }
    var steps by remember(d.id, d.steps) { mutableStateOf(d.steps?.toString() ?: "") }
    var rank by remember(d.id, d.rank) { mutableStateOf(d.rank?.toString() ?: "") }
    HubCard {
        HubSectionTitle("Settings", "Leave steps and rank empty for the recommended values")
        OutlinedTextField(trig, { trig = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Trigger word") })
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(steps, { steps = it.filter(Char::isDigit) }, Modifier.weight(1f), singleLine = true,
                label = { Text("Steps") }, placeholder = { Text("${d.estimate?.steps ?: ""}") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(rank, { rank = it.filter(Char::isDigit) }, Modifier.weight(1f), singleLine = true,
                label = { Text("Rank") }, placeholder = { Text("32") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Box(Modifier.padding(top = 8.dp)) { HubPrimaryButton("Save settings", enabled = enabled) { onSave(trig, steps.toIntOrNull(), rank.toIntOrNull()) } }
    }
}

@Composable
private fun ItemRow(item: LtItem, d: LtDataset, base: String, auth: String, edits: MutableMap<String, String>, enabled: Boolean, onRemove: (String) -> Unit) {
    val ctx = LocalContext.current
    HubCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (item.type == "image") {
                AsyncImage(
                    model = ImageRequest.Builder(ctx)
                        .data("$base$API/datasets/${d.id}/files/" + URLEncoder.encode(item.file, "UTF-8").replace("+", "%20"))
                        .addHeader("Authorization", auth).crossfade(true).build(),
                    contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(4.dp)).background(WdPalette.stroke),
                )
            } else {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(4.dp)).background(WdPalette.stroke), contentAlignment = Alignment.Center) {
                    Text("▶ clip", style = MaterialTheme.typography.labelSmall, color = WdPalette.textSecondary)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(item.file, style = MaterialTheme.typography.labelSmall)
                Text("${item.type} · ${item.size / 1024} KB" + (item.captionBy?.let { " · caption: $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = WdPalette.textMetadata)
                item.error?.let { Text("Auto-caption failed: $it", style = MaterialTheme.typography.labelSmall, color = WdPalette.accentLight) }
            }
            if (enabled) HubPill("✕", ok = false) { onRemove(item.file) }
        }
        OutlinedTextField(
            value = edits[item.file] ?: item.caption,
            onValueChange = { edits[item.file] = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text("Caption") }, minLines = 2,
        )
    }
}
