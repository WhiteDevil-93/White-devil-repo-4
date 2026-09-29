package com.whitedevil.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class DeviceMessage(val text: String, val isError: Boolean)

/**
 * "Device key" section of Settings: enrol this PC's Windows Hello key with the hub,
 * and sign in with it.
 *
 * Every Hello prompt starts from a button here. Nothing runs on composition, in a
 * LaunchedEffect, or on a timer, and a hub 429 disables the buttons until Retry-After
 * has passed rather than retrying.
 *
 * Purely additive: the relay password is still sent on every request. If the hub has
 * no device auth, or anything fails, the app carries on exactly as it did before and
 * the message here says so.
 *
 * @param hubUrl,relayUser,relayPass the Relay fields as currently typed above (not necessarily saved).
 * @param persistBase the settings to write device fields onto after enrolment. NOT `onSave`:
 *   the app's onSave navigates away from Settings, which enrolment must not do.
 * @param onDeviceChanged tells the screen the persisted id/name changed so its own Save keeps them.
 * @param onBusyChanged true while a Hello/hub action is in flight, so the screen can disable Save and Back:
 *   leaving mid-enrolment would cancel it after the Hello key was replaced but before the hub heard about it.
 */
@Composable
fun DeviceKeySection(
    hubUrl: String,
    relayUser: String,
    relayPass: String,
    deviceId: String,
    deviceName: String,
    persistBase: Settings,
    onDeviceChanged: (id: String, name: String) -> Unit,
    onBusyChanged: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val helper = remember { ProcessHelloHelper.createDefault() }
    val gate = remember { CooldownGate() }

    // Cheap file-existence checks only; nothing is spawned while composing.
    val helperFile = remember { helper.locatedExecutable() }
    val unavailable = remember { helper.unavailableReason() }

    var nameField by remember(deviceName) { mutableStateOf(deviceName.ifBlank { DeviceAuth.defaultDeviceName() }) }
    var enrolCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<DeviceMessage?>(null) }
    var confirmReenrol by remember { mutableStateOf(false) }

    val tokenKey = TokenCache.Key(HubAuthClient.normalizeBaseUrl(hubUrl).orEmpty(), deviceId)
    var signedInUntil by remember(deviceId, hubUrl) { mutableStateOf(DeviceAuthSession.tokens.validUntil(tokenKey)) }

    /**
     * Runs one action off a button press. Refuses while another is running and, for actions that talk to
     * the hub, while a 429 cooldown is in force. Never throws; never retries.
     */
    fun launchAction(label: String, usesHub: Boolean = true, action: suspend () -> DeviceMessage) {
        if (busy != null) return
        if (usesHub) {
            val wait = gate.remainingSeconds()
            if (wait > 0) {
                message = DeviceMessage("The hub asked us to slow down. Try again in about $wait seconds.", isError = true)
                return
            }
        }
        busy = label
        onBusyChanged(true)
        message = null
        scope.launch {
            try {
                message = action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Belt and braces: DeviceAuth already turns its failures into values.
                message = DeviceMessage("Unexpected error: ${e.message ?: e.javaClass.simpleName}", isError = true)
            } finally {
                busy = null
                onBusyChanged(false)
            }
        }
    }

    /** A DeviceAuth over a hub client built from the Relay fields as typed, closed when the action ends. */
    suspend fun <R> withAuth(block: suspend (DeviceAuth) -> R): R =
        HubAuthClient(hubUrl, relayUser, relayPass).use { hub -> block(DeviceAuth(helper, hub, DeviceAuthSession.tokens)) }

    fun enrol() = launchAction("Waiting for Windows Hello...") {
        val result = withAuth { it.enrol(nameField, enrolCode) }
        // The code is single use and was sent (or the key was already replaced): do not keep it around.
        if (result is EnrolResult.Enrolled || (result is EnrolResult.Failed && result.keyReplaced)) enrolCode = ""
        if (result is EnrolResult.Failed && result.rateLimited) gate.start(result.retryAfterSeconds)

        val next = settingsAfterEnrol(persistBase, result)
        var saveProblem: String? = null
        if (next != null) {
            Settings.save(next).onFailure { saveProblem = it.message ?: "unknown error" }
            onDeviceChanged(next.deviceId, next.deviceName)
            signedInUntil = null
        }
        when (result) {
            is EnrolResult.Enrolled -> DeviceMessage(
                "Enrolled as \"${result.deviceName}\" (id ${result.deviceId})." +
                    (saveProblem?.let { " Could not save it to settings ($it); press Save below to keep it." } ?: "") +
                    " Sign in to prove the key works.",
                isError = saveProblem != null,
            )
            is EnrolResult.Failed -> DeviceMessage(
                result.message + " The app carries on using the relay password as before.",
                isError = true,
            )
        }
    }

    fun signIn() = launchAction("Waiting for Windows Hello...") {
        when (val result = withAuth { it.signIn(deviceId) }) {
            is SignInResult.SignedIn -> {
                signedInUntil = result.validUntilMs
                DeviceMessage(
                    if (result.fromCache) "Already signed in; the device token is still valid until ${formatClock(result.validUntilMs)}."
                    else "Signed in with the device key. The token is held in memory only and is valid until ${formatClock(result.validUntilMs)}.",
                    isError = false,
                )
            }
            is SignInResult.Failed -> {
                if (result.rateLimited) gate.start(result.retryAfterSeconds)
                DeviceMessage(
                    result.message + " The app carries on using the relay password as before.",
                    isError = true,
                )
            }
        }
    }

    // status never prompts and never touches the hub, so it neither builds a hub client nor waits on a 429 cooldown.
    fun checkHello() = launchAction("Checking Windows Hello...", usesHub = false) {
        when (val s = helper.status()) {
            is HelperOutcome.Failure -> DeviceMessage(s.message, isError = true)
            is HelperOutcome.Success -> DeviceMessage(
                (if (s.value.helloAvailable) "Windows Hello is set up" else "Windows Hello is not set up") +
                    (if (s.value.helloAvailable) (if (s.value.keyExists) "; this PC has a WhiteDevil key." else "; no WhiteDevil key yet.") else ".") +
                    (s.value.detail?.let { " $it" } ?: ""),
                isError = !s.value.helloAvailable,
            )
        }
    }

    val enabled = busy == null && unavailable == null

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Device key", style = MaterialTheme.typography.labelLarge)
        Text(
            "Enrol this PC's Windows Hello key with the hub, then sign in with it. Windows will ask for your PIN or fingerprint. " +
                "This is additive: the relay user and password are still sent on every request, and if the hub has no device " +
                "authentication, or anything here fails, the app works exactly as it does today.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            when {
                unavailable != null -> unavailable
                helperFile != null -> "Windows Hello helper found: ${helperFile.absolutePath}"
                else -> "Windows Hello helper not found."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (unavailable != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (deviceId.isBlank()) "Not enrolled."
            else "Enrolled as \"${deviceName.ifBlank { "this PC" }}\" (id $deviceId). " +
                (signedInUntil?.let { "Signed in until ${formatClock(it)}." } ?: "Not signed in."),
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            nameField, { nameField = it },
            label = { Text("Device name") },
            supportingText = { Text("Shown in the hub's device list (up to ${DeviceAuth.MAX_DEVICE_NAME} characters).") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = busy == null,
        )
        OutlinedTextField(
            enrolCode, { enrolCode = it },
            label = { Text("Enrolment code (single use)") },
            supportingText = { Text("Only needed when the hub requires one. The hub operator issues it; it works once, for 15 minutes.") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = busy == null,
        )

        if (confirmReenrol) {
            Text(
                "This PC is already enrolled. Enrolling again replaces its Windows Hello key, so the existing enrolment stops working. Continue?",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (confirmReenrol) {
                Button(enabled = enabled, onClick = { confirmReenrol = false; enrol() }) { Text("Replace key and re-enrol") }
                TextButton(onClick = { confirmReenrol = false }) { Text("Cancel") }
            } else {
                Button(
                    enabled = enabled,
                    onClick = { if (deviceId.isNotBlank()) confirmReenrol = true else enrol() },
                ) { Text(if (deviceId.isBlank()) "Enrol this PC" else "Re-enrol...") }
                OutlinedButton(enabled = enabled && deviceId.isNotBlank(), onClick = { signIn() }) { Text("Sign in") }
                TextButton(enabled = enabled, onClick = { checkHello() }) { Text("Check Windows Hello") }
                if (signedInUntil != null) {
                    TextButton(
                        enabled = busy == null,
                        onClick = {
                            DeviceAuthSession.tokens.clear()
                            signedInUntil = null
                            message = DeviceMessage("Signed out. The in-memory token was discarded.", isError = false)
                        },
                    ) { Text("Sign out") }
                }
            }
            busy?.let {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        message?.let {
            Text(
                it.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )
        }
        Text(
            "Signing in proves this PC's key works (the hub records when it was last seen). Nothing else uses the token yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun formatClock(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(clockFormat)
