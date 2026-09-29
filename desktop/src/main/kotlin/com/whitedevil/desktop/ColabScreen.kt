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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whitedevil.desktop.ops.ActionController
import com.whitedevil.desktop.ops.ActionDialog
import com.whitedevil.desktop.ops.ActionSpec
import com.whitedevil.desktop.ops.ColabActions
import com.whitedevil.desktop.ops.ColabApi
import com.whitedevil.desktop.ops.ColabJob
import com.whitedevil.desktop.ops.ColabPack
import com.whitedevil.desktop.ops.ColabState
import com.whitedevil.desktop.ops.EmptyLine
import com.whitedevil.desktop.ops.KeyValue
import com.whitedevil.desktop.ops.LoadOnce
import com.whitedevil.desktop.ops.MonoBlock
import com.whitedevil.desktop.ops.Note
import com.whitedevil.desktop.ops.COLAB_POLL_MS
import com.whitedevil.desktop.ops.OpsState
import com.whitedevil.desktop.ops.PanelState
import com.whitedevil.desktop.ops.PanelView
import com.whitedevil.desktop.ops.Pill
import com.whitedevil.desktop.ops.PollWhileVisible
import com.whitedevil.desktop.ops.SectionCard
import com.whitedevil.desktop.ops.Tile
import com.whitedevil.desktop.ops.TileRow
import com.whitedevil.desktop.ops.Tone
import com.whitedevil.desktop.ops.ageOfEpochSec
import com.whitedevil.desktop.ops.formatAge
import com.whitedevil.desktop.ops.formatClock
import com.whitedevil.desktop.ops.OpsScreenFrame
import com.whitedevil.desktop.ops.orUnknown
import com.whitedevil.desktop.ops.plainNumber
import com.whitedevil.desktop.ops.rememberOpsClients
import com.whitedevil.desktop.ops.yesNoUnknown
import kotlinx.coroutines.launch

/**
 * Colab: a live, BILLING GPU runtime can be behind this screen.
 *
 * Everything here is read-only (GET status, usage, session) except two controls
 * at the bottom, each behind a typed confirmation: Stop runtime (ends the
 * session) and Start / recover (launches a G4, which bills). The reads run on a
 * 15s poll while the screen is open; the controls never run from the poll, from
 * screen open, or from a refresh. Job queueing, per-job cancel/retry and the
 * runner token route are deliberately not wired here.
 */
@Composable
fun ColabScreen(settings: Settings) {
    val clients = rememberOpsClients(settings)
    val api = remember(clients) { ColabApi(clients.reader) }
    val actions = remember(clients) { ColabActions(clients.actor) }
    val scope = rememberCoroutineScope()
    val state = remember(api) { PanelState(api::state) }
    val packs = remember(api) { PanelState(api::packs) }
    // After a confirmed action ends (success or failure) re-read the state: a GET, so the
    // screen shows what the hub now says instead of what it said before the action.
    val controller = remember(state) { ActionController(onFinished = { scope.launch { state.refresh(followUp = true) } }) }

    PollWhileVisible(state, COLAB_POLL_MS)
    LoadOnce(packs)

    OpsScreenFrame(
        title = "Colab",
        subtitle = state.lastGood?.let { "Updated ${formatClock(it.atMillis)} · refreshes every ${COLAB_POLL_MS / 1000}s" }
            ?: "Read-only · refreshes every ${COLAB_POLL_MS / 1000}s",
        refreshing = state.refreshing,
        onRefresh = { scope.launch { state.refresh() }; scope.launch { packs.refresh() } },
    ) {
        Note(
            "Read-only view. Nothing on this screen changes the runtime except the two controls at the bottom, " +
                "each of which asks you to type a confirmation first.",
        )
        PanelView(state, "Colab status", onRetry = { scope.launch { state.refresh() } }) { s, stale ->
            StatusCard(s)
            SessionCard(s)
            ServicesCard(s)
            JobsCard(s)
            ControlsCard(s, stale, controller, actions)
        }
        PacksCard(packs) { scope.launch { packs.refresh() } }
    }
    ActionDialog(controller)
}

private fun tone(kind: String?): Tone = when (kind) {
    "ok" -> Tone.Ok
    "warn" -> Tone.Warn
    "bad" -> Tone.Bad
    else -> Tone.Neutral
}

@Composable
private fun StatusCard(s: ColabState) {
    val summary = s.summary
    SectionCard(
        "Status",
        trailing = { Pill(summary?.label ?: "Status unknown", tone(summary?.kind)) },
    ) {
        when (s.billing) {
            true -> Note("BILLING IS ACTIVE. A Colab GPU is assigned and charging${s.usage?.ratePerHr?.let { " (${plainNumber(it)}/h as reported)" } ?: ""}.", Tone.Warn)
            false -> Note("The hub reports no billing right now.", Tone.Neutral)
            null -> Note("Billing state unknown: the hub did not say.", Tone.Warn)
        }
        val u = s.usage
        if (s.billing != true && (u == null || !u.readable)) {
            Note(
                "The hub could not read `colab usage`, so \"no runtime\" and \"not billing\" are unverified. " +
                    "Check colab.research.google.com before assuming you are not being charged.",
                Tone.Warn,
            )
        }
        summary?.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            ?: EmptyLine("The hub sent no status detail.")

        TileRow {
            Tile("Rate / hour", plainNumber(u?.ratePerHr), "as reported by colab usage", Modifier.weight(1f))
            Tile("Balance", plainNumber(u?.balance), "as reported by colab usage", Modifier.weight(1f))
            Tile(
                "Active assignments", orUnknown(u?.activeAssignments), null, Modifier.weight(1f),
                tone = if ((u?.activeAssignments ?: 0) > 0) Tone.Warn else Tone.Neutral,
            )
            val age = ageOfEpochSec(u?.checkedAtEpochSec)
            Tile(
                "Usage read", if (age == null) "never / unknown" else "${formatAge(age)} ago",
                "the hub caches usage for 5 min", Modifier.weight(1f),
                tone = if (age != null && age > 600) Tone.Warn else Tone.Neutral,
            )
        }
        s.paused?.let { Text("Marked stopped by the hub: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun SessionCard(s: ColabState) {
    val i = s.instance
    SectionCard("Session") {
        if (i == null) {
            EmptyLine("The hub sent no session information.")
        } else {
            KeyValue("Name", i.name ?: "unknown")
            KeyValue("Endpoint", i.endpoint ?: "none", mono = true)
            KeyValue("Accelerator", i.accelerator ?: s.gpu ?: "unknown")
            KeyValue("Session status", i.status ?: "unknown")
            KeyValue("Running", yesNoUnknown(i.running))
            i.rawCliOutput?.let {
                Text("Last `colab status` output", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MonoBlock(it)
            }
        }
        if (i == null || i.accelerator == null) s.gpu?.let { KeyValue("GPU", it) }
    }
}

@Composable
private fun ServicesCard(s: ColabState) {
    SectionCard("Services") {
        KeyValue("wanbot runner", when (s.runnerOnline) { true -> "online"; false -> "offline"; null -> "unknown" } + (s.runnerMode?.let { " · mode $it" } ?: ""))
        KeyValue("ComfyUI tunnel", when (s.comfyOnline) { true -> "online"; false -> "offline"; null -> "unknown" })
        KeyValue("Recovery script", when (s.recoverRunning) { true -> "running now"; false -> "not running"; null -> "unknown" })
        if (s.resumePacks.isNotEmpty()) KeyValue("Resume packs", s.resumePacks.joinToString(", "))
        if (s.heartbeat.isNotEmpty() || s.heartbeatAtEpochSec != null) {
            val age = ageOfEpochSec(s.heartbeatAtEpochSec)
            KeyValue(
                "Heartbeat",
                (s.heartbeat.entries.joinToString(" · ") { "${it.key}=${it.value}" }.ifBlank { "(no fields)" }) +
                    (age?.let { " · ${formatAge(it)} ago" } ?: ""),
            )
        }
        if (s.recoverLog.isNotEmpty()) {
            Text("Recovery log (last lines)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MonoBlock(s.recoverLog.joinToString("\n"))
        }
    }
}

@Composable
private fun JobsCard(s: ColabState) {
    SectionCard("Render jobs", trailing = { Text("${s.jobs.size}", style = MaterialTheme.typography.labelMedium) }) {
        when {
            s.runnerOnline != true && s.jobs.isEmpty() ->
                Note(
                    "The wanbot runner is ${if (s.runnerOnline == false) "not reachable from the hub" else "in an unknown state"}, " +
                        "so the job list is unavailable. An empty list here does NOT mean there are no jobs.",
                    Tone.Warn,
                )
            s.jobs.isEmpty() -> EmptyLine("The runner is up and reports no jobs.")
            else -> s.jobs.forEach { JobRow(it) }
        }
    }
}

@Composable
private fun JobRow(j: ColabJob) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(j.name ?: j.id ?: "(unnamed job)", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(10.dp))
            Pill(j.status ?: "unknown", when (j.status) { "rendering" -> Tone.Ok; "error" -> Tone.Bad; "waiting" -> Tone.Warn; else -> Tone.Neutral })
        }
        val bits = listOfNotNull(
            j.progress?.let { "progress $it" },
            j.currentClip?.let { "on clip $it" },
            j.avgSeconds?.let { "~$it s per clip" },
            j.id?.let { "id $it" },
        )
        if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        j.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ControlsCard(s: ColabState, stale: Boolean, controller: ActionController, actions: ColabActions) {
    SectionCard("Runtime control") {
        Text(
            "These two buttons are the only controls here. Each opens a confirmation that names what it does and what it costs, " +
                "and needs you to type a word before it can be sent. The hub's own answer is shown afterwards, including when it says no.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (stale) Note("Controls are disabled while the data above is stale.", Tone.Warn)
        val inFlight = s.jobs.count { it.status == "rendering" || it.status == "queued" || it.status == "waiting" }
        val rate = s.usage?.ratePerHr?.let { "${plainNumber(it)}/h as reported" } ?: "an unknown rate"
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !stale && !controller.busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                onClick = {
                    controller.request(
                        ActionSpec(
                            title = "Stop the Colab runtime",
                            consequences = listOfNotNull(
                                "Runs `colab stop` on the hub and ends the GPU session${s.instance?.endpoint?.let { " ($it)" } ?: ""}.",
                                "Billing stops only if Colab then shows no active assignment. The hub re-checks, and the result will say plainly if you are still being billed.",
                                if (inFlight > 0) "$inFlight render job(s) are queued or running and will be lost." else "No render jobs are reported as running.",
                                "The hub may wait on a lock, so this can take several minutes. Do not press it twice.",
                            ),
                            confirmLabel = "Stop runtime",
                            typedPhrase = "STOP",
                            danger = true,
                            run = { actions.stopRuntime() },
                        ),
                    )
                },
            ) { Text("Stop runtime…") }
            OutlinedButton(
                enabled = !stale && !controller.busy,
                onClick = {
                    controller.request(
                        ActionSpec(
                            title = "Start / recover the Colab runtime",
                            consequences = listOfNotNull(
                                "Clears the hub's \"paused\" markers and launches the recovery script in the background, which starts a Colab G4 runtime.",
                                "A running GPU BILLS ($rate) until you stop it.",
                                "Also restarts the Comfy tunnel service on the hub.",
                                if (s.billing == true) "Colab already shows an active, billing assignment right now. Starting again is probably not what you want." else null,
                                "The reply only says the script was launched. It does not say a GPU is up: watch Status.",
                            ),
                            confirmLabel = "Start / recover",
                            typedPhrase = "START",
                            danger = true,
                            run = { actions.recover() },
                        ),
                    )
                },
            ) { Text("Start / recover…") }
        }
    }
}

@Composable
private fun PacksCard(packs: PanelState<List<ColabPack>>, onRetry: () -> Unit) {
    SectionCard("Packs") {
        when (val s = packs.state) {
            is OpsState.Loading -> EmptyLine("Loading packs…")
            is OpsState.Error -> {
                Note("Could not load packs: ${s.message}", Tone.Bad)
                OutlinedButton(onClick = onRetry, enabled = !packs.refreshing) { Text("Retry") }
            }
            is OpsState.Loaded -> if (s.value.isEmpty()) {
                EmptyLine("No packs listed. The hub also returns an empty list when its pack file is missing, so this is not proof there are none.")
            } else {
                s.value.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${p.index?.let { "%02d".format(it) } ?: "??"}  ${p.title ?: "(untitled)"}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${orUnknown(p.rendered)} of ${orUnknown(p.clips)} clips rendered",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
