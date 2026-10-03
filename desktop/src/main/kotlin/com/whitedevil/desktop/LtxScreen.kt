package com.whitedevil.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.whitedevil.desktop.ops.ActionSpec
import com.whitedevil.desktop.ops.EmptyLine
import com.whitedevil.desktop.ops.KeyValue
import com.whitedevil.desktop.ops.LtxActions
import com.whitedevil.desktop.ops.LtxApi
import com.whitedevil.desktop.ops.LtxCycle
import com.whitedevil.desktop.ops.LtxCycleCost
import com.whitedevil.desktop.ops.LtxJob
import com.whitedevil.desktop.ops.LtxStatus
import com.whitedevil.desktop.ops.MonoBlock
import com.whitedevil.desktop.ops.Note
import com.whitedevil.desktop.ops.OPS_POLL_MS
import com.whitedevil.desktop.ops.OpsScreenFrame
import com.whitedevil.desktop.ops.OpsState
import com.whitedevil.desktop.ops.PanelState
import com.whitedevil.desktop.ops.PanelView
import com.whitedevil.desktop.ops.Picker
import com.whitedevil.desktop.ops.Pill
import com.whitedevil.desktop.ops.PollWhileVisible
import com.whitedevil.desktop.ops.SectionCard
import com.whitedevil.desktop.ops.Tile
import com.whitedevil.desktop.ops.TileRow
import com.whitedevil.desktop.ops.Tone
import com.whitedevil.desktop.ops.ageOfEpochSec
import com.whitedevil.desktop.ops.formatAge
import com.whitedevil.desktop.ops.formatClock
import com.whitedevil.desktop.ops.orUnknown
import com.whitedevil.desktop.ops.rememberOpsClients
import com.whitedevil.desktop.ops.yesNoUnknown
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

/** Status asks ComfyUI four times and can shell out to the Colab CLI on the hub, so it is read less often than jobs. */
private const val LTX_STATUS_POLL_MS = 60_000L

/**
 * LTX: image-to-video on the Colab GPU, and a render-review cycle that runs on the hub.
 *
 * Read-only: the ComfyUI status, the newest 30 jobs, and the cycle. Three controls, each behind the
 * confirmation dialog: Cancel on a queued or rendering job, and Start / Stop for the cycle. Starting a
 * cycle spends GPU time and OpenRouter credit and needs a typed word. The reads run on a poll while the
 * screen is open; the controls never run from the poll, from opening the screen, or from a refresh.
 *
 * Submitting a clip, a chain or a 2x sharpen, installing models and the prompt assistant are NOT here:
 * they stay on the web LTX screen (see the header of ops/LtxModels.kt for why). Job input pictures are
 * not shown either.
 */
@Composable
fun LtxScreen(settings: Settings) {
    val clients = rememberOpsClients(settings)
    val api = remember(clients) { LtxApi(clients.reader) }
    val actions = remember(clients) { LtxActions(clients.actor) }
    val scope = rememberCoroutineScope()
    val status = remember(api) { PanelState(api::status) }
    val jobs = remember(api) { PanelState(api::jobs) }
    val cycle = remember(api) { PanelState(api::cycle) }
    // After a confirmed action ends (success or failure) re-read jobs and the cycle: GETs, so the screen
    // shows what the hub now says instead of what it said before the action.
    val controller = remember(jobs, cycle) {
        ActionController(
            onFinished = {
                scope.launch { jobs.refresh(followUp = true) }
                scope.launch { cycle.refresh(followUp = true) }
            },
        )
    }

    PollWhileVisible(status, LTX_STATUS_POLL_MS)
    PollWhileVisible(jobs, OPS_POLL_MS)
    PollWhileVisible(cycle, OPS_POLL_MS)

    OpsScreenFrame(
        title = "LTX",
        subtitle = jobs.lastGood?.let { "Jobs updated ${formatClock(it.atMillis)} · refreshes every ${OPS_POLL_MS / 1000}s" }
            ?: "Read-only · refreshes every ${OPS_POLL_MS / 1000}s",
        refreshing = status.refreshing || jobs.refreshing || cycle.refreshing,
        onRefresh = {
            scope.launch { status.refresh() }
            scope.launch { jobs.refresh() }
            scope.launch { cycle.refresh() }
        },
    ) {
        BuilderCard(settings)
        Note(
            "Status, jobs and the cycle are read-only. The only controls here are Cancel on a job and Start / Stop for the cycle, " +
                "each of which asks you to confirm first. Rendering a clip or a chain, 2× sharpening, installing models and the " +
                "prompt assistant are not on this screen: use the LTX screen on the web for those. Input pictures are not shown here.",
        )
        PanelView(status, "LTX status", onRetry = { scope.launch { status.refresh() } }) { s, _ -> StatusCard(s) }
        PanelView(cycle, "the LTX cycle", onRetry = { scope.launch { cycle.refresh() } }) { c, stale ->
            CycleCard(c, stale, status, jobs, controller, actions)
        }
        PanelView(jobs, "LTX jobs", onRetry = { scope.launch { jobs.refresh() } }) { list, stale ->
            JobsCard(list, stale, controller, actions)
        }
    }
    ActionDialog(controller)
}

@Composable
private fun StatusCard(s: LtxStatus) {
    SectionCard(
        "ComfyUI on Colab",
        trailing = {
            Pill(
                when (s.online) { true -> "LTX ready"; false -> "Not ready"; null -> "Unknown" },
                when (s.online) { true -> Tone.Ok; false -> Tone.Warn; null -> Tone.Neutral },
            )
        },
    ) {
        s.detail?.let { Note(it, if (s.online == true) Tone.Neutral else Tone.Warn) }
        if (s.detail == null && s.online == false) EmptyLine("The hub reports LTX not ready and gave no reason.")
        // The hub sends billing:true whenever ComfyUI answers, and the real billing check when it does not.
        when (s.billing) {
            true -> Note("The hub reports the Colab GPU as billing.", Tone.Warn)
            false -> Text("The hub reports no billing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            null -> Unit
        }
        TileRow {
            Tile("ComfyUI queue", orUnknown(s.queueBusy), "running + pending", Modifier.weight(1f), tone = if ((s.queueBusy ?: 0) > 0) Tone.Warn else Tone.Neutral)
            Tile("LTX models", orUnknown(s.transformers?.size), null, Modifier.weight(1f))
            Tile("LoRAs", orUnknown(s.loras?.size), null, Modifier.weight(1f))
            Tile("Distill LoRA", yesNoUnknown(s.distilled), null, Modifier.weight(1f))
        }
        if (s.decoderQuality != null || s.decoderFast != null) {
            KeyValue("Decoders", "quality ${yesNoUnknown(s.decoderQuality)} · fast ${yesNoUnknown(s.decoderFast)}")
        }
        if (!s.transformers.isNullOrEmpty()) KeyValue("Models", s.transformers.joinToString(", "), mono = true)
        if (s.installs.isNotEmpty()) {
            Text("Installs the hub knows about", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.installs.forEach { i ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(i.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Pill(
                        i.state ?: "unknown",
                        when (i.state) { "done" -> Tone.Ok; "failed" -> Tone.Bad; "downloading" -> Tone.Warn; else -> Tone.Neutral },
                    )
                }
                val bits = listOfNotNull(i.kind, i.sizeMb?.let { "%.0f MB".format(java.util.Locale.ROOT, it) }, i.detail)
                if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val CYCLE_DEFAULT_ROUNDS = 2

@Composable
private fun CycleCard(
    c: LtxCycle,
    stale: Boolean,
    status: PanelState<LtxStatus>,
    jobs: PanelState<List<LtxJob>>,
    controller: ActionController,
    actions: LtxActions,
) {
    var rounds by remember { mutableStateOf(CYCLE_DEFAULT_ROUNDS) }
    var sourceId by remember { mutableStateOf<String?>(null) }

    // Only a fresh job list can name a source: a stale one may hold a job the hub no longer has.
    val jobList = (jobs.state as? OpsState.Loaded)?.value
    val sources = jobList.orEmpty().filter { it.usableAsCycleSource }
    val source = sources.firstOrNull { it.id == sourceId }
    val comfyOnline = (status.state as? OpsState.Loaded)?.value?.online

    SectionCard(
        "Render → assess → adjust cycle",
        trailing = { Pill(if (c.running) "Running" else "Not running", if (c.running) Tone.Warn else Tone.Neutral) },
    ) {
        if (c.running) {
            Note("A cycle process is running on the hub${c.pid?.let { " (pid $it)" } ?: ""}. Its chains render on the Colab GPU and bill while they run.", Tone.Warn)
            Note(
                "The details below come from the script's state file, which it writes after each render, so until the first render " +
                    "finishes they can still describe the previous run.",
            )
        }
        KeyValue(if (c.running) "Source job" else "Source job (last run)", c.src ?: "none recorded", mono = true)
        KeyValue("Rounds recorded", c.historyLen?.toString() ?: "unknown")
        KeyValue("Kept", if (c.kept.isEmpty()) "nothing kept" else c.kept.joinToString(", "), mono = true)
        c.last?.let { last ->
            val bits = listOfNotNull(
                last.round?.let { "round $it" },
                last.stack?.let { "recipe $it" },
                last.jobId?.let { "job $it" },
                last.verdict ?: last.status,
            )
            KeyValue("Last result", bits.joinToString(" · ").ifBlank { "recorded, no fields" })
            last.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            last.adjustNote?.let { KeyValue("Adjustment", it.take(300)) }
            last.report?.let {
                Text("Review of the last clip", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MonoBlock(it.take(700) + if (it.length > 700) "\n… (${it.length - 700} more characters not shown)" else "")
            }
        }
        if (c.logTail.isNotBlank()) {
            Text("Log (last lines)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MonoBlock(c.logTail.trimEnd())
        }

        Text(
            "Start and Stop are the only cycle controls. Start needs a source job and a typed word; the hub's own answer is shown afterwards, including when it says no.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (stale) Note("Controls are disabled while the data above is stale.", Tone.Warn)
        if (jobList == null) {
            Note("Starting needs the current job list to name a source, and that list is not loaded right now.", Tone.Warn)
        } else if (sources.isEmpty()) {
            Note(
                "No job in the newest 30 can be a cycle source. It has to be a chain made from a picture, with one prompt line per part. " +
                    "Start the cycle from the web LTX screen if the source is older.",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Picker(
                "Source job",
                sources.map(::sourceLabel),
                source?.let(::sourceLabel) ?: "choose",
                onSelect = { label -> sourceId = sources.firstOrNull { sourceLabel(it) == label }?.id },
                enabled = !stale && !controller.busy && sources.isNotEmpty(),
            )
            Picker(
                "Rounds",
                (LtxActions.MIN_ROUNDS..LtxActions.MAX_ROUNDS).map { it.toString() },
                rounds.toString(),
                onSelect = { rounds = it.toInt() },
                enabled = !stale && !controller.busy,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !stale && !controller.busy && source?.id != null,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                onClick = {
                    val src = source ?: return@Button
                    val id = src.id ?: return@Button
                    val cost = LtxCycleCost.of(rounds, src)
                    val active = jobList.orEmpty().count { it.active }
                    controller.request(
                        ActionSpec(
                            title = "Start the LTX cycle",
                            consequences = listOfNotNull(
                                "Starts a background process on the hub: it renders chains on the Colab GPU, has a vision model on OpenRouter review each result, adjusts the settings and repeats.",
                                "Source ${sourceLabel(src)}, ${cost.clipsPerChain} parts per chain. Worst case for $rounds round(s): ${cost.chains} chains (${LtxCycleCost.RECIPES_PER_ROUND} recipes a round), " +
                                    "${cost.clips} clips of ${orUnknown(cost.frames)} frames at ${cost.size ?: "an unknown size"}. It stops after the first round that yields a KEEP.",
                                "Every clip BILLS on the Colab GPU while it renders, and every review spends OpenRouter credit.",
                                if (c.running) "A cycle is already running${c.pid?.let { " (pid $it)" } ?: ""}. Starting a new one stops it first, and the chain it already submitted keeps running." else null,
                                if (active > 0) "$active job(s) are already queued or rendering." else null,
                                if (comfyOnline == false) "The hub reports ComfyUI on Colab as not ready; the cycle waits for it before rendering." else null,
                                "The reply only says the process was launched, not that a render started: watch Jobs.",
                            ),
                            confirmLabel = "Start cycle",
                            typedPhrase = "START",
                            danger = true,
                            run = { actions.startCycle(rounds, id) },
                        ),
                    )
                },
            ) { Text("Start cycle…") }
            OutlinedButton(
                enabled = !stale && !controller.busy && c.running,
                onClick = {
                    controller.request(
                        ActionSpec(
                            title = "Stop the LTX cycle",
                            consequences = listOf(
                                "Signals the cycle process on the hub${c.pid?.let { " (pid $it)" } ?: ""} to stop.",
                                "A chain the cycle already submitted is NOT cancelled by this. It keeps rendering on the Colab GPU, and billing, until it finishes: cancel it under Jobs if you do not want it.",
                                "Nothing a finished round already recorded is removed.",
                            ),
                            confirmLabel = "Stop cycle",
                            danger = true,
                            run = { actions.stopCycle() },
                        ),
                    )
                },
            ) { Text("Stop cycle…") }
        }
    }
}

private fun sourceLabel(j: LtxJob): String = "${(j.name ?: "unnamed").take(28)} · ${j.id}"

@Composable
private fun JobsCard(list: List<LtxJob>, stale: Boolean, controller: ActionController, actions: LtxActions) {
    SectionCard("Jobs", trailing = { Text("${list.size}", style = MaterialTheme.typography.labelMedium) }) {
        if (list.isEmpty()) {
            EmptyLine("The hub answered and lists no LTX jobs.")
        } else {
            EmptyLine("Newest ${list.size} first. The hub lists at most 30.")
            list.forEach { JobRow(it, stale, controller, actions) }
        }
    }
}

@Composable
private fun JobRow(j: LtxJob, stale: Boolean, controller: ActionController, actions: LtxActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(j.name ?: j.id ?: "(unnamed job)", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(10.dp))
            Pill(
                j.status ?: "unknown",
                when (j.status) { "done" -> Tone.Ok; "failed" -> Tone.Bad; "rendering" -> Tone.Warn; else -> Tone.Neutral },
            )
            Spacer(Modifier.width(8.dp))
            Text(j.kindLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (j.active && j.hasValidId) {
                OutlinedButton(
                    enabled = !stale && !controller.busy,
                    onClick = { controller.request(cancelSpec(j, actions)) },
                ) { Text("Cancel…") }
            }
        }
        val age = ageOfEpochSec(j.createdEpochSec)
        val bits = listOfNotNull(
            j.step?.takeIf { j.active },
            j.frames?.let { "$it frames" },
            j.size,
            if (j.kind == "chain") j.partCount?.let { "$it parts" } else null,
            j.seconds?.let { "took ${formatAge(it.toLong())}" },
            age?.let { "started ${formatAge(it)} ago" },
            j.id?.let { "id $it" },
        )
        if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        j.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

private fun cancelSpec(j: LtxJob, actions: LtxActions): ActionSpec {
    val id = requireNotNull(j.id)
    return ActionSpec(
        title = "Cancel the LTX job",
        consequences = listOfNotNull(
            "Marks ${j.name ?: id} ($id) as failed with the reason \"Cancelled\" on the hub, and asks ComfyUI on Colab to interrupt or drop its queued prompt.",
            if (j.kind == "sharpen") "A 2× sharpen job holds no ComfyUI prompt id, so the hub can only mark it cancelled: ComfyUI may finish the upscale it already started." else null,
            if (j.kind == "chain") "For a chain, only the part currently queued or rendering is removed from ComfyUI. The chain is marked failed and does not continue." else null,
            "This does not undo a cycle that submitted the job: use Stop cycle for that.",
        ),
        confirmLabel = "Cancel job",
        danger = true,
        run = { actions.cancelJob(id) },
    )
}

/**
 * The place to actually build a render (picture, prompt, length, Render, New render) is the LTX web
 * page on the hub. This opens it in the default browser; the browser asks for the relay login the
 * first time and remembers it.
 */
@Composable
private fun BuilderCard(settings: Settings) {
    var error by remember { mutableStateOf<String?>(null) }
    val url = settings.hubUrl.trim().trimEnd('/') + "/app/ltx/"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    error = null
                    runCatching { Desktop.getDesktop().browse(URI(url)) }.onFailure { error = "Couldn't open the browser: ${it.message}" }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Forge.Acc2, contentColor = androidx.compose.ui.graphics.Color.White),
            ) { Text("Open the LTX builder") }
            Text(url, color = Forge.Dim, style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = Forge.Bad, style = MaterialTheme.typography.bodySmall) }
    }
}
