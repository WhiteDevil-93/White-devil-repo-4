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
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(
    initial: Settings,
    deviceAuth: DeviceAuthService,
    onSave: (Settings) -> Unit,
    /** Enrolment persists the device id straight away, without leaving the screen. */
    onDeviceChanged: (Settings) -> Unit,
    onBack: () -> Unit,
) {
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

            // Uses the values as typed, so a device can be enrolled before Save.
            DeviceKeySection(
                settings = initial,
                hubUrl = hubUrl.trim(),
                relayUser = relayUser.trim(),
                relayPass = relayPass,
                deviceAuth = deviceAuth,
                onDeviceChanged = onDeviceChanged,
            )

            HorizontalDivider()

            Text("Stored in ${Settings.dir}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Secrets are held in plaintext here, as the Electron app did. The device key above (Windows TPM) " +
                    "will remove the need to store the relay password at all once the relay is switched over to device tokens.",
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

/**
 * Windows Hello device key: enrol this laptop with the hub, then sign in with it.
 *
 * Additive — the relay password above is still sent on every request, so a failure
 * here changes nothing about how the app works today; it just says why. Hello
 * prompts the user on create and on sign, so those only ever run from a button.
 */
@Composable
private fun DeviceKeySection(
    settings: Settings,
    hubUrl: String,
    relayUser: String,
    relayPass: String,
    deviceAuth: DeviceAuthService,
    onDeviceChanged: (Settings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var deviceName by remember { mutableStateOf(settings.deviceName.ifBlank { defaultDeviceName() }) }
    var enrolCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var helloLine by remember { mutableStateOf("Checking Windows Hello…") }
    var result by remember { mutableStateOf<DeviceResult?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }

    // Read-only probe: `status` does not prompt. Never create/sign from here.
    LaunchedEffect(Unit) {
        helloLine = deviceAuth.status().fold(
            onSuccess = { st ->
                when {
                    !st.available -> st.detail.ifBlank { "Windows Hello is not set up on this account." }
                    st.keyExists -> "Windows Hello ready · a WhiteDevil key exists on this account."
                    else -> "Windows Hello ready · no WhiteDevil key yet."
                }
            },
            onFailure = { it.message ?: "Windows Hello helper unavailable." },
        )
    }

    val enrolled = settings.deviceId.isNotBlank()
    val token = remember(result, settings.deviceId) { if (enrolled) deviceAuth.cachedToken(settings.deviceId) else null }

    fun run(action: suspend (HubAuthClient) -> DeviceResult, onDone: (DeviceResult) -> Unit = {}) {
        if (busy) return
        if (hubUrl.isBlank()) {
            result = DeviceResult(false, "Set the hub URL first. ${DeviceAuthService.CARRY_ON}")
            return
        }
        busy = true
        result = null
        scope.launch {
            val r = try {
                HubAuthClient(hubUrl, relayUser, relayPass).use { action(it) }
            } finally {
                busy = false
            }
            result = r
            onDone(r)
        }
    }

    fun enrol(replace: Boolean) = run(
        action = { hub -> deviceAuth.enrol(hub, deviceName, enrolCode, replaceExisting = replace) },
        onDone = { r ->
            when {
                r.needsReplaceConfirmation -> confirmReplace = true
                r.ok && r.deviceId != null -> {
                    val next = settings.copy(deviceId = r.deviceId, deviceName = r.deviceName ?: deviceName.trim())
                    Settings.save(next)
                        .onSuccess { enrolCode = ""; onDeviceChanged(next) }
                        // Enrolled on the hub but the id was not kept: say so, and show
                        // the id, or the device is registered with nothing pointing at it.
                        .onFailure {
                            result = DeviceResult(
                                false,
                                "Enrolled on the hub as ${r.deviceId}, but the id could not be saved (${it.message}). " +
                                    "Note it down; you can revoke it with DELETE /api/auth/devices/${r.deviceId}.",
                            )
                        }
                }
            }
        },
    )

    Text("Device key", style = MaterialTheme.typography.labelLarge)
    Text(
        "Enrols this laptop with the hub using a key held in Windows Hello (TPM). Optional: the relay password " +
            "is still sent as before, and a failure here changes nothing about how the app works.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(helloLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (enrolled) {
        Text("Enrolled as \"${settings.deviceName}\" · id ${settings.deviceId}", style = MaterialTheme.typography.bodyMedium)
        Text(
            token?.let { "Device token held until ${clock(it.expiresAtMs)}." } ?: "Not signed in this session.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        OutlinedTextField(
            deviceName, { deviceName = it.take(DeviceAuthService.MAX_NAME) },
            label = { Text("Device name") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(
            enrolCode, { enrolCode = it },
            label = { Text("Enrolment code (only if the hub requires one)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrect = false),
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!enrolled) {
            Button(onClick = { enrol(replace = false) }, enabled = !busy) { Text("Enrol this device") }
        } else {
            Button(
                onClick = { run({ hub -> deviceAuth.signIn(hub, settings.deviceId) }) },
                enabled = !busy,
            ) { Text("Sign in") }
            OutlinedButton(
                onClick = { run({ hub -> deviceAuth.verify(hub, settings.deviceId) }) },
                enabled = !busy && token != null,
            ) { Text("Verify token") }
            TextButton(
                onClick = {
                    deviceAuth.forgetLocal()
                    val next = settings.copy(deviceId = "", deviceName = "")
                    Settings.save(next)
                        .onSuccess { result = null; onDeviceChanged(next) }
                        .onFailure { result = DeviceResult(false, "Could not save: ${it.message}") }
                },
                enabled = !busy,
            ) { Text("Forget device") }
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Waiting… approve the Windows Hello prompt if one appears.", style = MaterialTheme.typography.bodySmall)
        }
    }

    result?.let {
        Text(
            it.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (it.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }

    if (enrolled) {
        Text(
            "Forget device only clears this app's record (to enrol again: Forget, then Enrol). To revoke it on the hub use DELETE /api/auth/devices/{id}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace the existing key?") },
            text = {
                Text(
                    "Windows already holds a WhiteDevil key on this account. Enrolling creates a new one and destroys " +
                        "the old, so any hub entry enrolled with the old key can no longer sign in. Revoke it on the hub afterwards.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmReplace = false; enrol(replace = true) }) { Text("Replace key") }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } },
        )
    }
}

private fun defaultDeviceName(): String =
    System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() } ?: "WhiteDevil desktop"

private fun clock(epochMs: Long): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
