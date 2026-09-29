package com.whitedevil.security

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire format for the hub's device-bound auth endpoints (hub/auth.py).
 *
 * Everything here is pure: no Android classes, no network, no keystore — so it is
 * unit-testable on the JVM and is where the encoding bugs can actually be caught.
 * Bodies are built with kotlinx-serialization, never by string concatenation: a
 * hand-rolled JSON escaper is exactly the kind of thing that silently emits a raw
 * newline where it meant `\n`, and the server would reject it with no useful clue.
 */
object DeviceAuthCodec {

    /** Server tolerates extra fields and gains them over time; ignore what we don't model. */
    val json: Json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------- requests

    @Serializable
    data class EnrolRequest(
        val name: String,
        @SerialName("public_key_pem") val publicKeyPem: String,
    )

    @Serializable
    data class ChallengeRequest(
        @SerialName("device_id") val deviceId: String,
    )

    @Serializable
    data class TokenRequest(
        @SerialName("device_id") val deviceId: String,
        @SerialName("signature_b64") val signatureB64: String,
    )

    // --------------------------------------------------------------- responses

    @Serializable
    data class EnrolResponse(
        val id: String,
        val name: String = "",
        val created: Double = 0.0,
    )

    @Serializable
    data class ChallengeResponse(
        val nonce: String,
        @SerialName("expires_in") val expiresIn: Long = 0,
    )

    @Serializable
    data class TokenResponse(
        val token: String,
        @SerialName("expires_in") val expiresIn: Long = 0,
        @SerialName("device_id") val deviceId: String = "",
    )

    @Serializable
    data class WhoamiResponse(
        val authenticated: Boolean = false,
        @SerialName("device_id") val deviceId: String? = null,
        val name: String? = null,
        @SerialName("devices_enrolled") val devicesEnrolled: Int = 0,
    )

    fun encodeEnrol(body: EnrolRequest): String =
        json.encodeToString(EnrolRequest.serializer(), body)

    fun encodeChallenge(body: ChallengeRequest): String =
        json.encodeToString(ChallengeRequest.serializer(), body)

    fun encodeToken(body: TokenRequest): String =
        json.encodeToString(TokenRequest.serializer(), body)

    fun decodeEnrol(text: String): EnrolResponse =
        json.decodeFromString(EnrolResponse.serializer(), text)

    fun decodeChallenge(text: String): ChallengeResponse =
        json.decodeFromString(ChallengeResponse.serializer(), text)

    fun decodeTokenResponse(text: String): TokenResponse =
        json.decodeFromString(TokenResponse.serializer(), text)

    fun decodeWhoami(text: String): WhoamiResponse =
        json.decodeFromString(WhoamiResponse.serializer(), text)

    // ------------------------------------------------------------------- PEM

    private const val PEM_BEGIN = "-----BEGIN PUBLIC KEY-----"
    private const val PEM_END = "-----END PUBLIC KEY-----"
    private const val PEM_LINE = 64

    /**
     * SubjectPublicKeyInfo DER -> PEM, base64 wrapped at 64 columns.
     *
     * [java.security.PublicKey.getEncoded] already hands back SPKI for the
     * AndroidKeyStore EC keys, which is precisely what the hub's
     * `load_pem_public_key` expects — no ASN.1 assembly needed on our side.
     */
    fun publicKeyPem(spkiDer: ByteArray): String {
        require(spkiDer.isNotEmpty()) { "Empty SubjectPublicKeyInfo" }
        val b64 = java.util.Base64.getEncoder().encodeToString(spkiDer)
        val sb = StringBuilder(b64.length + 96)
        sb.append(PEM_BEGIN).append('\n')
        var i = 0
        while (i < b64.length) {
            val end = minOf(i + PEM_LINE, b64.length)
            sb.append(b64, i, end).append('\n')
            i = end
        }
        sb.append(PEM_END).append('\n')
        return sb.toString()
    }

    /** Standard (not URL-safe, not chunked) base64 — `base64.b64decode(validate=True)` on the hub. */
    fun signatureBase64(derSignature: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(derSignature)

    /**
     * Cheap structural check on an ECDSA signature: DER SEQUENCE of two INTEGERs.
     * Not a verification — just enough to catch a raw (r||s) signature or a
     * truncated buffer before it is shipped to the hub and comes back a bare 401.
     */
    fun looksLikeEcdsaDer(sig: ByteArray): Boolean {
        if (sig.size < 8 || sig.size > 144) return false
        if (sig[0] != 0x30.toByte()) return false
        val declaredLen = sig[1].toInt() and 0xFF
        // P-256 signatures are always short form (< 128 bytes of content).
        if (declaredLen >= 0x80) return false
        if (declaredLen + 2 != sig.size) return false
        if (sig[2] != 0x02.toByte()) return false
        val rLen = sig[3].toInt() and 0xFF
        val sTagAt = 4 + rLen
        if (sTagAt + 1 >= sig.size) return false
        if (sig[sTagAt] != 0x02.toByte()) return false
        val sLen = sig[sTagAt + 1].toInt() and 0xFF
        return sTagAt + 2 + sLen == sig.size
    }

    // ---------------------------------------------------------------- expiry

    /** One minute of slack so a token isn't presented on the very edge of expiry. */
    const val EXPIRY_SKEW_MS = 60_000L

    /** Absolute wall-clock deadline for a token the server says lives [expiresInS] seconds. */
    fun expiryAtMs(nowMs: Long, expiresInS: Long): Long =
        nowMs + (expiresInS.coerceAtLeast(0L) * 1000L)

    /** True while the cached token is still worth sending. */
    fun tokenUsable(expiresAtMs: Long, nowMs: Long): Boolean =
        expiresAtMs > 0L && nowMs < expiresAtMs - EXPIRY_SKEW_MS

    /** Signing the RAW UTF-8 bytes of the nonce string is the whole contract with hub/auth.py. */
    fun nonceBytes(nonce: String): ByteArray = nonce.toByteArray(Charsets.UTF_8)
}
