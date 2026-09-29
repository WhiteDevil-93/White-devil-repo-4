package com.whitedevil.desktop.ops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The confirmation dialog for every action that starts, stops, spends or destroys.
 *
 * It is the only caller of [ActionController.confirm], so it is the only way an
 * [ActionSpec.run] ever executes. Three phases in one dialog:
 *  1. Confirming: names the action and what it costs or does; where the spec
 *     demands it, the confirm button stays disabled until the phrase is typed.
 *  2. Running: cannot be dismissed, and says leaving the screen stops the wait,
 *     not the action.
 *  3. Finished: the hub's own answer, verbatim, plus per-part results — this is
 *     where a 200 with ok:false is shown as the failure it is.
 */
@Composable
fun ActionDialog(controller: ActionController) {
    val scope = rememberCoroutineScope()
    when (val p = controller.phase) {
        is ActionPhase.Idle -> Unit

        is ActionPhase.Confirming -> {
            val spec = p.spec
            AlertDialog(
                onDismissRequest = { controller.dismiss() },
                modifier = Modifier.widthIn(min = 460.dp, max = 640.dp),
                title = { Text(spec.title) },
                text = {
                    Column(
                        Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        spec.consequences.forEach { line ->
                            Text("•  $line", style = MaterialTheme.typography.bodyMedium)
                        }
                        val phrase = spec.typedPhrase
                        if (phrase != null) {
                            Text(
                                "This cannot be undone from here. To go ahead, type $phrase below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = controller.typed,
                                onValueChange = controller::updateTyped,
                                label = { Text("Type $phrase to confirm") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(
                                "Nothing is sent until you press ${spec.confirmLabel}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { scope.launch { controller.confirm() } },
                        enabled = controller.canConfirm(),
                        colors = if (spec.danger) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            )
                        } else ButtonDefaults.buttonColors(),
                    ) { Text(spec.confirmLabel) }
                },
                dismissButton = { TextButton(onClick = { controller.dismiss() }) { Text("Cancel") } },
            )
        }

        is ActionPhase.Running -> AlertDialog(
            onDismissRequest = {}, // deliberately inert: the outcome must be seen
            modifier = Modifier.widthIn(min = 460.dp, max = 640.dp),
            title = { Text(p.spec.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Request sent. Waiting for the hub to answer…", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "Do not press it again. Leaving this screen stops the wait here, not the action on the hub; " +
                            "reopen the screen and read the state to see what happened.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
        )

        is ActionPhase.Finished -> AlertDialog(
            onDismissRequest = { controller.dismiss() },
            modifier = Modifier.widthIn(min = 460.dp, max = 720.dp),
            title = { Text(p.spec.title) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    OutcomeView(p.outcome)
                }
            },
            confirmButton = { TextButton(onClick = { controller.dismiss() }) { Text("Close") } },
        )
    }
}

/** What the hub said, as plainly as possible: verdict, per-part results, then the raw body. */
@Composable
fun OutcomeView(outcome: ActionOutcome) {
    val ok = outcome is ActionOutcome.Succeeded
    val tone = if (ok) Tone.Ok else Tone.Bad
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Pill(if (ok) "HUB REPLY: OK" else "HUB REPLY: FAILED OR NOT CONFIRMED", tone)
        when (outcome) {
            is ActionOutcome.Succeeded -> {
                SelectionContainer { Text(outcome.headline, style = MaterialTheme.typography.bodyMedium, color = tone.color()) }
                outcome.detail?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
            }
            is ActionOutcome.Failed -> {
                SelectionContainer { Text(outcome.headline, style = MaterialTheme.typography.bodyMedium, color = tone.color()) }
                outcome.parts.forEach { part ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(if (part.ok) "OK" else "FAILED", if (part.ok) Tone.Ok else Tone.Bad)
                        Column {
                            Text(part.label, style = MaterialTheme.typography.bodyMedium)
                            part.detail?.let {
                                SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
                outcome.detail?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
                if (outcome.mayHaveExecuted) {
                    Note("The hub may still have carried this out. Refresh and check the current state before trying again.", Tone.Warn)
                }
            }
        }
        outcome.status?.let {
            Text("HTTP $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val raw = when (outcome) {
            is ActionOutcome.Succeeded -> outcome.rawBody
            is ActionOutcome.Failed -> outcome.rawBody
        }
        if (!raw.isNullOrBlank()) {
            Text("Hub response", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MonoBlock(raw)
        }
    }
}
