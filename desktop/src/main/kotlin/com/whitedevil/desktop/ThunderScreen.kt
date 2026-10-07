package com.whitedevil.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.whitedevil.desktop.ops.ChainSpec
import com.whitedevil.desktop.ops.EmptyLine
import com.whitedevil.desktop.ops.KeyValue
import com.whitedevil.desktop.ops.LoadOnce
import com.whitedevil.desktop.ops.MonoBlock
import com.whitedevil.desktop.ops.Note
import com.whitedevil.desktop.ops.OPS_POLL_MS
import com.whitedevil.desktop.ops.OpsError
import com.whitedevil.desktop.ops.OpsErrorKind
import com.whitedevil.desktop.ops.OpsResult
import com.whitedevil.desktop.ops.OpsScreenFrame
import com.whitedevil.desktop.ops.OpsState
import com.whitedevil.desktop.ops.PanelState
import com.whitedevil.desktop.ops.PanelView
import com.whitedevil.desktop.ops.Picker
import com.whitedevil.desktop.ops.Pill
import com.whitedevil.desktop.ops.PollWhileVisible
import com.whitedevil.desktop.ops.SectionCard
import com.whitedevil.desktop.ops.ThunderActions
import com.whitedevil.desktop.ops.ThunderApi
import com.whitedevil.desktop.ops.ThunderInstance
import com.whitedevil.desktop.ops.ThunderJob
import com.whitedevil.desktop.ops.ThunderQueue
import com.whitedevil.desktop.ops.ThunderSnapshot
import com.whitedevil.desktop.ops.ThunderState
import com.whitedevil.desktop.ops.Tone
import com.whitedevil.desktop.ops.formatClock
import com.whitedevil.desktop.ops.orUnknown
import com.whitedevil.desktop.ops.rememberOpsClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDate
import javax.swing.SwingUtilities

/**
 * Thunder Compute: instances and snapshots (GET /state), and the 14B render queue (GET /queue,
 * polled every 15s while this screen is open).
 *
 * Every mutating control (create, delete, snapshot, submit a chain, cancel/retry a job) opens a
 * confirmation first and shows the hub's own answer afterwards. Submitting a chain treats HTTP 200
 * as success only when the reply says ok=true, and lists which parts failed otherwise. Not offered
 * here: resize (modify) and port changes; use the web screen for those.
 */
@Composable
fun ThunderScreen(settings: Settings, onCreate: (String) -> Unit = {}) {
    val clients = rememberOpsClients(settings)
    val api = remember(clients) { ThunderApi(clients.reader) }
    val actions = remember(clients) { ThunderActions(clients.actor) }
    val scope = rememberCoroutineScope()
    val state = remember(api) { PanelState(api::state) }
    val queue = remember(api) { PanelState(api::queue) }
    // After a confirmed action ends, re-read both panels (GETs) so the screen shows the hub's truth.
    val controller = remember(state, queue) {
        ActionController(onFinished = { scope.launch { state.refresh(followUp = true) }; scope.launch { queue.refresh(followUp = true) } })
    }

    LoadOnce(state)
    PollWhileVisible(queue, OPS_POLL_MS)

    OpsScreenFrame(
        title = "Thunder",
        subtitle = "Queue refreshes every ${OPS_POLL_MS / 1000}s · instances on Refresh",
        refreshing = state.refreshing || queue.refreshing,
        onRefresh = { scope.launch { state.refresh() }; scope.launch { queue.refresh() } },
    ) {
        RenderHereCard(
            "Thunder",
            listOf("Wan 2.2 14B renders here: the 14B runner on your Thunder instance takes the chain you build in Create. The queue below shows what it is doing."),
            onCreate,
        )
        Note(
            "Thunder has no stop button: to stop paying, snapshot the instance and delete it. " +
                "Buttons that create, delete, snapshot or submit ask for confirmation first and then show the hub's answer.",
        )
        PanelView(state, "Thunder instances", onRetry = { scope.launch { state.refresh() } }) { s, stale ->
            InstancesCard(s, stale, controller, actions)
            NewInstanceCard(s, stale, controller, actions)
            SnapshotsCard(s, stale, controller, actions)
        }
        PanelView(queue, "the 14B render queue", onRetry = { scope.launch { queue.refresh() } }) { q, stale ->
            QueueCard(q, stale, controller, actions)
        }
        SendChainCard(queue, controller, actions)
    }
    ActionDialog(controller)
}

private fun statusTone(status: String?): Tone {
    val s = status?.lowercase() ?: return Tone.Neutral
    return when {
        s == "running" -> Tone.Ok
        Regex("start|creat|pend|snap|restor").containsMatchIn(s) -> Tone.Warn
        Regex("err|fail|delet").containsMatchIn(s) -> Tone.Bad
        else -> Tone.Neutral
    }
}

@Composable
private fun InstancesCard(s: ThunderState, stale: Boolean, controller: ActionController, actions: ThunderActions) {
    SectionCard("Instances", trailing = { Text("${s.instances.size}", style = MaterialTheme.typography.labelMedium) }) {
        if (s.instances.isEmpty()) EmptyLine("Thunder lists no instances.")
        s.instances.forEach { i -> InstanceRow(i, s, stale, controller, actions) }
        if (s.pricing == null) EmptyLine("Pricing unavailable: the hub could not fetch it, so hourly rates below are unknown.")
    }
}

@Composable
private fun InstanceRow(i: ThunderInstance, s: ThunderState, stale: Boolean, controller: ActionController, actions: ThunderActions) {
    val rate = s.ratePerHour(i.gpuType, i.numGpus)
    val label = i.name ?: i.id
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(10.dp))
            Pill(i.status ?: "status unknown", statusTone(i.status))
        }
        Text(
            listOfNotNull(
                "${i.numGpus ?: 1}× ${i.gpuType?.uppercase() ?: "GPU unknown"}",
                i.cpuCores?.let { "$it vCPU" },
                i.storageGb?.let { "$it GB disk" },
                i.template,
                rate?.let { "about $${"%.2f".format(java.util.Locale.ROOT, it)}/h from Thunder pricing" } ?: "rate unknown",
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        i.createdAt?.let { KeyValue("Created", it) }
        i.sshCommand?.let { KeyValue("SSH", it, mono = true) }
        if (i.httpPorts.isNotEmpty()) KeyValue("Open HTTP ports", i.httpPorts.joinToString(", "))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !stale && !controller.busy, onClick = {
                val snapName = "${label.replace(Regex("\\W+"), "-")}-${LocalDate.now()}"
                controller.request(
                    ActionSpec(
                        title = "Snapshot \"$label\"",
                        consequences = listOf(
                            "Asks Thunder to save a snapshot of instance ${i.id} named \"$snapName\".",
                            "Snapshots may incur storage charges on your Thunder account.",
                            "The instance keeps running and keeps billing.",
                        ),
                        confirmLabel = "Create snapshot",
                        danger = false,
                        run = { actions.createSnapshot(i.id, snapName) },
                    ),
                )
            }) { Text("Snapshot…") }
            Button(
                enabled = !stale && !controller.busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                onClick = {
                    controller.request(
                        ActionSpec(
                            title = "Delete instance \"$label\"",
                            consequences = listOf(
                                "Permanently deletes Thunder instance ${i.id}. Everything on its disk is lost unless a snapshot exists.",
                                "Billing for it stops once it is gone. Anything running on it (including 14B renders) stops.",
                                if (s.snapshots.isEmpty()) "The hub lists no snapshots right now, so nothing is saved." else "${s.snapshots.size} snapshot(s) are listed; check one covers this instance.",
                            ),
                            confirmLabel = "Delete instance",
                            typedPhrase = "DELETE",
                            run = { actions.deleteInstance(i.id) },
                        ),
                    )
                },
            ) { Text("Delete…") }
        }
    }
}

@Composable
private fun NewInstanceCard(s: ThunderState, stale: Boolean, controller: ActionController, actions: ThunderActions) {
    val types = s.gpuTypes()
    var gpuPick by remember { mutableStateOf<String?>(null) }
    var gpusPick by remember { mutableStateOf(1) }
    var cpuPick by remember { mutableStateOf<Int?>(null) }
    var disk by remember { mutableStateOf("100") }
    var templatePick by remember { mutableStateOf<String?>(null) }

    val gpu = gpuPick?.takeIf { it in types } ?: types.firstOrNull { it == "l40" } ?: types.first()
    val spec = s.specs?.get("${gpu}_x$gpusPick")
    val cpuOptions = spec?.vcpuOptions?.takeIf { it.isNotEmpty() } ?: listOf(4, 8, 16, 32)
    val cpu = cpuPick?.takeIf { it in cpuOptions } ?: cpuOptions.firstOrNull { it == 8 } ?: cpuOptions.first()
    val templates = s.templates?.takeIf { it.isNotEmpty() } ?: listOf(
        com.whitedevil.desktop.ops.ThunderTemplate("base", "Base (Ubuntu + CUDA)"),
        com.whitedevil.desktop.ops.ThunderTemplate("ollama", "Ollama"),
        com.whitedevil.desktop.ops.ThunderTemplate("comfy-ui", "ComfyUI"),
        com.whitedevil.desktop.ops.ThunderTemplate("webui-forge", "WebUI Forge"),
    )
    val template = templates.firstOrNull { it.value == templatePick } ?: templates.first()
    val diskGb = disk.trim().toIntOrNull()
    val diskOk = diskGb != null && diskGb in 100..1000
    val stock = s.stock?.get("${gpu}_x$gpusPick")
    val rate = s.ratePerHour(gpu, gpusPick)
    val diskRate = s.pricing?.get("disk_gb")
    val estimate = if (rate != null && diskGb != null) rate + maxOf(0, diskGb - 100) * (diskRate ?: 0.0) else null
    val specMissing = s.specs?.isNotEmpty() == true && spec == null

    SectionCard("New instance") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Picker("GPU", types.map { s.gpuLabel(it) }, s.gpuLabel(gpu), { label -> gpuPick = types.first { s.gpuLabel(it) == label } })
            Picker("GPUs", listOf("1", "2", "4", "8"), gpusPick.toString(), { gpusPick = it.toInt() })
            Picker("vCPUs", cpuOptions.map { it.toString() }, cpu.toString(), { cpuPick = it.toInt() })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(disk, { disk = it.filter(Char::isDigit).take(4) }, label = { Text("Disk (GB, 100 to 1000)") }, singleLine = true, modifier = Modifier.width(220.dp), isError = !diskOk)
            Picker("Template", templates.map { it.label }, template.label, { label -> templatePick = templates.first { it.label == label }.value })
        }
        Text(
            when {
                specMissing -> "Thunder does not list ${s.gpuLabel(gpu)} in a ${gpusPick}× configuration."
                estimate != null -> "About $${"%.2f".format(java.util.Locale.ROOT, estimate)} per hour${spec?.vramGb?.let { " · $it GB VRAM each" } ?: ""}."
                else -> "Hourly price unknown: the hub has no pricing for this configuration."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (stock != null && stock != "available") Note("Thunder reports ${s.gpuLabel(gpu)} ${gpusPick}× as \"$stock\".", Tone.Warn)
        Button(
            enabled = !stale && !controller.busy && diskOk && !specMissing,
            onClick = {
                // Frozen at click time: what the dialog shows is exactly what run() sends.
                val n = gpusPick
                val g = gpu
                val cores = cpu
                val tpl = template
                val d = diskGb!!
                val est = estimate
                val stk = stock
                controller.request(
                    ActionSpec(
                        title = "Create a Thunder instance",
                        consequences = listOfNotNull(
                            "Creates ${n}× ${s.gpuLabel(g)}, $cores vCPUs, $d GB disk, template \"${tpl.label}\".",
                            est?.let { "It bills about $${"%.2f".format(java.util.Locale.ROOT, it)} per hour from the moment it exists, until you delete it." }
                                ?: "It bills hourly from the moment it exists, until you delete it. The hub has no price for this configuration, so the rate is unknown.",
                            "Thunder has no stop: the only way to stop the bill is to snapshot and delete.",
                            if (stk != null && stk != "available") "Thunder reports this GPU as \"$stk\"; creation may fail." else null,
                        ),
                        confirmLabel = "Create instance",
                        typedPhrase = "CREATE",
                        run = { actions.createInstance(g, n, cores, tpl.value, d) },
                    ),
                )
            },
        ) { Text("Create instance…") }
    }
}

@Composable
private fun SnapshotsCard(s: ThunderState, stale: Boolean, controller: ActionController, actions: ThunderActions) {
    SectionCard("Snapshots", trailing = { Text("${s.snapshots.size}", style = MaterialTheme.typography.labelMedium) }) {
        if (s.snapshots.isEmpty()) {
            EmptyLine("No snapshots listed. The hub also reports an empty list when its snapshot call failed, so this is not proof there are none.")
        }
        s.snapshots.forEach { snap -> SnapshotRow(snap, stale, controller, actions) }
    }
}

@Composable
private fun SnapshotRow(snap: ThunderSnapshot, stale: Boolean, controller: ActionController, actions: ThunderActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(snap.name ?: snap.id, style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(snap.status, snap.createdAt, snap.minDiskGb?.let { "$it GB" }).joinToString(" · ").ifBlank { "no details" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(enabled = !stale && !controller.busy, onClick = {
            controller.request(
                ActionSpec(
                    title = "Delete snapshot \"${snap.name ?: snap.id}\"",
                    consequences = listOf("Permanently deletes Thunder snapshot ${snap.id}. Instances cannot be restored from it afterwards."),
                    confirmLabel = "Delete snapshot",
                    run = { actions.deleteSnapshot(snap.id) },
                ),
            )
        }) { Text("Delete…") }
    }
}

@Composable
internal fun QueueCard(q: ThunderQueue, stale: Boolean, controller: ActionController, actions: ThunderActions) {
    SectionCard(
        "14B render queue",
        trailing = { Pill(when (q.runnerUp) { true -> "runner up"; false -> "runner not reachable"; null -> "runner unknown" }, if (q.runnerUp == true) Tone.Ok else Tone.Warn) },
    ) {
        val c = q.comfy
        Text(
            when {
                c == null -> "ComfyUI status unknown: the hub sent none."
                c.online == true -> "ComfyUI online · ${if ((c.running ?: 0) > 0) "rendering" else "idle"}${c.pending?.takeIf { it > 0 }?.let { " · $it waiting" } ?: ""}"
                else -> "ComfyUI offline: ${c.why ?: "no reason given"}"
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (q.runnerUp != true && q.jobs.isEmpty()) {
            Note("The 14B runner is not reachable from the hub, so no job list is available. An empty queue here does NOT mean nothing is queued.", Tone.Warn)
        } else if (q.jobs.isEmpty()) {
            EmptyLine("The runner is up and reports no jobs.")
        }
        q.jobs.forEach { j -> JobRow(j, stale, controller, actions) }
    }
}

@Composable
private fun JobRow(j: ThunderJob, stale: Boolean, controller: ActionController, actions: ThunderActions) {
    val label = j.name ?: j.chainId ?: j.id ?: "(unnamed job)"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Pill(
                j.status ?: "unknown",
                when (j.status) { "rendering", "done" -> Tone.Ok; "error" -> Tone.Bad; "waiting" -> Tone.Warn; else -> Tone.Neutral },
            )
        }
        val total = j.total
        val done = j.done
        if (total != null && total > 0 && done != null) {
            LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            listOfNotNull(
                if (done != null || total != null) "${orUnknown(done)} of ${orUnknown(total)} clips" else null,
                j.currentClip?.let { "on clip $it" },
                j.avgSeconds?.let { "~${it / 60} min each" },
                j.created?.let { "sent $it" },
                j.id?.let { "id $it" },
            ).joinToString(" · ").ifBlank { "no progress reported" },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        j.error?.let { Text(it.take(400), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        val id = j.id
        if (id != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (j.canRetry) {
                    OutlinedButton(enabled = !stale && !controller.busy, onClick = {
                        controller.request(
                            ActionSpec(
                                title = "Retry job \"$label\"",
                                consequences = listOf(
                                    "Asks the 14B runner to retry job $id. It resumes from the last finished clip.",
                                    "Rendering uses GPU time on your billing Thunder instance.",
                                ),
                                confirmLabel = "Retry job",
                                danger = false,
                                run = { actions.jobAction(id, "retry") },
                            ),
                        )
                    }) { Text("Retry…") }
                }
                if (j.canCancel) {
                    OutlinedButton(enabled = !stale && !controller.busy, onClick = {
                        controller.request(
                            ActionSpec(
                                title = "Cancel job \"$label\"",
                                consequences = listOf("Cancels 14B job $id. Clips already finished are kept; the rest of this chain will not render."),
                                confirmLabel = "Cancel job",
                                run = { actions.jobAction(id, "cancel") },
                            ),
                        )
                    }) { Text("Cancel job…") }
                }
            }
        }
    }
}

private const val MAX_CHAIN_BYTES = 5_000_000L

/** Native file picker on the Swing thread; empty when cancelled. */
private fun pickFiles(): List<File> {
    var picked: List<File> = emptyList()
    SwingUtilities.invokeAndWait {
        val dialog = FileDialog(null as Frame?, "Choose wan_chain JSON file(s)", FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.isVisible = true // blocks until closed
        picked = dialog.files.toList()
    }
    return picked
}

/** Null when the operator cancelled the picker. */
private fun chooseAndReadChain(): OpsResult<ChainSpec>? {
    val files = pickFiles()
    if (files.isEmpty()) return null
    val texts = files.map { f ->
        if (f.length() > MAX_CHAIN_BYTES) {
            return OpsResult.Err(OpsError("${f.name} is larger than 5 MB, which is not a chain file.", kind = OpsErrorKind.BadShape))
        }
        f.readText()
    }
    return ChainSpec.fromFiles(texts)
}

@Composable
private fun SendChainCard(queue: PanelState<ThunderQueue>, controller: ActionController, actions: ThunderActions) {
    val scope = rememberCoroutineScope()
    var chain by remember { mutableStateOf<ChainSpec?>(null) }
    var pickError by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var seed by remember { mutableStateOf("") }
    var nextUp by remember { mutableStateOf(false) }

    SectionCard("Send a chain to the 14B runner") {
        Text(
            "Pick a wan_chain JSON from the prompt creator. Pick all its parts together and they are joined into one chain.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(enabled = !picking && !controller.busy, onClick = {
            scope.launch {
                picking = true
                pickError = null
                val result = withContext(Dispatchers.IO) {
                    runCatching { chooseAndReadChain() }.getOrElse { OpsResult.Err(OpsError("Could not read the file: ${it.message ?: it.javaClass.simpleName}", kind = OpsErrorKind.BadShape)) }
                }
                when (result) {
                    null -> Unit // cancelled
                    is OpsResult.Ok -> { chain = result.value; name = result.value.suggestedName }
                    is OpsResult.Err -> { chain = null; pickError = result.error.message }
                }
                picking = false
            }
        }) { Text(if (picking) "Waiting for the file picker…" else "Choose chain file(s)…") }
        pickError?.let { Note(it, Tone.Bad) }

        val c = chain
        if (c != null) {
            val seedValue = seed.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
            val seedOk = seed.isBlank() || seedValue != null
            Text("${c.clipCount} clips from ${c.sourceFiles} file${if (c.sourceFiles > 1) "s" else ""}", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                seed, { seed = it.filter { ch -> ch.isDigit() }.take(9) },
                label = { Text("Seed (optional)") }, singleLine = true, modifier = Modifier.width(220.dp), isError = !seedOk,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = nextUp, onCheckedChange = { nextUp = it })
                Text(
                    "Next up: jump ahead of queued jobs (the hub cancels them, submits this, then tries to re-queue them)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val runnerUp = (queue.state as? OpsState.Loaded)?.value?.runnerUp
            Button(enabled = seedOk && !controller.busy, onClick = {
                // Frozen at click time: what the dialog shows is exactly what run() sends.
                val jobName = name.trim()
                val position = nextUp
                controller.request(
                    ActionSpec(
                        title = "Submit chain to the 14B runner",
                        consequences = listOfNotNull(
                            "Sends ${c.clipCount} clips${if (jobName.isNotEmpty()) " as \"$jobName\"" else ""} to the Wan 14B runner on the Thunder instance.",
                            "Rendering uses GPU time on your billing Thunder instance, hour by hour, until the chain finishes.",
                            if (position) "Queue position NEXT UP: the hub first CANCELS every queued job ahead of it, submits yours, then tries to put them back. If that fails they stay cancelled, and the result will name them."
                            else "Queue position: end of the queue.",
                            if (runnerUp != true) "The queue panel does not report the 14B runner as reachable, so this will most likely fail." else null,
                            "The hub's reply is shown as it came. HTTP 200 counts as success only when the reply says ok=true.",
                        ),
                        confirmLabel = "Submit chain",
                        typedPhrase = if (position) "SUBMIT" else null,
                        run = {
                            val outcome = actions.submit(c, jobName.ifEmpty { null }, seedValue, position)
                            if (outcome is ActionOutcome.Succeeded) { chain = null; seed = ""; nextUp = false }
                            outcome
                        },
                    ),
                )
            }) { Text("Submit chain…") }
        }
    }
}
