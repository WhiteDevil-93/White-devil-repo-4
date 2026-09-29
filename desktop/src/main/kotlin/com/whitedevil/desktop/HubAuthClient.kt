package com.whitedevil.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.util.Base64

// ---- wire types for hub/auth.py -------------------------------------------------

@Serializable
internal data class EnrolRequest(
    val name: String,
    @SerialName("public_key_pem") val publicKeyPem: String,
    @SerialName("enrol_code") val enrolCode: String? = null,
)

@Serializable
data class EnrolResponse(val id: String, val name: String, val created: Double = 0.0)

@Serializable
internal data class ChallengeRequest(@SerialName("device_id") val deviceId: String)

@Serializable
data class ChallengeResponse(val nonce: String, @SerialName("expires_in") val expiresIn: Long = 0) {
    // The nonce is what gets signed; keep it out of logs like the rest.
    override fun toString() = "ChallengeResponse(expiresIn=$expiresIn)"
}

@Serializable
internal data class TokenRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("signature_b64") val signatureB64: String,
)

@Serializable
data class TokenResponse(
    val token: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("device_id") val deviceId: String? = null,
) {
    override fun toString() = "TokenResponse(expiresIn=$expiresIn, deviceId=$deviceId)"
}

@Serializable
data class WhoamiResponse(
    val authenticated: Boolean,
    @SerialName("device_id") val deviceId: String? = null,
    val name: String? = null,
    @SerialName("devices_enrolled") val devicesEnrolled: Int? = null,
)

/**
 * A hub call that did not succeed, classified so the UI can say something useful
 * instead of "HTTP 403". [message] is safe to show: it comes from the hub's own
 * `detail` text or from us, never from a request body.
 */
class HubAuthException(
    val kind: Kind,
    message: String,
    val status: Int? = null,
    val retryAfterSeconds: Int? = null,
) : Exception(message) {
    enum class Kind {
        /** Network failure or timeout: the hub was never heard from. */
        Unreachable,

        /** The hub has no such endpoint — it predates device auth (404/405/501). */
        Unsupported,

        /** 401: the relay credentials, or the signature, were refused. */
        Unauthorized,

        /** 429: slow down. */
        RateLimited,

        /** 403 on enrolment: an enrolment code is required, unknown, used or expired. */
        EnrolCode,

        /** 409: this exact public key is already enrolled. */
        AlreadyEnrolled,

        /** 404 "Unknown device": the hub has no device with this id (revoked, or never enrolled). */
        UnknownDevice,

        /** Any other 4xx the hub explained. */
        Rejected,

        /** 5xx, or a 2xx body we could not read. */
        Server,
    }
}

/**
 * Ktor client for the four device-auth routes in hub/auth.py.
 *
 * Relay basic auth is sent on every request that can carry it, because Caddy still
 * fronts the hub and enrolment is "guarded by whatever already guards the hub".
 * Device tokens are an addition. The one request that cannot also carry it is
 * [whoami], since a bearer token occupies the same Authorization header.
 *
 * Bodies are built with kotlinx-serialization — never by string concatenation,
 * which is how an earlier attempt shipped a broken escaper.
 */
class HubAuthClient(
    hubUrl: String,
    relayUser: String,
    relayPass: String,
    engine: HttpClientEngine? = null,
) : AutoCloseable {
    private val base = hubUrl.trim().trimEnd('/')
    private val basicAuth: String? = relayPass.takeIf { it.isNotBlank() }?.let {
        "Basic " + Base64.getEncoder().encodeToString("$relayUser:$it".toByteArray(Charsets.UTF_8))
    }

    private val http: HttpClient = run {
        val configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
            }
        }
        if (engine != null) HttpClient(engine, configure) else HttpClient(CIO, configure)
    }

    /** Registers a public key. [enrolCode] is only needed when the hub has `require_enrol_code` on. */
    suspend fun enrol(name: String, publicKeyPem: String, enrolCode: String? = null): EnrolResponse =
        post(
            "/api/auth/devices",
            json.encodeToString(EnrolRequest.serializer(), EnrolRequest(name, publicKeyPem, enrolCode?.trim()?.ifEmpty { null })),
        ) { json.decodeFromString(EnrolResponse.serializer(), it) }

    /** Asks for a nonce to sign. Single use on the hub: it is consumed by [token], win or lose. */
    suspend fun challenge(deviceId: String): ChallengeResponse =
        post(
            "/api/auth/challenge",
            json.encodeToString(ChallengeRequest.serializer(), ChallengeRequest(deviceId)),
        ) { json.decodeFromString(ChallengeResponse.serializer(), it) }

    suspend fun token(deviceId: String, signatureB64: String): TokenResponse =
        post(
            "/api/auth/token",
            json.encodeToString(TokenRequest.serializer(), TokenRequest(deviceId, signatureB64)),
        ) { json.decodeFromString(TokenResponse.serializer(), it) }

    /** Checks a token. Bearer only — see the class comment. */
    suspend fun whoami(token: String): WhoamiResponse = call {
        http.get(base + "/api/auth/whoami") { header(HttpHeaders.Authorization, "Bearer $token") }
    }.let { decode(it, WhoamiResponse.serializer()) }

    override fun close() = http.close()

    // ---- plumbing ---------------------------------------------------------------

    private suspend fun <T> post(path: String, body: String, parse: (String) -> T): T {
        val response = call {
            http.post(base + path) {
                contentType(ContentType.Application.Json)
                basicAuth?.let { header(HttpHeaders.Authorization, it) }
                setBody(body)
            }
        }
        return decode(response) { parse(it) }
    }

    private suspend fun call(request: suspend () -> HttpResponse): Checked {
        val response = try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Timeouts, DNS, refused connections, TLS. The hub was never heard from.
            throw HubAuthException(
                HubAuthException.Kind.Unreachable,
                "Could not reach the hub at $base: ${e.message ?: e::class.simpleName}",
            )
        }
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw HubAuthException(HubAuthException.Kind.Unreachable, "The connection to the hub dropped mid-response.")
        }
        if (response.status.isSuccess()) return Checked(text)
        throw classify(response.status.value, text, response.headers[HttpHeaders.RetryAfter])
    }

    private class Checked(val body: String)

    private fun <T> decode(checked: Checked, parse: (String) -> T): T = try {
        parse(checked.body)
    } catch (e: Exception) {
        throw HubAuthException(HubAuthException.Kind.Server, "The hub answered, but not in the shape expected.")
    }

    private fun <T> decode(checked: Checked, serializer: kotlinx.serialization.KSerializer<T>): T =
        decode(checked) { json.decodeFromString(serializer, it) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** FastAPI's error body is `{"detail": "..."}`; anything else (a Caddy 401 page) yields null. */
        internal fun detailOf(body: String): String? = try {
            when (val d = (json.parseToJsonElement(body) as? JsonObject)?.get("detail")) {
                is JsonPrimitive -> d.contentOrNull?.take(300)
                null -> null
                else -> "the request was rejected as invalid" // 422: a list of validation errors
            }
        } catch (_: Exception) {
            null
        }

        internal fun classify(status: Int, body: String, retryAfter: String?): HubAuthException {
            val detail = detailOf(body)
            fun ex(kind: HubAuthException.Kind, fallback: String, retry: Int? = null) =
                HubAuthException(kind, detail ?: fallback, status, retry)

            return when {
                status == 429 -> {
                    val secs = retryAfter?.trim()?.toIntOrNull()
                    ex(HubAuthException.Kind.RateLimited, "The hub is rate limiting this device; try again shortly.", secs)
                }
                status == 404 && detail?.startsWith("Unknown device", ignoreCase = true) == true ->
                    ex(HubAuthException.Kind.UnknownDevice, "The hub does not know this device.")
                status == 404 || status == 405 || status == 501 ->
                    ex(HubAuthException.Kind.Unsupported, "The hub has no device-auth endpoint (it predates this feature).")
                status == 401 ->
                    ex(HubAuthException.Kind.Unauthorized, "The hub refused the credentials (check the relay user and password).")
                status == 403 && detail?.contains("code", ignoreCase = true) == true ->
                    ex(HubAuthException.Kind.EnrolCode, "The hub requires an enrolment code.")
                status == 409 ->
                    ex(HubAuthException.Kind.AlreadyEnrolled, "That public key is already enrolled.")
                status in 400..499 ->
                    ex(HubAuthException.Kind.Rejected, "The hub rejected the request (HTTP $status).")
                else ->
                    ex(HubAuthException.Kind.Server, "The hub failed (HTTP $status).")
            }
        }
    }
}
