package com.whitedevil.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(initial: Settings, onSave: (Settings) -> Unit, onBack: () -> Unit) {
    var hubUrl by remember { mutableStateOf(initial.hubUrl) }
    var relayUser by remember { mutableStateOf(initial.relayUser) }
    var relayPass by remember { mutableStateOf(initial.relayPass) }
    var veniceKey by remember { mutableStateOf(initial.veniceApiKey) }
    var openRouterKey by remember { mutableStateOf(initial.openRouterApiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var webSearch by remember { mutableStateOf(initial.enableWebSearch) }
    var status by remember { mutableStateOf<String?>(null) }

    // Enrolment writes deviceId/deviceName straight to disk (it must not go through onSave, which
    // navigates away), so the app-level `initial` can be older than the file. Read the file too, and
    // carry these two fields through Save so a later Save cannot blank an enrolment.
    val onDisk = remember { Settings.load() }
    var deviceId by remember { mutableStateOf(initial.deviceId.ifBlank { onDisk.deviceId }) }
    var deviceName by remember { mutableStateOf(initial.deviceName.ifBlank { onDisk.deviceName }) }
    // Leaving mid-enrolment would cancel it after the Hello key was replaced but before the hub heard about it.
    var deviceBusy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBack, enabled = !deviceBusy) { Text("Back") }
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Relay", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(hubUrl, { hubUrl = it }, label = { Text("Hub URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(relayUser, { relayUser = it }, label = { Text("Relay user") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(
                relayPass, { relayPass = it },
                label = { Text("Relay password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrect = false),
            )

            HorizontalDivider()

            Text("Venice", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                veniceKey, { veniceKey = it },
                label = { Text("Venice API key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrect = false),
            )
            Text("OpenRouter", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                openRouterKey, { openRouterKey = it },
                label = { Text("OpenRouter API key") },
                supportingText = { Text("Used automatically for models marked OpenRouter.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrect = false),
            )

            ModelPicker(model, { model = it })
            OutlinedTextField(
                model, { model = it },
                label = { Text("Custom model ID (optional)") },
                supportingText = { Text("Use a provider/model ID, for example openai/gpt-4o-mini for OpenRouter.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = webSearch, onCheckedChange = { webSearch = it })
                Spacer(Modifier.width(12.dp))
                Text("Enable web search", style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider()

            DeviceKeySection(
                hubUrl = hubUrl,
                relayUser = relayUser.trim(),
                relayPass = relayPass,
                deviceId = deviceId,
                deviceName = deviceName,
                persistBase = initial.copy(deviceId = deviceId, deviceName = deviceName),
                onDeviceChanged = { id, name -> deviceId = id; deviceName = name },
                onBusyChanged = { deviceBusy = it },
            )

            HorizontalDivider()

            Text("Stored in ${Settings.dir}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Secrets are held in plaintext here, as the Electron app did. Enrolling the device key above (Windows TPM) is a first step " +
                    "towards not storing the relay password at all; until the hub stops requiring it, the password is still needed and still sent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            Button(enabled = !deviceBusy, onClick = {
                val next = initial.copy(
                    hubUrl = hubUrl.trim().trimEnd('/'),
                    relayUser = relayUser.trim(),
                    relayPass = relayPass,
                    veniceApiKey = veniceKey.trim(),
                    openRouterApiKey = openRouterKey.trim(),
                    model = model.trim().ifBlank { initial.model },
                    enableWebSearch = webSearch,
                    deviceId = deviceId,
                    deviceName = deviceName,
                )
                Settings.save(next)
                    .onSuccess { status = null; onSave(next) }
                    // A save that silently fails would lose the key on restart
                    // with no sign anything went wrong.
                    .onFailure { status = "Could not save: ${it.message}" }
            }) { Text("Save") }
        }
    }
}

@Composable
private fun ModelPicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(ModelCatalog.find(selected)?.name ?: "Choose a catalog model")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ModelCatalog.entries.forEach { info ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("${info.name} · ${info.provider.label}")
                            Text(
                                "${ModelCatalog.priceLabel(info)}  •  ${ModelCatalog.capabilityLabel(info)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { onSelect(info.id); expanded = false },
                )
            }
        }
    }
}
