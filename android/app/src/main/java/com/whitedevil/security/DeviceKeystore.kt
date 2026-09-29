package com.whitedevil.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The phone's half of device-bound auth: an ECDSA P-256 keypair that lives in the
 * AndroidKeyStore and never comes out.
 *
 * Two properties carry the whole design:
 *  - the private key is non-exportable (no `getEncoded`, no backup, no clipboard),
 *    so the only thing that ever crosses the wire is the public key and a signature;
 *  - `setUserAuthenticationRequired(true)` with a 0-second timeout makes it an
 *    auth-per-use key: the keystore refuses to sign unless THIS operation was
 *    unlocked by the user, which is what [BiometricAuth.authorizeSigning] arranges
 *    by handing the Signature object to BiometricPrompt inside a CryptoObject.
 *
 * StrongBox (a separate security chip) is requested but not required — plenty of
 * devices have no such chip and throw [StrongBoxUnavailableException]; falling back
 * to the TEE-backed keystore is strictly better than refusing to enrol.
 */
object DeviceKeystore {

    const val ALIAS = "whitedevil_device_auth_ec_v1"
    private const val PROVIDER = "AndroidKeyStore"
    private const val SIGN_ALGORITHM = "SHA256withECDSA"

    /** Thrown when the phone cannot give us a usable hardware key at all. */
    class KeystoreUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Result of a fresh enrolment key: the PEM to send, and whether StrongBox took it. */
    data class Created(val publicKeyPem: String, val strongBox: Boolean)

    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun hasKey(): Boolean = runCatching { store().containsAlias(ALIAS) }.getOrDefault(false)

    fun deleteKey() {
        runCatching { store().deleteEntry(ALIAS) }
    }

    /**
     * Generate (or replace) the device key. StrongBox first, TEE second.
     *
     * Any previous key is dropped: a key the hub no longer knows about is useless,
     * and re-enrolment is the only way to recover from a revoked or orphaned device.
     */
    fun createKeyPair(): Created {
        deleteKey()
        val strongBoxAttempt = runCatching { generate(strongBox = true) }
        strongBoxAttempt.getOrNull()?.let { return Created(it, strongBox = true) }

        val failure = strongBoxAttempt.exceptionOrNull()
        // StrongBoxUnavailableException is the documented signal, but OEM keystores
        // have been known to surface the same condition as a generic
        // ProviderException, so every failure retries once without StrongBox rather
        // than blocking enrolment on a chip the user may simply not have.
        deleteKey()
        return try {
            Created(generate(strongBox = false), strongBox = false)
        } catch (t: Throwable) {
            deleteKey()
            val why = if (failure is StrongBoxUnavailableException) {
                "no StrongBox, and the keystore refused a software-backed key"
            } else {
                failure?.javaClass?.simpleName ?: t.javaClass.simpleName
            }
            throw KeystoreUnavailable(
                "Could not create a device key on this phone ($why). A screen lock " +
                    "(fingerprint, face or PIN) must be set up before enrolling.",
                t,
            )
        }
    }

    private fun generate(strongBox: Boolean): String {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            // Timeout 0 == authenticate per operation, which is what binds the
            // fingerprint to this signature instead of to a time window.
            .setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        generator.initialize(spec)
        val pair = generator.generateKeyPair()
        return DeviceAuthCodec.publicKeyPem(pair.public.encoded)
    }

    /** PEM for the existing key, or null if there isn't one. */
    fun publicKeyPem(): String? {
        val cert = runCatching { store().getCertificate(ALIAS) }.getOrNull() ?: return null
        val encoded = cert.publicKey?.encoded ?: return null
        return runCatching { DeviceAuthCodec.publicKeyPem(encoded) }.getOrNull()
    }

    /**
     * A Signature initialised for signing but NOT yet usable: the keystore will
     * reject [Signature.sign] until this exact object has been through
     * BiometricPrompt as a CryptoObject. Hand it to
     * [BiometricAuth.authorizeSigning] and sign in the success callback.
     */
    fun signatureForSigning(): Signature {
        val key = runCatching { store().getKey(ALIAS, null) }.getOrNull() as? PrivateKey
            ?: throw KeystoreUnavailable("No device key on this phone — enrol it first.")
        return Signature.getInstance(SIGN_ALGORITHM).apply { initSign(key) }
    }

    /** Sign the raw UTF-8 bytes of [nonce] with an already-authorised Signature. */
    fun signNonce(authorised: Signature, nonce: String): ByteArray {
        authorised.update(DeviceAuthCodec.nonceBytes(nonce))
        return authorised.sign()
    }
}
