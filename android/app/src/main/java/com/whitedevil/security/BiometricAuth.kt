package com.whitedevil.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.Signature

/**
 * Biometric / screen-lock gating.
 *
 * The important entry point is [authorizeSigning]: it does not ask "is this the
 * user?" and then trust the answer, it hands the in-flight [Signature] to
 * BiometricPrompt inside a [BiometricPrompt.CryptoObject], so the AndroidKeyStore
 * itself refuses to produce a signature unless that fingerprint/PIN unlocked this
 * one operation. A UI-only check before calling sign() proves nothing — anything
 * that can reach the success path can reach sign() directly.
 *
 * [prompt] is the older UI-only gate. It still exists because the app-lock screen
 * is a convenience over locally stored settings with no key to bind to; it must
 * not be used to authorise the device key.
 */
object BiometricAuth {
    const val ALLOWED =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun status(context: Context): Int =
        BiometricManager.from(context).canAuthenticate(ALLOWED)

    fun available(context: Context): Boolean =
        status(context) == BiometricManager.BIOMETRIC_SUCCESS

    fun statusLabel(context: Context): String = when (status(context)) {
        BiometricManager.BIOMETRIC_SUCCESS -> "Ready (fingerprint / face / device PIN)"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "No biometrics enrolled — add fingerprint/face in Android Settings"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Biometric hardware unavailable"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "This device has no biometric hardware"
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "Security update required"
        else -> "Biometrics unavailable"
    }

    /**
     * Which authenticators can gate the signing key on this phone, or 0 for none.
     *
     * Degrades on purpose: a phone with no enrolled fingerprint but a PIN still
     * gets device-credential auth rather than being shut out of enrolment.
     * Returning 0 means there is no screen lock at all — the keystore would refuse
     * to create an auth-bound key anyway, so the caller skips enrolment and says why.
     */
    fun signingAuthenticators(context: Context): Int {
        val manager = BiometricManager.from(context)
        val strong = BiometricManager.Authenticators.BIOMETRIC_STRONG
        val credential = BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return when {
            manager.canAuthenticate(strong or credential) == BiometricManager.BIOMETRIC_SUCCESS ->
                strong or credential
            manager.canAuthenticate(credential) == BiometricManager.BIOMETRIC_SUCCESS ->
                credential
            else -> 0
        }
    }

    /** Why the signing key cannot be authorised here, or null if it can. */
    fun signingBlockedReason(context: Context): String? =
        if (signingAuthenticators(context) != 0) {
            null
        } else {
            "No fingerprint, face or device PIN is set up on this phone, so the signing " +
                "key cannot be protected. Add a screen lock in Android Settings, then enrol."
        }

    /**
     * Gate USE of the device signing key.
     *
     * [signature] must come from [DeviceKeystore.signatureForSigning]. On success
     * the callback receives the Signature the keystore actually unlocked (the one
     * carried back on the result, falling back to the original), ready for exactly
     * one sign().
     */
    fun authorizeSigning(
        activity: FragmentActivity,
        signature: Signature,
        title: String = "Sign in to the hub",
        subtitle: String = "Confirm it's you to use this phone's device key",
        onAuthorized: (Signature) -> Unit,
        onError: (String) -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        val authenticators = signingAuthenticators(activity)
        if (authenticators == 0) {
            onError(signingBlockedReason(activity) ?: statusLabel(activity))
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onAuthorized(result.cryptoObject?.signature ?: signature)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (isCancellation(errorCode)) onCancel() else onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // Keep the prompt open; user can retry.
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
    }

    /**
     * UI-only unlock for the app shell. No key is bound to it; do not use it to
     * authorise anything cryptographic — see [authorizeSigning].
     */
    fun prompt(
        activity: FragmentActivity,
        title: String = "Unlock WhiteDevil",
        subtitle: String = "Confirm it's you to open the app",
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        if (!available(activity)) {
            onError(statusLabel(activity))
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (isCancellation(errorCode)) onCancel() else onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // Keep the prompt open; user can retry.
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(ALLOWED)
            .build()
        prompt.authenticate(info)
    }

    private fun isCancellation(errorCode: Int): Boolean =
        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
            errorCode == BiometricPrompt.ERROR_CANCELED
}
