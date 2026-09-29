package com.whitedevil.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentLength
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.util.Base64

// ---------------------------------------------------------------------------
// Wire types. Requests are encoded and responses decoded by kotlinx-serialization,
// so a device name with quotes, newlines or emoji is escaped by the encoder and
// never by string concatenation.
// ---------------------------------------------------------------------------

@Serializable
internal data class EnrolRequest(
    val name: String,
    @SerialName("public_key_pem") val publicKeyPem: String,
    @SerialName("enrol_code") val enrolCode: String? = null,
)

@Serializable
internal data class ChallengeRequest(@SerialName("device_id") val deviceId: String)

@Serializable
internal data class TokenRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("signature_b64") val signatureB64: String,
)

/** hub: POST /api/auth/devices -> {id, name, created} */
@Serializable
data class EnrolResponse(val id: String, val name: String, val created: Double? = null)

/** hub: POST /api/auth/challenge -> {nonce, expires_in} */
@Serializable
data class ChallengeResponse(val nonce: String, @SerialName("expires_in") val expiresIn: Long)

/** hub: POST /api/auth/token -> {token, expires_in, device_id}. The token is a credential: redacted from toString. */
@Serializable
data class TokenResponse(
    val token: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("device_id") val deviceId: String? = null,
) {
    override fun toString() = "TokenResponse(<redacted>, expiresIn=$expiresIn, deviceId=$deviceId)"
}

/** hub: GET /api/auth/whoami. device_id and name are absent when the token was not accepted. */
@Serializable
data class WhoamiResponse(
    val authenticated: Boolean,
    @SerialName("device_id") val deviceId: String? = null,
    val name: String? = null,
    @SerialName("devices_enrolled") val devicesEnrolled: Int? = null,
)

/** hub: GET /api/auth/config (only what the desktop needs). */
@Serializable
data class HubAuthConfig(
    @SerialName("require_enrol_code") val requireEnrolCode: Boolean = false,
    @SerialName("forward_auth_mode") val forwardAuthMode: String? = null,
    @SerialName("devices_enrolled") val devicesEnrolled: Int? = null,
)

// ---------------------------------------------------------------------------
// Typed results: no call below throws for a hub problem.
// ---------------------------------------------------------------------------

sealed interface HubAuthResult<out T> {
    data class Success<T>(val value: T) : HubAuthResult<T>
}

/** Every way a hub call can fail. Each is a value the UI can word for a human. */
sealed interface HubAuthError : HubAuthResult<Nothing> {
    /**
     * 404 (or 405/501) on a device-auth route: this hub predates device auth, or
     * this is not the hub. The caller carries on over basic auth exactly as before.
     */
    data object NoDeviceAuth : HubAuthError

    /** 404 "Unknown device" from challenge/token: the hub has no such device id (revoked, or hub reset). */
    data class UnknownDevice(val detail: String?) : HubAuthError

    /**
     * 401. On challenge/enrol this is the relay refusing the user name or password;
     * on token it is the hub saying the signature does not match the enrolled key.
     */
    data class Unauthorized(val detail: String?) : HubAuthError

    /** 403: an enrolment code is required, unknown, used or expired. */
    data class Forbidden(val detail: String?) : HubAuthError

    /** 409: this public key is already enrolled. */
    data class Conflict(val detail: String?) : HubAuthError

    /** 400/422: the hub understood the request and refused its content (bad PEM, no outstanding challenge...). */
    data class BadRequest(val detail: String?) : HubAuthError

    /** 429. [retryAfterSeconds] is the hub's Retry-After, null if absent or not a plain number of seconds. */
    data class RateLimited(val retryAfterSeconds: Int?, val detail: String?) : HubAuthError

    /** Any other non-2xx: 5xx, an unfollowed redirect, etc. */
    data class HttpError(val status: Int, val detail: String?) : HubAuthError

    /** Could not reach the hub, or it did not answer in time. */
    data class Network(val message: String) : HubAuthError

    /** The configured hub URL is unusable. Nothing was sent. */
    data class BadConfig(val message: String) : HubAuthError

    /** A 2xx whose body was not what the protocol promises. */
    data class BadResponse(val message: String) : HubAuthError
}

// ---------------------------------------------------------------------------
// The client
// ---------------------------------------------------------------------------

/**
 * Thin client for hub/auth.py.
 *
 * ADDITIVE: every request except `whoami` carries the relay's basic auth, because
 * Caddy still fronts the hub and enrolment is "guarded by whatever already guards
 * the hub". The device token is an addition, never a replacement.
 *
 * `whoami` is the one exception. It must send `Authorization: Bearer <token>`, and a
 * request has only one Authorization header, so it cannot also carry basic auth.
 * Behind a Caddy that still enforces basic auth, the proxy answers that request
 * before the hub sees it; callers must treat any whoami error as "not confirmed",
 * never as a failure of anything else. The desktop's flows do not call it.
 *
 * Redirects are NOT followed: a redirect would re-send the relay password to
 * wherever it points. A hub URL that redirects surfaces as an [HubAuthError.HttpError].
 *
 * Never logs. Never puts a password, token or signature in an error message.
 *
 * @param engine test seam (ktor-client-mock); defaults to CIO.
 */
class HubAuthClient(
    baseUrl: String,
    private val relayUser: String,
    private val relayPass: String,
    engine: HttpClientEngine? = null,
) : AutoCloseable {

    /** The normalised hub URL (no trailing slash), or null if [baseUrl] is unusable. */
    val normalizedBaseUrl: String? = normalizeBaseUrl(baseUrl)

    private val http = HttpClient(engine ?: CIO.create()) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    private val basicAuthHeader: String? =
        if (relayUser.isBlank()) null
        else "Basic " + Base64.getEncoder().encodeToString("$relayUser:$relayPass".toByteArray(Charsets.UTF_8))

    /** Does this hub have device auth, and does it demand an enrolment code? (GET /api/auth/config) */
    suspend fun config(): HubAuthResult<HubAuthConfig> =
        call(Step.PROBE, HttpMethod.Get, "/api/auth/config", null, basicAuthHeader) {
            json.decodeFromString(HubAuthConfig.serializer(), it)
        }

    /**
     * GET /api/auth/devices: exists on every hub with device auth, including ones that
     * predate /config. Used only as a "is device auth here at all" probe.
     */
    suspend fun probeDevices(): HubAuthResult<Unit> =
        call(Step.PROBE, HttpMethod.Get, "/api/auth/devices", null, basicAuthHeader) { }

    /** POST /api/auth/devices. [enrolCode] is omitted from the body when null or blank. */
    suspend fun enrol(name: String, publicKeyPem: String, enrolCode: String?): HubAuthResult<EnrolResponse> {
        val body = json.encodeToString(
            EnrolRequest.serializer(),
            EnrolRequest(name = name, publicKeyPem = publicKeyPem, enrolCode = enrolCode?.trim()?.ifEmpty { null }),
        )
        return call(Step.ENROL, HttpMethod.Post, "/api/auth/devices", body, basicAuthHeader) {
            val r = json.decodeFromString(EnrolResponse.serializer(), it)
            require(r.id.isNotBlank()) { "empty device id" }
            r
        }
    }

    /** POST /api/auth/challenge. Single use; the hub consumes it whether or not the signature is good. */
    suspend fun challenge(deviceId: String): HubAuthResult<ChallengeResponse> {
        val body = json.encodeToString(ChallengeRequest.serializer(), ChallengeRequest(deviceId))
        return call(Step.CHALLENGE, HttpMethod.Post, "/api/auth/challenge", body, basicAuthHeader) {
            val r = json.decodeFromString(ChallengeResponse.serializer(), it)
            require(r.nonce.isNotBlank()) { "empty nonce" }
            r
        }
    }

    /** POST /api/auth/token. */
    suspend fun token(deviceId: String, signatureB64: String): HubAuthResult<TokenResponse> {
        val body = json.encodeToString(TokenRequest.serializer(), TokenRequest(deviceId, signatureB64))
        return call(Step.TOKEN, HttpMethod.Post, "/api/auth/token", body, basicAuthHeader) {
            val r = json.decodeFromString(TokenResponse.serializer(), it)
            require(r.token.isNotBlank()) { "empty token" }
            r
        }
    }

    /** GET /api/auth/whoami with the Bearer token. See the class note: it cannot also send basic auth. */
    suspend fun whoami(token: String): HubAuthResult<WhoamiResponse> =
        call(Step.WHOAMI, HttpMethod.Get, "/api/auth/whoami", null, "Bearer $token") {
            json.decodeFromString(WhoamiResponse.serializer(), it)
        }

    override fun close() {
        http.close()
    }

    private enum class Step { PROBE, ENROL, CHALLENGE, TOKEN, WHOAMI }

    private suspend fun <T> call(
        step: Step,
        method: HttpMethod,
        path: String,
        body: String?,
        authorization: String?,
        decode: (String) -> T,
    ): HubAuthResult<T> {
        val base = normalizedBaseUrl
            ?: return HubAuthError.BadConfig(
                "The hub URL is not usable. It must start with https:// (or http://) and must not contain a user name or password.",
            )

        val response: HttpResponse
        val text: String
        try {
            response = http.request("$base$path") {
                this.method = method
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                if (authorization != null) header(HttpHeaders.Authorization, authorization)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            val length = response.contentLength()
            if (length != null && length > MAX_RESPONSE_BYTES) {
                return HubAuthError.BadResponse("The hub's reply was unexpectedly large.")
            }
            text = response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return HubAuthError.Network(describe(e))
        }

        val status = response.status.value
        val detail = extractDetail(text)
        return when {
            status in 200..299 -> try {
                HubAuthResult.Success(decode(text))
            } catch (_: SerializationException) {
                HubAuthError.BadResponse("The hub's reply was not in the expected format.")
            } catch (_: IllegalArgumentException) {
                // Also what require() throws for an empty id/nonce/token.
                HubAuthError.BadResponse("The hub's reply was not in the expected format.")
            }
            status == 404 || status == 405 || status == 501 ->
                if (status == 404 && (step == Step.CHALLENGE || step == Step.TOKEN) &&
                    detail?.startsWith("Unknown device", ignoreCase = true) == true
                ) HubAuthError.UnknownDevice(detail)
                else HubAuthError.NoDeviceAuth
            status == 401 -> HubAuthError.Unauthorized(detail)
            status == 403 -> HubAuthError.Forbidden(detail)
            status == 409 -> HubAuthError.Conflict(detail)
            status == 400 || status == 422 -> HubAuthError.BadRequest(detail)
            status == 429 -> HubAuthError.RateLimited(retryAfterSeconds(response), detail)
            else -> HubAuthError.HttpError(status, detail)
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException ->
            "The hub did not answer in time."
        else -> {
            val msg = e.message?.lineSequence()?.firstOrNull()?.take(200)?.trim().orEmpty()
            if (msg.isEmpty()) "Could not reach the hub (${e.javaClass.simpleName})." else "Could not reach the hub: $msg"
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val MAX_RESPONSE_BYTES = 1_000_000L
        private const val MAX_DETAIL_CHARS = 200
        private const val MAX_RETRY_AFTER_S = 86_400

        private val json = Json {
            ignoreUnknownKeys = true
            // enrol_code is simply absent when there is none, rather than "enrol_code":null.
            explicitNulls = false
        }

        /**
         * The hub URL without a trailing slash, or null if it cannot be used: blank,
         * not http(s), no host, or carrying `user:pass@`. Credentials belong in the
         * relay fields; in the URL they would end up in exception text.
         */
        fun normalizeBaseUrl(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val uri = try {
                URI(trimmed)
            } catch (_: Exception) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "https" && scheme != "http") return null
            if (uri.host.isNullOrEmpty()) return null
            if (uri.userInfo != null) return null
            return trimmed
        }

        /**
         * The human-readable `detail` from a FastAPI error body, or null. Only that field is
         * surfaced: an arbitrary proxy error page must never be echoed into the UI.
         */
        internal fun extractDetail(body: String): String? {
            if (body.isBlank()) return null
            val root = try {
                json.parseToJsonElement(body)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            val detail = (root as? JsonObject)?.get("detail") ?: return null
            val text = when (detail) {
                is JsonPrimitive -> detail.contentOrNull
                // FastAPI 422: a list of {loc, msg, type}. The first message is enough.
                is JsonArray -> (detail.firstOrNull() as? JsonObject)?.get("msg")?.let { (it as? JsonPrimitive)?.contentOrNull }
                else -> null
            } ?: return null
            val flat = HelloHelperOutput.clean(text)
            return flat.take(MAX_DETAIL_CHARS).takeIf { it.isNotEmpty() }
        }

        /** Retry-After as whole seconds. The hub sends an integer; an HTTP-date is not attempted. */
        internal fun retryAfterSeconds(response: HttpResponse): Int? =
            response.headers[HttpHeaders.RetryAfter]?.trim()?.toIntOrNull()?.coerceIn(0, MAX_RETRY_AFTER_S)
    }
}
