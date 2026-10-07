package com.whitedevil.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whitedevil.desktop.ops.ActionController
import com.whitedevil.desktop.ops.ActionDialog
import com.whitedevil.desktop.ops.ActionOutcome
import com.whitedevil.desktop.ops.ActionSpec
import com.whitedevil.desktop.ops.EmptyLine
import com.whitedevil.desktop.ops.KeyValue
import com.whitedevil.desktop.ops.MonoBlock
import com.whitedevil.desktop.ops.Note
import com.whitedevil.desktop.ops.OpsScreenFrame
import com.whitedevil.desktop.ops.Picker
import com.whitedevil.desktop.ops.Pill
import com.whitedevil.desktop.ops.SectionCard
import com.whitedevil.desktop.ops.Tile
import com.whitedevil.desktop.ops.TileRow
import com.whitedevil.desktop.ops.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private val KIND_LABELS = linkedMapOf("character" to "Character / identity", "motion" to "Motion / movement", "concept" to "Anatomy / pose")

/**
 * Train LoRA: make a dataset, drop in pictures and clips (any file type: the hub converts them, .txt files become
 * captions, .zip files are unpacked), auto-caption and fix the captions, then train on the Colab G4. Starting a run
 * goes through the confirmation dialog because it uses Colab compute units and pauses renders.
 */
@Composable
fun LoraTrainScreen(settings: Settings) {
    val client = remember(settings.hubUrl, settings.relayUser, settings.relayPass) {
        LoraTrainClient(settings.hubUrl, settings.relayUser, settings.relayPass)
    }
    DisposableEffect(client) { onDispose { client.close() } }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<LoraDatasetRow>>(emptyList()) }
    var runs by remember { mutableStateOf<List<LoraRun>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var ds by remember { mutableStateOf<LoraDataset?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val edits = remember { mutableStateMapOf<String, String>() }
    val controller = remember { ActionController() }

    suspend fun reload() {
        refreshing = true
        when (val r = client.datasets()) { is MediaResult.Ok -> rows = r.value; is MediaResult.Failure -> error = r.error.message }
        when (val r = client.runs()) { is MediaResult.Ok -> runs = r.value; is MediaResult.Failure -> error = r.error.message }
        selected?.let { id ->
            when (val r = client.dataset(id)) { is MediaResult.Ok -> ds = r.value; is MediaResult.Failure -> error = r.error.message }
        }
        refreshing = false
    }

    LaunchedEffect(client, selected) { reload() }
    // Poll while something is moving: captions being written or a run active.
    val moving = ds?.captioning == "running" || runs.any { it.active }
    LaunchedEffect(moving, selected) {
        while (moving) { delay(10_000); reload() }
    }

    fun act(label: String, block: suspend () -> MediaResult<*>) {
        if (busy != null) return
        busy = label; error = null
        scope.launch {
            val r = block()
            if (r is MediaResult.Failure) error = r.error.message
            busy = null
            reload()
        }
    }

    OpsScreenFrame(
        title = "Train LoRA",
        subtitle = "Datasets live on the relay; training runs on the Colab G4",
        refreshing = refreshing,
        onRefresh = { scope.launch { reload() } },
    ) {
        error?.let { Note(it, Tone.Bad) }
        busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { Text("$it…", style = MaterialTheme.typography.bodySmall) } }
        NewDatasetCard(enabled = busy == null) { name, kind, trigger ->
            act("Creating") {
                client.create(name, kind, trigger).also { if (it is MediaResult.Ok) { selected = it.value.id; edits.clear() } }
            }
        }
        SectionCard("Datasets") {
            if (rows.isEmpty()) EmptyLine("No datasets yet.")
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${KIND_LABELS[r.kind] ?: r.kind} · ${r.items} items", style = MaterialTheme.typography.bodySmall)
                    Pill(if (r.ready) "ready" else "not ready", if (r.ready) Tone.Ok else Tone.Neutral)
                    TextButton(onClick = { selected = r.id; edits.clear() }) { Text(if (r.id == selected) "Open ✓" else "Open") }
                }
            }
        }
        val d = ds?.takeIf { it.id == selected }
        if (d != null) {
            DatasetCard(d, busy == null, edits,
                onAdd = {
                    val dialog = FileDialog(null as Frame?, "Add pictures, clips, caption .txt files or a .zip", FileDialog.LOAD).apply { isMultipleMode = true; isVisible = true }
                    val files = dialog.files?.toList().orEmpty()
                    if (files.isNotEmpty()) act("Uploading ${files.size} file(s)") {
                        var last: MediaResult<*> = MediaResult.Ok(0)
                        files.forEachIndexed { n, f ->
                            busy = "Uploading ${n + 1} of ${files.size}: ${f.name} (${f.length() / 1_048_576} MB)"
                            val r = client.upload(d.id, f)
                            if (r is MediaResult.Failure) { error = "${f.name}: ${r.error.message}"; last = r }
                        }
                        last
                    }
                },
                onCaption = { act("Starting auto-captions") { client.autoCaption(d.id) } },
                onSaveCaptions = { act("Saving captions") { client.saveCaptions(d.id, edits.toMap()).also { if (it is MediaResult.Ok) edits.clear() } } },
                onDelete = { file -> act("Removing $file") { client.deleteItem(d.id, file) } },
                onSettings = { steps, rank, trigger -> act("Saving settings") { client.settings(d.id, steps, rank, trigger) } },
                onTrain = {
                    controller.request(ActionSpec(
                        title = "Train '${d.lora}' on Colab",
                        consequences = trainConsequences(d),
                        confirmLabel = "Start training",
                        danger = true,
                        run = {
                            when (val r = client.train(d.id)) {
                                is MediaResult.Ok -> ActionOutcome.Succeeded("Training queued", "Run ${r.value.id}: ${r.value.step}")
                                is MediaResult.Failure -> ActionOutcome.Failed(r.error.message)
                            }.also { scope.launch { reload() } }
                        },
                    ))
                },
                onRemoveDataset = {
                    controller.request(ActionSpec(
                        title = "Delete the dataset '${d.name}'",
                        consequences = listOf("Deletes its ${d.items.size} converted files and captions on the relay. Trained LoRAs are kept."),
                        confirmLabel = "Delete", typedPhrase = "delete", danger = true,
                        run = {
                            when (val r = client.deleteDataset(d.id)) {
                                is MediaResult.Ok -> ActionOutcome.Succeeded("Deleted").also { selected = null; ds = null }
                                is MediaResult.Failure -> ActionOutcome.Failed(r.error.message)
                            }.also { scope.launch { reload() } }
                        },
                    ))
                },
            )
        }
        RunsCard(runs.filter { selected == null || it.dataset == selected }) { run ->
            controller.request(ActionSpec(
                title = "Cancel training '${run.name}'",
                consequences = listOf("Stops training on Colab. Checkpoints already pulled to the relay are kept.", "Renders can start again once it has stopped."),
                confirmLabel = "Cancel training", danger = true,
                run = {
                    when (val r = client.cancel(run.id)) {
                        is MediaResult.Ok -> ActionOutcome.Succeeded("Cancelling")
                        is MediaResult.Failure -> ActionOutcome.Failed(r.error.message)
                    }.also { scope.launch { reload() } }
                },
            ))
        }
    }
    ActionDialog(controller)
}

@Composable
private fun NewDatasetCard(enabled: Boolean, onCreate: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("character") }
    var trigger by remember { mutableStateOf("") }
    SectionCard("New dataset") {
        Note("Character: 25-40 pictures and short clips of one person (varied angles, light, outfits, backgrounds) plus a made-up trigger word. " +
            "Motion: 30-50 clips of the movement with different people. Anatomy / pose: 30-60 pictures and some clips of ONE body part " +
            "or ONE position across many different people. A real person needs their consent (LTX licence).")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true, label = { Text("LoRA name") })
            Picker("Kind", KIND_LABELS.values.toList(), KIND_LABELS[kind] ?: kind, { label -> kind = KIND_LABELS.entries.first { it.value == label }.key })
            OutlinedTextField(trigger, { trigger = it }, Modifier.width(180.dp), singleLine = true, label = { Text("Trigger word") })
            Button(onClick = { onCreate(name, kind, trigger); name = ""; trigger = "" }, enabled = enabled && name.isNotBlank()) { Text("Create") }
        }
    }
}

@Composable
private fun DatasetCard(
    d: LoraDataset,
    enabled: Boolean,
    edits: MutableMap<String, String>,
    onAdd: () -> Unit,
    onCaption: () -> Unit,
    onSaveCaptions: () -> Unit,
    onDelete: (String) -> Unit,
    onSettings: (Int?, Int?, String?) -> Unit,
    onTrain: () -> Unit,
    onRemoveDataset: () -> Unit,
) {
    SectionCard(d.name, trailing = { Pill(if (d.ready) "ready to train" else "not ready", if (d.ready) Tone.Ok else Tone.Warn) }) {
        TileRow {
            Tile("Items", "${d.items.size}", "${d.images} pictures · ${d.videos} clips", Modifier.weight(1f))
            Tile("Captioned", "${d.captioned} / ${d.items.size}", d.captioning?.let { if (it == "running") "auto-captioning ${d.captionDone}/${d.captionTotal}" else null }, Modifier.weight(1f))
            Tile("Time", d.estimate?.let { "~${it.minutes / 60} h ${it.minutes % 60} m" } ?: "?", "ESTIMATE", Modifier.weight(1f))
            Tile("Cost", d.estimate?.costUnits?.let { "~$it units" } ?: "?", d.estimate?.unitsPerHr?.let { "at $it units/h" } ?: "rate shows once Colab ran", Modifier.weight(1f))
        }
        KeyValue("LoRA file", "${d.lora}.safetensors", mono = true)
        d.recommended?.let { Note("Recommended: $it") }
        d.problems.forEach { Note(it, Tone.Warn) }
        d.warnings.forEach { Note(it) }
        var steps by remember(d.id, d.steps) { mutableStateOf(d.steps?.toString() ?: "") }
        var rank by remember(d.id, d.rank) { mutableStateOf(d.rank?.toString() ?: "") }
        var trigger by remember(d.id, d.trigger) { mutableStateOf(d.trigger ?: "") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(trigger, { trigger = it }, Modifier.width(180.dp), singleLine = true, label = { Text("Trigger word") })
            OutlinedTextField(steps, { steps = it.filter(Char::isDigit) }, Modifier.width(140.dp), singleLine = true,
                label = { Text("Steps") }, placeholder = { Text("${d.estimate?.steps ?: ""}") })
            OutlinedTextField(rank, { rank = it.filter(Char::isDigit) }, Modifier.width(120.dp), singleLine = true,
                label = { Text("Rank") }, placeholder = { Text("32") })
            OutlinedButton(onClick = { onSettings(steps.toIntOrNull(), rank.toIntOrNull(), trigger) }, enabled = enabled) { Text("Save settings") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd, enabled = enabled) { Text("Add files…") }
            OutlinedButton(onClick = onCaption, enabled = enabled && d.captioning != "running" && d.items.isNotEmpty()) { Text("Auto-caption") }
            OutlinedButton(onClick = onSaveCaptions, enabled = enabled && edits.isNotEmpty()) { Text("Save ${edits.size} caption edit(s)") }
            Button(onClick = onTrain, enabled = enabled && d.ready) { Text("Train on Colab") }
            TextButton(onClick = onRemoveDataset, enabled = enabled) { Text("Delete dataset") }
        }
        Note("Any file type: pictures and clips are converted on the relay (clips to 24 fps, first 30 s), a .txt file captions the item with the same name, a .zip is unpacked. Up to 25 GB per file.")
        if (d.items.isEmpty()) EmptyLine("No items yet. Add files.")
        d.items.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.file, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("${item.type} · ${item.size / 1024} KB${item.captionBy?.let { " · caption: $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onDelete(item.file) }, enabled = enabled) { Text("Remove") }
                }
                item.error?.let { Note("Auto-caption failed: $it", Tone.Warn) }
                OutlinedTextField(
                    value = edits[item.file] ?: item.caption,
                    onValueChange = { edits[item.file] = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Caption") },
                    minLines = 2,
                )
            }
        }
    }
}

@Composable
private fun RunsCard(runs: List<LoraRun>, onCancel: (LoraRun) -> Unit) {
    SectionCard("Training runs") {
        if (runs.isEmpty()) EmptyLine("No runs yet.")
        runs.take(10).forEach { r ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Pill(r.status, when (r.status) { "done" -> Tone.Ok; "failed" -> Tone.Bad; "cancelled" -> Tone.Neutral; else -> Tone.Warn })
                    if (r.active) TextButton(onClick = { onCancel(r) }) { Text("Cancel") }
                }
                Text(r.step, style = MaterialTheme.typography.bodySmall)
                if (r.stepNow != null && r.stepTotal != null && r.stepTotal > 0) {
                    LinearProgressIndicator(progress = { r.stepNow.toFloat() / r.stepTotal }, modifier = Modifier.fillMaxWidth())
                    Text("Step ${r.stepNow} of ${r.stepTotal}", style = MaterialTheme.typography.bodySmall)
                }
                if (r.pulled.isNotEmpty()) {
                    val ok = r.pulled.count { it.verified }
                    Text("Checkpoints on the relay: ${r.pulled.size} (${ok} size-verified)", style = MaterialTheme.typography.bodySmall)
                }
                r.final?.let { KeyValue("LoRA", "${it.local} (${it.size / 1_048_576} MB, ${if (it.verified) "size verified" else "NOT verified"})", mono = true) }
                if (r.active || r.status == "failed") r.log?.takeIf { it.isNotBlank() }?.let { MonoBlock(it.lines().takeLast(12).joinToString("\n")) }
            }
        }
    }
}
