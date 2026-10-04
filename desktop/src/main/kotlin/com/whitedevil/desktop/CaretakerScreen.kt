package com.whitedevil.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.whitedevil.desktop.ops.CaretakerActions
import com.whitedevil.desktop.ops.CaretakerApi
import com.whitedevil.desktop.ops.CaretakerEvent
import com.whitedevil.desktop.ops.CaretakerState
import com.whitedevil.desktop.ops.EmptyLine
import com.whitedevil.desktop.ops.KeyValue
import com.whitedevil.desktop.ops.LoadOnce
import com.whitedevil.desktop.ops.Note
import com.whitedevil.desktop.ops.OpsResult
import com.whitedevil.desktop.ops.OpsScreenFrame
import com.whitedevil.desktop.ops.PanelState
import com.whitedevil.desktop.ops.PanelView
import com.whitedevil.desktop.ops.PollWhileVisible
import com.whitedevil.desktop.ops.Pill
import com.whitedevil.desktop.ops.SectionCard
import com.whitedevil.desktop.ops.Tile
import com.whitedevil.desktop.ops.TileRow
import com.whitedevil.desktop.ops.Tone
import com.whitedevil.desktop.ops.formatClock
import com.whitedevil.desktop.ops.rememberOpsClients
import kotlinx.coroutines.launch

/**
 * The caretaker: the relay's resident maintenance model, shown natively (the phone shows the same page
 * from the relay; this app has no web view). Status refreshes every 15 s while the screen is open; the
 * activity log loads on open and on Refresh. Pause/Resume asks for confirmation first. The chat is read-only.
 */
@Composable
fun CaretakerScreen(settings: Settings) {
    val clients = rememberOpsClients(settings)
    val api = remember(clients) { CaretakerApi(clients.reader) }
    val actions = remember(clients) { CaretakerActions(clients.actor) }
    val scope = rememberCoroutineScope()
    val state = remember(api) { PanelState(api::state) }
    val audit = remember(api) { PanelState(api::audit) }
    val controller = remember(state) {
        ActionController(onFinished = { scope.launch { state.refresh(followUp = true); audit.refresh(followUp = true) } })
    }

    PollWhileVisible(state)
    LoadOnce(audit)

    OpsScreenFrame(
        title = "Caretaker",
        subtitle = state.lastGood?.let { "Updated ${formatClock(it.atMillis)} · refreshes every 15 s" } ?: "Refreshes every 15 s",
        refreshing = state.refreshing,
        onRefresh = { scope.launch { state.refresh(); audit.refresh() } },
    ) {
        Note(
            "The caretaker is a small model that lives on the relay and watches it: disk, memory, the hub, upgrades, backups and " +
                "failed services. It only ever runs a fixed list of actions, and it waits whenever renders or GPU boxes are busy.",
        )
        PanelView(state, "Caretaker status", onRetry = { scope.launch { state.refresh() } }) { s, stale ->
            StatusCard(s)
            FactsCard(s)
            ControlsCard(s, stale, controller, actions)
        }
        PanelView(audit, "Caretaker activity", onRetry = { scope.launch { audit.refresh() } }) { rows, _ ->
            ActivityCard(rows)
        }
        AskCard(actions)
    }
    ActionDialog(controller)
}

@Composable
private fun StatusCard(s: CaretakerState) {
    val (modeText, modeTone) = when {
        s.paused -> "PAUSED" to Tone.Warn
        s.live -> "LIVE" to Tone.Bad
        else -> "DRY-RUN" to Tone.Neutral
    }
    SectionCard("Status", trailing = { Pill(modeText, modeTone) }) {
        if (s.anomalies.isEmpty()) Pill("All clear", Tone.Ok)
        else s.anomalies.forEach { Note(it, Tone.Warn) }
        if (s.inFlight.isNotEmpty()) KeyValue("Work in flight", s.inFlight.joinToString("\n"))
        Note(
            when {
                s.paused -> "Paused: it will not act until you resume it."
                s.live -> "Live: it may restart services, run upgrades and reboot the relay by itself, but never while work is in flight."
                else -> "Dry-run: it only logs what it would do. Nothing is changed on the relay."
            },
        )
    }
}

@Composable
private fun FactsCard(s: CaretakerState) {
    SectionCard("The relay right now") {
        TileRow {
            Tile("Disk", s.diskUsedPct?.let { "${"%.0f".format(it)}%" } ?: "?", tone = if ((s.diskUsedPct ?: 0.0) > 80) Tone.Warn else Tone.Neutral, modifier = Modifier.weight(1f))
            Tile("Free memory", s.memAvailableMb?.let { "$it MB" } ?: "?", modifier = Modifier.weight(1f))
            Tile("Hub", if (s.hubHealthy) "healthy" else "${s.hubService ?: "?"} / ${s.hubHttp ?: "?"}", tone = if (s.hubHealthy) Tone.Ok else Tone.Bad, modifier = Modifier.weight(1f))
        }
        TileRow {
            Tile("Upgrades pending", s.aptUpgradable ?: "?", tone = if ((s.aptUpgradable?.toIntOrNull() ?: 0) > 0) Tone.Warn else Tone.Neutral, modifier = Modifier.weight(1f))
            Tile("Last backup", s.lastBackupAgeHours?.let { "${"%.1f".format(it)} h ago" } ?: "none", tone = if ((s.lastBackupAgeHours ?: 99.0) > 26) Tone.Warn else Tone.Neutral, modifier = Modifier.weight(1f))
            Tile("Reboot needed", if (s.rebootRequired) "yes" else "no", tone = if (s.rebootRequired) Tone.Warn else Tone.Neutral, modifier = Modifier.weight(1f))
        }
        s.kernel?.let { KeyValue("Kernel", it, mono = true) }
        if (s.failedUnits.isNotEmpty()) KeyValue("Failed services", s.failedUnits.joinToString("\n"), mono = true)
        s.factsTime?.let { KeyValue("Read at", it, mono = true) }
    }
}

@Composable
private fun ControlsCard(s: CaretakerState, stale: Boolean, controller: ActionController, actions: CaretakerActions) {
    SectionCard("Controls") {
        val spec = if (s.paused) {
            ActionSpec(
                title = "Resume the caretaker",
                consequences = listOf(
                    if (s.live) "It will start acting on its own again (live mode)." else "It is in dry-run, so it will only log what it would do.",
                ),
                confirmLabel = "Resume",
                danger = s.live,
                run = { actions.pause(false) },
            )
        } else {
            ActionSpec(
                title = "Pause the caretaker",
                consequences = listOf("It stops all actions until you resume it. Monitoring and this page keep working."),
                confirmLabel = "Pause",
                danger = false,
                run = { actions.pause(true) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { controller.request(spec) }, enabled = !stale) { Text(if (s.paused) "Resume" else "Pause") }
        }
    }
}

@Composable
private fun ActivityCard(rows: List<CaretakerEvent>) {
    SectionCard("Activity") {
        if (rows.isEmpty()) EmptyLine("Nothing logged yet.")
        rows.take(30).forEach { e ->
            val stamp = e.time.replace('T', ' ').take(16)
            Text(
                "$stamp  ${e.event}${if (e.detail.isNotBlank()) "  ${e.detail}" else ""}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AskCard(actions: CaretakerActions) {
    val scope = rememberCoroutineScope()
    val log = remember { mutableStateListOf<Pair<String, String>>() }
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }

    fun ask() {
        val q = input.trim()
        if (q.isEmpty() || thinking) return
        input = ""
        val history = log.toList()
        log += "user" to q
        thinking = true
        scope.launch {
            val answer = when (val r = actions.ask(q, history)) {
                is OpsResult.Ok -> r.value
                is OpsResult.Err -> "Could not get an answer: ${r.error.message}"
            }
            log += "assistant" to answer
            thinking = false
        }
    }

    SectionCard("Ask the caretaker") {
        Note("Read-only: it can explain what it sees, it cannot change anything. The first answer after a quiet spell takes up to a minute while its model loads; small models also get things wrong, so check the numbers above.")
        log.forEach { (role, text) ->
            Text(
                (if (role == "user") "You: " else "Caretaker: ") + text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (role == "user") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (thinking) Text("Thinking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), singleLine = true,
                label = { Text("Ask about the relay") },
            )
            Button(onClick = ::ask, enabled = input.isNotBlank() && !thinking) { Text("Ask") }
        }
    }
}
