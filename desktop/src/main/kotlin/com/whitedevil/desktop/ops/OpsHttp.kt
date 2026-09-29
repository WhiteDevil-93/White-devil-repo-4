package com.whitedevil.desktop.ops

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.Base64
import javax.net.ssl.SSLException

/** What went wrong, coarsely, so the UI can word it and callers can reason about it. */
enum class OpsErrorKind { Config, Auth, Http, Timeout, Unreachable, Network, BadJson, BadShape }

/**
 * A failed request, carrying enough to show the operator *why*. Every screen
 * renders this in the panel; none maps it to an empty result.
 */
data class OpsError(
    val message: String,
    val status: Int? = null,
    val kind: OpsErrorKind = OpsErrorKind.Http,
    /** Raw response body when there was one (already length-capped by the caller). */
    val body: String? = null,
) {
    /**
     * True when the hub may have carried the request out even though we saw a
     * failure. A timeout on a "stop" says nothing about whether it stopped, and
     * the UI must not let that read as "it did not happen".
     */
    val mayHaveExecuted: Boolean
        get() = when (kind) {
            OpsErrorKind.Timeout, OpsErrorKind.Network, OpsErrorKind.BadJson -> true
            OpsErrorKind.Http -> status == 502 || status == 504
            else -> false
        }
}

sealed interface OpsResult<out T> {
    data class Ok<T>(val value: T) : OpsResult<T>
    data class Err(val error: OpsError) : OpsResult<Nothing>
}

inline fun <T, R> OpsResult<T>.flatMap(f: (T) -> OpsResult<R>): OpsResult<R> = when (this) {
    is OpsResult.Ok -> f(value)
    is OpsResult.Err -> this
}

inline fun <T, R> OpsResult<T>.map(f: (T) -> R): OpsResult<R> = flatMap { OpsResult.Ok(f(it)) }

/** A 2xx reply: status plus the raw text, with the parsed JSON when it parsed. */
data class HubReply(val status: Int, val rawBody: String, val json: JsonElement?)

/** Longest body kept for display; bodies past this are cut with a marker. */
internal const val MAX_BODY_CHARS = 1500

internal fun capBody(text: String, limit: Int = MAX_BODY_CHARS): String =
    if (text.length > limit) text.take(limit) + "\n… (${text.length - limit} more characters not shown)" else text

/**
 * Shared transport to the relay/hub. Basic auth from Settings, explicit
 * timeouts on every request, redirects NOT followed (a redirect would carry the
 * relay password somewhere we did not choose, and would hide a misconfigured URL).
 *
 * Deliberately not used directly by screens: they get [OpsReader] (GET only —
 * there is no way to POST through it) or [OpsActor] (POST, only ever called from
 * behind the confirmation dialog).
 *
 * @param engine test seam; null means a real CIO engine that this class owns and closes.
 */
class OpsHttp(
    baseUrl: String,
    user: String,
    pass: String,
    engine: HttpClientEngine? = null,
) : AutoCloseable {
    private val base = baseUrl.trim().trimEnd('/')
    private val authHeader: String? =
        if (pass.isNotEmpty()) "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8)) else null

    private val configure: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = DEFAULT_READ_TIMEOUT_MS
            socketTimeoutMillis = DEFAULT_READ_TIMEOUT_MS
        }
    }

    private val client: HttpClient = if (engine == null) HttpClient(CIO, configure) else HttpClient(engine, configure)

    /** The hub host for messages. Never includes credentials. */
    val hostLabel: String get() = base.substringAfter("://", base).substringBefore('/')

    internal suspend fun execute(
        method: HttpMethod,
        path: String,
        jsonBody: String?,
        timeoutMs: Long,
    ): OpsResult<HubReply> {
        if (!(base.startsWith("http://") || base.startsWith("https://"))) {
            return OpsResult.Err(OpsError("Set a valid hub URL (http:// or https://) in Settings.", kind = OpsErrorKind.Config))
        }
        require(path.startsWith("/")) { "path must start with /: $path" }
        val url = base + path
        return try {
            val response = client.request(url) {
                this.method = method
                header(HttpHeaders.Accept, "application/json")
                authHeader?.let { header(HttpHeaders.Authorization, it) }
                if (jsonBody != null) {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody)
                }
                timeout {
                    requestTimeoutMillis = timeoutMs
                    socketTimeoutMillis = timeoutMs
                }
            }
            val status = response.status.value
            val text = response.bodyAsText()
            when {
                status in 200..299 -> OpsResult.Ok(HubReply(status, text, parseJsonOrNull(text)))
                else -> OpsResult.Err(describeHttpFailure(status, text))
            }
        } catch (e: CancellationException) {
            // ktor reports its own request timeout as a cancellation whose cause is the timeout.
            val cause = e.cause
            if (cause is HttpRequestTimeoutException) OpsResult.Err(timeoutError(timeoutMs)) else throw e
        } catch (e: HttpRequestTimeoutException) {
            OpsResult.Err(timeoutError(timeoutMs))
        } catch (e: ConnectTimeoutException) {
            OpsResult.Err(OpsError("Could not connect to $hostLabel within ${CONNECT_TIMEOUT_MS / 1000}s.", kind = OpsErrorKind.Unreachable))
        } catch (e: SocketTimeoutException) {
            OpsResult.Err(timeoutError(timeoutMs))
        } catch (e: UnknownHostException) {
            OpsResult.Err(OpsError("Cannot resolve $hostLabel — check the hub URL and your network.", kind = OpsErrorKind.Unreachable))
        } catch (e: ConnectException) {
            OpsResult.Err(OpsError("Cannot connect to $hostLabel: ${e.message ?: "connection refused"}", kind = OpsErrorKind.Unreachable))
        } catch (e: SSLException) {
            OpsResult.Err(OpsError("TLS error talking to $hostLabel: ${e.message ?: e.javaClass.simpleName}", kind = OpsErrorKind.Network))
        } catch (e: IOException) {
            OpsResult.Err(OpsError("Network error talking to $hostLabel: ${e.message ?: e.javaClass.simpleName}", kind = OpsErrorKind.Network))
        } catch (e: Exception) {
            OpsResult.Err(OpsError("Unexpected failure: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim(), kind = OpsErrorKind.Network))
        }
    }

    private fun timeoutError(timeoutMs: Long) = OpsError(
        "No answer from $hostLabel within ${timeoutMs / 1000}s (timed out).",
        kind = OpsErrorKind.Timeout,
    )

    override fun close() {
        client.close()
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val DEFAULT_READ_TIMEOUT_MS = 30_000L
    }
}

/**
 * GET-only view of the hub. There is intentionally no POST here: a screen that
 * only holds an OpsReader cannot start, stop or spend anything.
 */
class OpsReader(private val http: OpsHttp) {
    suspend fun getJson(path: String, timeoutMs: Long = OpsHttp.DEFAULT_READ_TIMEOUT_MS): OpsResult<JsonElement> =
        http.execute(HttpMethod.Get, path, null, timeoutMs).flatMap { reply ->
            reply.json?.let { OpsResult.Ok(it) }
                ?: OpsResult.Err(
                    OpsError(
                        "The hub answered HTTP ${reply.status} but the body is not a JSON object or array: ${snippet(reply.rawBody)}",
                        status = reply.status,
                        kind = OpsErrorKind.BadJson,
                        body = capBody(reply.rawBody),
                    ),
                )
        }
}

/**
 * POST side. Only ever invoked from an [ActionSpec.run] lambda, i.e. after the
 * operator confirmed in the dialog — never from composition or a LaunchedEffect.
 */
class OpsActor(private val http: OpsHttp) {
    suspend fun post(path: String, body: JsonObject? = null, timeoutMs: Long = DEFAULT_ACTION_TIMEOUT_MS): OpsResult<HubReply> =
        http.execute(HttpMethod.Post, path, body?.toString(), timeoutMs)

    companion object {
        const val DEFAULT_ACTION_TIMEOUT_MS = 90_000L
    }
}

/**
 * Parses a hub reply. Only a JSON object or array counts: every hub route returns one,
 * and kotlinx-serialization reads a bare word such as `<html>` or `nope` as an unquoted
 * literal even in strict mode, which would let a proxy's error page pass as "valid JSON".
 */
internal fun parseJsonOrNull(text: String): JsonElement? {
    if (text.isBlank()) return null
    val parsed = runCatching { opsJson.parseToJsonElement(text) }.getOrNull()
    return parsed?.takeIf { it is JsonObject || it is kotlinx.serialization.json.JsonArray }
}

internal fun snippet(text: String, limit: Int = 160): String {
    val flat = text.trim().replace(Regex("\\s+"), " ")
    return if (flat.isEmpty()) "(empty body)" else if (flat.length > limit) flat.take(limit) + "…" else flat
}

/**
 * Text of the hub's error, from FastAPI's `{"detail": ...}` (string, or a list of
 * validation problems), or the vendor-style `msg`/`error`/`message`; otherwise the
 * raw body. Null when the body says nothing (Caddy's 401 has an empty body).
 */
internal fun extractDetail(body: String): String? {
    if (body.isBlank()) return null
    val obj = parseJsonOrNull(body) as? JsonObject
        ?: return snippet(body, 300)
    val detail = obj["detail"]
    val text = when {
        detail is kotlinx.serialization.json.JsonPrimitive -> detail.content
        detail is kotlinx.serialization.json.JsonArray -> detail.joinToString("; ") { item ->
            val o = item as? JsonObject
            val msg = o?.str("msg")
            val loc = o?.arr("loc")?.strings()?.joinToString(".")
            if (msg != null) (if (loc.isNullOrEmpty()) msg else "$loc: $msg") else item.toString()
        }
        detail != null -> detail.toString()
        else -> obj.nonBlankStr("msg") ?: obj.nonBlankStr("error") ?: obj.nonBlankStr("message")
    }
    return (text ?: snippet(body, 300)).take(400)
}

/** Maps a non-2xx reply to an error that names the status and the hub's own words. */
internal fun describeHttpFailure(status: Int, body: String): OpsError {
    val detail = extractDetail(body)
    val capped = capBody(body).ifBlank { null }
    return when (status) {
        401, 403 -> OpsError(
            if (detail != null) "HTTP $status: $detail (if this persists, check the relay user and password in Settings)"
            else "HTTP $status: the relay rejected the sign-in. Check the relay user and password in Settings.",
            status = status, kind = OpsErrorKind.Auth, body = capped,
        )
        404 -> OpsError("HTTP 404: ${detail ?: "not found"} — the hub may be an older build without this route.", status = status, body = capped)
        429 -> OpsError("HTTP 429: rate limited${detail?.let { " — $it" } ?: ""}. Wait a moment before retrying.", status = status, body = capped)
        in 300..399 -> OpsError("HTTP $status: the hub redirected the request (not followed). Check the hub URL in Settings.", status = status, body = capped)
        in 500..599 -> OpsError("HTTP $status from the hub: ${detail ?: "(no detail)"}", status = status, body = capped)
        else -> OpsError("HTTP $status: ${detail ?: "(no detail)"}", status = status, body = capped)
    }
}
