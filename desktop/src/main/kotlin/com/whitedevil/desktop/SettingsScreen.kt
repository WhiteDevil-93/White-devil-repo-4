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
    var model by remember { mutableStateOf(initial.model) }
    var webSearch by remember { mutableStateOf(initial.enableWebSearch) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("Back") }
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
            OutlinedTextField(model, { model = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = webSearch, onCheckedChange = { webSearch = it })
                Spacer(Modifier.width(12.dp))
                Text("Enable web search", style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider()

            Text("Stored in ${Settings.dir}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Secrets are held in plaintext here, as the Electron app did. The device key in the Windows TPM " +
                    "(Settings → device enrolment, once wired) removes the need to store the relay password at all.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            Button(onClick = {
                val next = initial.copy(
                    hubUrl = hubUrl.trim().trimEnd('/'),
                    relayUser = relayUser.trim(),
                    relayPass = relayPass,
                    veniceApiKey = veniceKey.trim(),
                    model = model.trim().ifBlank { initial.model },
                    enableWebSearch = webSearch,
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
