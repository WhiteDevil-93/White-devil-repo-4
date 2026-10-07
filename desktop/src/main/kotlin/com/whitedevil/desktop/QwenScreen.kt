package com.whitedevil.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.whitedevil.desktop.ops.ActionController
import com.whitedevil.desktop.ops.ActionDialog
import com.whitedevil.desktop.ops.ActionSpec
import com.whitedevil.desktop.ops.KeyValue
import com.whitedevil.desktop.ops.MonoBlock
import com.whitedevil.desktop.ops.Note
import com.whitedevil.desktop.ops.OpsScreenFrame
import com.whitedevil.desktop.ops.PanelState
import com.whitedevil.desktop.ops.PanelView
import com.whitedevil.desktop.ops.Pill
import com.whitedevil.desktop.ops.PollWhileVisible
import com.whitedevil.desktop.ops.QwenActions
import com.whitedevil.desktop.ops.QwenApi
import com.whitedevil.desktop.ops.QWEN_POLL_MS
import com.whitedevil.desktop.ops.QwenState
import com.whitedevil.desktop.ops.QwenVmInfo
import com.whitedevil.desktop.ops.SectionCard
import com.whitedevil.desktop.ops.Tile
import com.whitedevil.desktop.ops.TileRow
import com.whitedevil.desktop.ops.Tone
import com.whitedevil.desktop.ops.formatClock
import com.whitedevil.desktop.ops.money
import com.whitedevil.desktop.ops.orUnknown
import com.whitedevil.desktop.ops.rememberOpsClients
import kotlinx.coroutines.launch

/**
 * Qwen API Operations screen:
 *
 * Displays live gateway health (/healthz) vs model readiness (/readyz), in-memory
 * request concurrency, and optional Vast GPU VM power state and billing.
 * Any billed VM state change is protected by typed confirmation.
 */
@Composable
fun QwenScreen(settings: Settings) {
    val clients = rememberOpsClients(settings)
    val api = remember(clients) { QwenApi(clients.reader) }
    val actions = remember(clients) { QwenActions(clients.actor) }
    val scope = rememberCoroutineScope()
    val state: PanelState<QwenState> = remember(api, settings.qwenGatewayUrl) {
        PanelState(load = { api.state(gatewayUrl = settings.qwenGatewayUrl.ifBlank { null }) })
    }

    val controller = remember(state) {
        ActionController(onFinished = { scope.launch { state.refresh(followUp = true) } })
    }

    PollWhileVisible(state, QWEN_POLL_MS)

    var showKeyEditor by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var keySaveStatus by remember { mutableStateOf<String?>(null) }
    var hasKey by remember { mutableStateOf(WindowsSecretStore.hasQwenKey()) }

    OpsScreenFrame(
        title = "Qwen API",
        subtitle = state.lastGood?.let { "Updated ${formatClock(it.atMillis)} · refreshes every ${QWEN_POLL_MS / 1000}s" }
            ?: "Refreshes every ${QWEN_POLL_MS / 1000}s",
        refreshing = state.refreshing,
        onRefresh = { scope.launch { state.refresh() } },
    ) {
        PanelView(state, "Qwen state", onRetry = { scope.launch { state.refresh() } }) { s, stale ->
            QwenOverview(s)
            s.error?.let { QwenErrorCard(it) }
            s.vm?.let { vm ->
                QwenVmCard(
                    vm = vm,
                    stale = stale,
                    onStart = {
                        controller.request(
                            ActionSpec(
                                title = "Start Qwen GPU VM",
                                consequences = listOf("Starting this instance will resume per-hour GPU billing."),
                                confirmLabel = "Start VM",
                                typedPhrase = "START",
                                danger = true,
                                run = { actions.startVm(vm.instanceId) },
                            )
                        )
                    },
                    onStop = {
                        controller.request(
                            ActionSpec(
                                title = "Stop Qwen GPU VM",
                                consequences = listOf("Stopping releases the GPU. The disk continues to bill at storage rate."),
                                confirmLabel = "Stop VM",
                                typedPhrase = "STOP",
                                danger = true,
                                run = { actions.stopVm(vm.instanceId) },
                            )
                        )
                    },
                )
            }

            SectionCard("Client Security & Credentials") {
                Text(
                    text = "The client key is stored in the OS-protected Windows Credential store (DPAPI) and never stored in plaintext settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(
                        text = if (hasKey) "Key Saved (DPAPI)" else "No Key Configured",
                        tone = if (hasKey) Tone.Ok else Tone.Neutral,
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = {
                        keyInput = ""
                        keySaveStatus = null
                        showKeyEditor = !showKeyEditor
                    }) {
                        Text(if (showKeyEditor) "Cancel" else if (hasKey) "Change Key" else "Set Key")
                    }
                    if (hasKey) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            WindowsSecretStore.deleteQwenKey()
                            hasKey = false
                            keySaveStatus = "Key removed"
                        }) {
                            Text("Clear Key")
                        }
                    }
                }

                if (showKeyEditor) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("Qwen Bearer API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(onClick = {
                            if (keyInput.isNotBlank()) {
                                val ok = WindowsSecretStore.saveQwenKey(keyInput)
                                hasKey = WindowsSecretStore.hasQwenKey()
                                keySaveStatus = if (ok) "Key encrypted and saved" else "Failed to save key"
                                if (ok) showKeyEditor = false
                            }
                        }) {
                            Text("Save to DPAPI")
                        }
                    }
                }
                keySaveStatus?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    ActionDialog(controller)
}

@Composable
private fun QwenOverview(s: QwenState) {
    SectionCard("Inference Gateway & Model State") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(
                text = if (s.gatewayAlive) "Gateway Online" else "Gateway Offline",
                tone = if (s.gatewayAlive) Tone.Ok else Tone.Bad,
            )
            Pill(
                text = if (s.modelReady) "Model Ready (${s.modelAlias})" else if (s.gatewayAlive) "Model Loading / Not Ready" else "Model Offline",
                tone = if (s.modelReady) Tone.Ok else if (s.gatewayAlive) Tone.Warn else Tone.Neutral,
            )
            Pill(
                text = "Overall: ${s.status.uppercase()}",
                tone = when (s.status) {
                    "ready" -> Tone.Ok
                    "loading" -> Tone.Warn
                    else -> Tone.Bad
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        TileRow {
            Tile("Gateway Ping", s.healthLatencyMs?.let { "${it.toInt()} ms" } ?: "—")
            Tile("Ready Check", s.readyLatencyMs?.let { "${it.toInt()} ms" } ?: "—")
            Tile("Active Inferences", "${s.activeRequests ?: 0} / ${s.maxConcurrency ?: 1}")
            Tile("Model Alias", s.modelAlias)
        }

        Spacer(Modifier.height(8.dp))
        KeyValue("Gateway Endpoint", s.gatewayUrl.ifBlank { "http://127.0.0.1:18080" })
    }
}

@Composable
private fun QwenErrorCard(err: String) {
    SectionCard("Probe Diagnostics") {
        Note("Last probe encountered an issue:", Tone.Warn)
        Spacer(Modifier.height(6.dp))
        MonoBlock(err)
    }
}

@Composable
private fun QwenVmCard(
    vm: QwenVmInfo,
    stale: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    SectionCard("Vast GPU Host VM") {
        TileRow {
            Tile("Instance ID", vm.instanceId)
            Tile("VM Status", orUnknown(vm.status).uppercase())
            Tile("GPU", orUnknown(vm.gpu))
            Tile("VRAM", vm.vramGb?.let { "$it GB" } ?: "—")
        }
        Spacer(Modifier.height(8.dp))
        TileRow {
            Tile("Running Rate", vm.pricePerHour?.let { "${money(it)}/hr" } ?: "—")
            Tile("Stopped Rate", vm.stoppedPricePerHour?.let { "${money(it)}/hr" } ?: "—")
            Tile("Host Address", vm.host?.let { h -> vm.port?.let { p -> "$h:$p" } ?: h } ?: "—")
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val isRunning = vm.status?.lowercase() == "running"
            if (!isRunning) {
                Button(onClick = onStart, enabled = !stale) {
                    Text("Start VM (Resume Billing)")
                }
            } else {
                Button(
                    onClick = onStop,
                    enabled = !stale,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Stop VM (Release GPU)")
                }
            }
        }
    }
}
