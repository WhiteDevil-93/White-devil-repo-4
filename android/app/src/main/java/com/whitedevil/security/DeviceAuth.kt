package com.whitedevil.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.fragment.app.FragmentActivity
import com.whitedevil.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Device-bound authentication, phone side.
 *
 * Enrol once (public key -> hub), then sign in by signing a single-use challenge
 * with a key the user has to unlock with their fingerprint or PIN. The result is a
 * short-lived bearer token.
 *
 * ADDITIVE. The relay still requires its basic auth and every existing call still
 * sends it. Nothing in here can make the app fail: an older relay that 404s on
 * the auth endpoints, a phone with no screen lock, a cancelled prompt — all come
 * back as an [Outcome] carrying a sentence for the UI, and the app carries on
 * exactly as it does today.
 *
 * Nothing logs the token, the nonce, the signature or any key material.
 */
object DeviceAuth {

    /** Result of an enrolment or sign-in attempt. Never thrown, always reported. */
    sealed class Outcome(val message: String) {
        /** Worked: enrolled and/or holding a live token. */
        class Ok(message: String) : Outcome(message)

        /** Deliberately not done (no screen lock, relay too old, user cancelled). */
        class Skipped(message: String) : Outcome(message)

        /** Tried and failed. The app continues on basic auth regardless. */
        class Failed(message: String) : Outcome(message)
    }

    /** Snapshot for the Settings screen. Holds no secrets. */
    data class State(
        val enrolled: Boolean = false,
        val deviceId: String? = null,
        val deviceName: String = "",
        val strongBox: Boolean = false,
        val hasKey: Boolean = false,
        val tokenValid: Boolean = false,
    ) {
        val summary: String
            get() = when {
                !enrolled -> "Not enrolled — this phone still uses the relay password only."
                tokenValid && strongBox -> "Enrolled (StrongBox) · device token active"
                tokenValid -> "Enrolled (hardware keystore) · device token active"
                strongBox -> "Enrolled (StrongBox) · sign in to refresh the device token"
                else -> "Enrolled (hardware keystore) · sign in to refresh the device token"
            }
    }

    fun defaultDeviceName(): String {
        val model = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        return if (model.isBlank()) "Android phone" else model.take(60)
    }

    private fun prefs(context: Context): SharedPreferences = SettingsManager.getPrefs(context)

    fun state(context: Context): State {
        val p = prefs(context)
        val id = p.getString(SettingsManager.KEY_DEVICE_AUTH_ID, null)?.takeIf { it.isNotBlank() }
        val expiresAt = p.getLong(SettingsManager.KEY_DEVICE_AUTH_TOKEN_EXPIRES_AT, 0L)
        val hasToken = !p.getString(SettingsManager.KEY_DEVICE_AUTH_TOKEN, null).isNullOrBlank()
        val hasKey = DeviceKeystore.hasKey()
        return State(
            // Both halves have to be present. An id with no key (enrolment died
            // half way, or the key was invalidated) is not a working enrolment,
            // and showing it as one would send the user to a sign-in that can
            // only fail; reporting "not enrolled" points them at the fix.
            enrolled = id != null && hasKey,
            deviceId = id,
            deviceName = p.getString(SettingsManager.KEY_DEVICE_AUTH_NAME, "").orEmpty()
                .ifBlank { defaultDeviceName() },
            strongBox = p.getBoolean(SettingsManager.KEY_DEVICE_AUTH_STRONGBOX, false),
            hasKey = hasKey,
            tokenValid = hasToken &&
                DeviceAuthCodec.tokenUsable(expiresAt, System.currentTimeMillis()),
        )
    }

    /** The cached bearer token while it is still live, else null. Never logged. */
    fun cachedToken(context: Context): String? {
        val p = prefs(context)
        val token = p.getString(SettingsManager.KEY_DEVICE_AUTH_TOKEN, null) ?: return null
        if (token.isBlank()) return null
        val expiresAt = p.getLong(SettingsManager.KEY_DEVICE_AUTH_TOKEN_EXPIRES_AT, 0L)
        return if (DeviceAuthCodec.tokenUsable(expiresAt, System.currentTimeMillis())) token else null
    }

    /** Forget the token but keep the enrolment (used when the hub rejects it). */
    fun clearToken(context: Context) {
        prefs(context).edit()
            .remove(SettingsManager.KEY_DEVICE_AUTH_TOKEN)
            .remove(SettingsManager.KEY_DEVICE_AUTH_TOKEN_EXPIRES_AT)
            .apply()
    }

    /** Forget everything local, including the keystore key. Re-enrolment starts clean. */
    fun forget(context: Context) {
        clearToken(context)
        prefs(context).edit()
            .remove(SettingsManager.KEY_DEVICE_AUTH_ID)
            .remove(SettingsManager.KEY_DEVICE_AUTH_NAME)
            .remove(SettingsManager.KEY_DEVICE_AUTH_STRONGBOX)
            .apply()
        DeviceKeystore.deleteKey()
    }

    // ------------------------------------------------------------------ flows

    /**
     * Enrol (or re-enrol) this phone, then immediately sign in.
     *
     * Re-enrolment always mints a fresh keypair: the hub rejects a duplicate public
     * key with 409, and a key the hub does not know is dead weight.
     */
    suspend fun enrolAndSignIn(
        activity: FragmentActivity,
        relayBase: String,
        basicAuth: String,
        deviceName: String,
    ): Outcome {
        BiometricAuth.signingBlockedReason(activity)?.let { return Outcome.Skipped(it) }

        val name = deviceName.trim().ifBlank { defaultDeviceName() }.take(80)
        val created = try {
            withContext(Dispatchers.IO) { DeviceKeystore.createKeyPair() }
        } catch (e: DeviceKeystore.KeystoreUnavailable) {
            return Outcome.Skipped(e.message ?: "This phone cannot hold a device key.")
        } catch (e: Exception) {
            return Outcome.Failed("Could not create a device key: ${e.javaClass.simpleName}")
        }

        val enrolled = try {
            withContext(Dispatchers.IO) {
                DeviceAuthClient.enrol(relayBase, basicAuth, name, created.publicKeyPem)
            }
        } catch (e: Exception) {
            DeviceKeystore.deleteKey()
            return if (DeviceAuthClient.isMissingEndpoint(e)) {
                Outcome.Skipped(
                    "This relay has no device-auth endpoints yet, so the phone keeps using " +
                        "the relay password. Nothing else changed.",
                )
            } else {
                Outcome.Failed("Enrolment refused by the hub: ${short(e)}")
            }
        }

        prefs(activity).edit()
            .putString(SettingsManager.KEY_DEVICE_AUTH_ID, enrolled.id)
            .putString(SettingsManager.KEY_DEVICE_AUTH_NAME, name)
            .putBoolean(SettingsManager.KEY_DEVICE_AUTH_STRONGBOX, created.strongBox)
            .remove(SettingsManager.KEY_DEVICE_AUTH_TOKEN)
            .remove(SettingsManager.KEY_DEVICE_AUTH_TOKEN_EXPIRES_AT)
            .apply()

        val backing = if (created.strongBox) "StrongBox" else "hardware keystore"
        return when (val signIn = signIn(activity, relayBase, basicAuth)) {
            is Outcome.Ok -> Outcome.Ok("Enrolled as \"$name\" ($backing) and signed in.")
            is Outcome.Skipped -> Outcome.Skipped("Enrolled as \"$name\" ($backing). ${signIn.message}")
            is Outcome.Failed -> Outcome.Failed("Enrolled as \"$name\" ($backing), but sign-in failed: ${signIn.message}")
        }
    }

    /** Use the cached token if it is live, otherwise sign in for a new one. */
    suspend fun ensureToken(
        activity: FragmentActivity,
        relayBase: String,
        basicAuth: String,
    ): Outcome {
        if (cachedToken(activity) != null) return Outcome.Ok("Device token is still valid.")
        return signIn(activity, relayBase, basicAuth)
    }

    /**
     * challenge -> biometric-bound signature -> token.
     *
     * The challenge is consumed by the hub whether or not verification succeeds, so
     * every attempt (including a retry after a cancelled prompt) starts by asking
     * for a fresh one. Reusing a nonce would always 400.
     */
    suspend fun signIn(
        activity: FragmentActivity,
        relayBase: String,
        basicAuth: String,
    ): Outcome {
        val deviceId = state(activity).deviceId
            ?: return Outcome.Skipped("This phone is not enrolled yet.")
        BiometricAuth.signingBlockedReason(activity)?.let { return Outcome.Skipped(it) }

        val signature = try {
            withContext(Dispatchers.IO) { DeviceKeystore.signatureForSigning() }
        } catch (_: DeviceKeystore.KeystoreUnavailable) {
            return Outcome.Failed(
                "The device key is missing from this phone's keystore — re-enrol to fix it.",
            )
        } catch (e: Exception) {
            return Outcome.Failed("Could not prepare the device key: ${e.javaClass.simpleName}")
        }

        val challenge = try {
            withContext(Dispatchers.IO) {
                DeviceAuthClient.challenge(relayBase, basicAuth, deviceId)
            }
        } catch (e: Exception) {
            return if (DeviceAuthClient.isMissingEndpoint(e)) {
                Outcome.Skipped("This relay has no device-auth endpoints; carrying on with the relay password.")
            } else {
                Outcome.Failed("Could not get a challenge: ${short(e)}")
            }
        }

        val authorised = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Result<java.security.Signature>> { cont ->
                BiometricAuth.authorizeSigning(
                    activity = activity,
                    signature = signature,
                    onAuthorized = { if (cont.isActive) cont.resume(Result.success(it)) },
                    onError = { msg -> if (cont.isActive) cont.resume(Result.failure(SignInError(msg, fatal = true))) },
                    onCancel = {
                        if (cont.isActive) {
                            cont.resume(
                                Result.failure(SignInError("Sign-in cancelled; the challenge was discarded.", fatal = false)),
                            )
                        }
                    },
                )
            }
        }
        authorised.exceptionOrNull()?.let { e ->
            val err = e as? SignInError
            return if (err?.fatal == false) Outcome.Skipped(err.message.orEmpty()) else Outcome.Failed(e.message ?: "Biometric check failed.")
        }

        val signatureB64 = try {
            withContext(Dispatchers.IO) {
                val der = DeviceKeystore.signNonce(authorised.getOrThrow(), challenge.nonce)
                if (!DeviceAuthCodec.looksLikeEcdsaDer(der)) {
                    error("keystore returned a signature that is not DER ECDSA")
                }
                DeviceAuthCodec.signatureBase64(der)
            }
        } catch (e: Exception) {
            return Outcome.Failed("Signing failed: ${e.javaClass.simpleName}")
        }

        val token = try {
            withContext(Dispatchers.IO) {
                DeviceAuthClient.token(relayBase, basicAuth, deviceId, signatureB64)
            }
        } catch (e: Exception) {
            return Outcome.Failed("The hub rejected the signature: ${short(e)}")
        }

        prefs(activity).edit()
            .putString(SettingsManager.KEY_DEVICE_AUTH_TOKEN, token.token)
            .putLong(
                SettingsManager.KEY_DEVICE_AUTH_TOKEN_EXPIRES_AT,
                DeviceAuthCodec.expiryAtMs(System.currentTimeMillis(), token.expiresIn),
            )
            .apply()
        val hours = token.expiresIn / 3600
        return Outcome.Ok(
            if (hours > 0) "Signed in — device token valid for ${hours}h." else "Signed in — device token issued.",
        )
    }

    private class SignInError(message: String, val fatal: Boolean) : Exception(message)

    /** Error text for the UI, trimmed and free of anything sensitive (bodies are hub messages). */
    private fun short(e: Throwable): String =
        (e.message ?: e.javaClass.simpleName).take(200)
}
