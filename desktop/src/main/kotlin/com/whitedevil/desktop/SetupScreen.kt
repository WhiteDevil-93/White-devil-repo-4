package com.whitedevil.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.whitedevil.desktop.ops.LoadOnce
import com.whitedevil.desktop.ops.Note
import com.whitedevil.desktop.ops.OpsScreenFrame
import com.whitedevil.desktop.ops.PanelState
import com.whitedevil.desktop.ops.PanelView
import com.whitedevil.desktop.ops.Pill
import com.whitedevil.desktop.ops.SectionCard
import com.whitedevil.desktop.ops.SetupActions
import com.whitedevil.desktop.ops.SetupApi
import com.whitedevil.desktop.ops.SetupPhase
import com.whitedevil.desktop.ops.SetupState
import com.whitedevil.desktop.ops.Tile
import com.whitedevil.desktop.ops.TileRow
import com.whitedevil.desktop.ops.Tone
import com.whitedevil.desktop.ops.formatClock
import com.whitedevil.desktop.ops.orUnknown
import com.whitedevil.desktop.ops.rememberOpsClients
import kotlinx.coroutines.launch

/**
 * Setup: the LTX 2.5 LoRA pack (GET /api/setup).
 *
 * The hub reports `enabled` = 0 until the first save, by design, so this screen shows "not yet
 * saved" as its own state and keeps showing the catalog values that ARE present; it never reads
 * that 0 as "nothing configured". Saving is a write to the hub (POST /api/setup), so it goes
 * through a confirmation, and it only counts as saved if the hub's own reply says saved and lists
 * exactly the LoRAs you chose. Nothing here polls or writes on its own.
 */
@Composable
fun SetupScreen(settings: Settings) {
    val clients = rememberOpsClients(settings)
    val api = remember(clients) { SetupApi(clients.reader) }
    val actions = remember(clients) { SetupActions(clients.actor) }
    val scope = rememberCoroutineScope()
    val state = remember(api) { PanelState(api::state) }
    // A save adopts the state the hub returns in its reply, so no extra read is fired afterwards.
    val controller = remember(state) { ActionController() }

    LoadOnce(state)

    OpsScreenFrame(
        title = "Setup",
        subtitle = state.lastGood?.let { "LTX 2.5 LoRA pack · read ${formatClock(it.atMillis)}" } ?: "LTX 2.5 LoRA pack",
        refreshing = state.refreshing,
        onRefresh = { scope.launch { state.refresh() } },
    ) {
        PanelView(state, "the LoRA setup", onRetry = { scope.launch { state.refresh() } }) { s, stale ->
            PhaseCard(s)
            LorasCard(s, stale, state, controller, actions)
        }
    }
    ActionDialog(controller)
}

@Composable
private fun PhaseCard(s: SetupState) {
    val phase = s.phase
    SectionCard(
        "Saved state",
        trailing = {
            when (phase) {
                is SetupPhase.NotYetSaved -> Pill("Not yet saved", Tone.Warn)
                is SetupPhase.Saved -> Pill("Saved · ${orUnknown(phase.enabled)} of ${orUnknown(phase.of)} enabled", Tone.Ok)
            }
        },
    ) {
        when (phase) {
            is SetupPhase.NotYetSaved -> Note(
                "The hub has no saved LoRA selection yet. That is the normal state before the first save: the hub " +
                    "reports 0 enabled until you save, so 0 does not mean nothing is configured. The catalog below is real.",
            )
            is SetupPhase.Saved -> Note("The hub has a saved selection: ${orUnknown(phase.enabled)} of ${orUnknown(phase.of)} LoRAs enabled.", Tone.Ok)
        }
        TileRow {
            Tile("In the catalog", orUnknown(s.count ?: s.loras.size.takeIf { it > 0 }), null, Modifier.weight(1f))
            Tile("Lightricks", orUnknown(s.lightricks), "IC / control", Modifier.weight(1f))
            Tile("NSFW content", orUnknown(s.nsfw), null, Modifier.weight(1f))
            Tile(
                "Enabled",
                when (phase) {
                    is SetupPhase.NotYetSaved -> "not yet saved"
                    is SetupPhase.Saved -> orUnknown(phase.enabled)
                },
                null, Modifier.weight(1f),
                tone = if (phase is SetupPhase.Saved) Tone.Ok else Tone.Neutral,
            )
        }
        s.target?.let { KeyValue("Target", it) }
        s.dest?.let { KeyValue("Downloads go to", it, mono = true) }
    }
}

@Composable
private fun LorasCard(s: SetupState, stale: Boolean, panel: PanelState<SetupState>, controller: ActionController, actions: SetupActions) {
    val phase = s.phase
    // Saved: mirror what the hub has. Not yet saved: everything pre-ticked as a proposal (nothing is written until Save).
    var selection by remember(s) {
        mutableStateOf(if (phase is SetupPhase.Saved) s.enabledIds else s.loras.map { it.id }.toSet())
    }

    SectionCard("LoRAs") {
        if (s.loras.isEmpty()) {
            EmptyLine("The hub sent no LoRA list, so there is nothing to choose here. The counts above are as the hub reported them.")
            return@SectionCard
        }
        Text(
            if (phase is SetupPhase.NotYetSaved) "Pre-ticked as a proposal. Nothing is written until you save."
            else "Ticks show what the hub has saved, plus any change you make here.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val groups = s.loras.groupBy { it.pack ?: "other" }
        val order = listOf("lightricks", "nsfw") + groups.keys.filter { it != "lightricks" && it != "nsfw" }
        order.filter { it in groups }.forEach { pack ->
            Text(
                when (pack) { "lightricks" -> "Lightricks"; "nsfw" -> "NSFW content"; else -> pack.replaceFirstChar(Char::uppercase) },
                style = MaterialTheme.typography.labelMedium,
            )
            groups.getValue(pack).forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = l.id in selection,
                        enabled = !stale && !controller.busy,
                        onCheckedChange = { on -> selection = if (on) selection + l.id else selection - l.id },
                    )
                    Column {
                        Text(l.name ?: l.id, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(l.kind, l.filename).joinToString(" · ").ifBlank { l.id },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        val all = s.loras.map { it.id }.toSet()
        val changes = if (phase is SetupPhase.Saved) (selection - s.enabledIds).size + (s.enabledIds - selection).size else null
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !stale && !controller.busy, onClick = { selection = all }) { Text("Select all") }
            TextButton(enabled = !stale && !controller.busy, onClick = { selection = emptySet() }) { Text("Select none") }
            Spacer(Modifier.width(12.dp))
            Text(
                "${selection.size} of ${all.size} selected" + (changes?.let { " · $it unsaved change${if (it == 1) "" else "s"}" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selection.isEmpty()) {
            Note("Select at least one LoRA. The hub treats an empty selection as \"all\", so this screen will not send one.", Tone.Warn)
        }
        val canSave = !stale && !controller.busy && selection.isNotEmpty() && (phase is SetupPhase.NotYetSaved || changes != 0)
        Button(
            enabled = canSave,
            onClick = {
                // Frozen at click time: what the dialog shows is exactly what run() sends.
                val ids = selection
                val ltCount = s.loras.count { it.id in ids && it.pack == "lightricks" }
                val nsCount = s.loras.count { it.id in ids && it.pack == "nsfw" }
                controller.request(
                    ActionSpec(
                        title = "Save the LoRA selection",
                        consequences = listOf(
                            "Writes ${ids.size} of ${all.size} LoRAs ($ltCount Lightricks, $nsCount NSFW) as the enabled set, to the hub's saved-setup file on the relay (~/.forge_setup.json).",
                            if (phase is SetupPhase.NotYetSaved) "This is the first save: it records a selection for the first time."
                            else "It replaces the selection the hub has saved now.",
                            "Saving downloads nothing. The laptop's download script reads this list later.",
                            "The hub answers with its own view; this screen adopts that answer and reports any difference from what you chose.",
                        ),
                        confirmLabel = "Save selection",
                        danger = false,
                        run = {
                            val (outcome, fromHub) = actions.save(ids)
                            if (fromHub != null) panel.adopt(fromHub)
                            outcome
                        },
                    ),
                )
            },
        ) { Text("Save selection…") }
    }
}
