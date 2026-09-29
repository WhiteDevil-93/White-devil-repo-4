package com.whitedevil.desktop

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.util.Base64

/** `Authorization: Basic …` for the relay, or null when no password is set (matches :shared's Tools). */
internal fun relayBasicAuthHeader(user: String, pass: String): String? =
    pass.takeIf { it.isNotBlank() }?.let {
        "Basic " + Base64.getEncoder().encodeToString("$user:$it".toByteArray(Charsets.UTF_8))
    }

/**
 * A relay call that did not produce usable data. [message] is fit to show in a
 * panel as-is: it is the hub's own `detail` text or ours, and never carries the
 * password, a request body, or a token.
 *
 * Screens must show this rather than fall back to an empty view. A failed request
 * rendered as "nothing here" is indistinguishable from "no renders", and that exact
 * bug was just fixed across four of the hub's web screens.
 */
class RelayException(
    val kind: Kind,
    message: String,
    val status: Int? = null,
) : Exception(message) {
    enum class Kind {
        /** DNS, refused connection, TLS, a dropped connection: the hub was not heard from. */
        Unreachable,

        /** The hub did not answer in time. */
        Timeout,

        /** 401/403: the relay credentials were refused. */
        Unauthorized,

        /** 404: no such route or resource. */
        NotFound,

        /** 429. */
        RateLimited,

        /** Any other 4xx. */
        Rejected,

        /** 5xx: the hub was reached and failed; [message] carries its reason when it gave one. */
        Server,

        /** A 2xx whose body was not what the route promises (e.g. a proxy's HTML page). */
        BadResponse,
    }
}

/**
 * Relay HTTP for the native screens: base URL, relay basic auth on every request,
 * bounded timeouts, and non-2xx turned into a [RelayException] with a reason.
 *
 * One instance per set of credentials; the Settings screen produces a new
 * `Settings`, and holders of this build a new one.
 */
class RelayHttp(
    hubUrl: String,
    relayUser: String,
    relayPass: String,
    engine: HttpClientEngine? = null,
    private val defaultTimeoutMs: Long = 30_000,
) : AutoCloseable {
    private val base = hubUrl.trim().trimEnd('/')
    private val basicAuth = relayBasicAuthHeader(relayUser, relayPass)

    private val http: HttpClient = run {
        val configure: HttpClientConfig<*>.() -> Unit = {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = defaultTimeoutMs
                socketTimeoutMillis = defaultTimeoutMs
            }
        }
        if (engine != null) HttpClient(engine, configure) else HttpClient(CIO, configure)
    }

    suspend fun getText(path: String, timeoutMs: Long? = null): String =
        execute(timeoutMs) { http.get(base + path) { prepare(this, timeoutMs) } }.text()

    /** The response body, capped at [maxBytes] so a misbehaving route cannot exhaust the heap. */
    suspend fun getBytes(path: String, timeoutMs: Long? = null, maxBytes: Int = 16 * 1024 * 1024): ByteArray =
        execute(timeoutMs) { http.get(base + path) { prepare(this, timeoutMs) } }.bytes(maxBytes)

    suspend fun postJson(path: String, jsonBody: String, timeoutMs: Long? = null): String =
        execute(timeoutMs) {
            http.post(base + path) {
                prepare(this, timeoutMs)
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }
        }.text()

    override fun close() = http.close()

    // ---- plumbing ---------------------------------------------------------------

    private fun prepare(request: HttpRequestBuilder, timeoutMs: Long?) {
        basicAuth?.let { request.header(HttpHeaders.Authorization, it) }
        if (timeoutMs != null) request.timeout {
            requestTimeoutMillis = timeoutMs
            socketTimeoutMillis = timeoutMs
        }
    }

    /** A checked response: non-2xx has already been thrown as [RelayException]. */
    private inner class Ok(val response: HttpResponse) {
        suspend fun text(): String = guarded { response.bodyAsText() }

        suspend fun bytes(maxBytes: Int): ByteArray = guarded {
            // Streamed with a cap rather than bodyAsBytes(): the size is not
            // trusted, and a runaway body should fail cleanly.
            response.bodyAsChannel().toInputStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) {
                        throw RelayException(RelayException.Kind.BadResponse, "The hub sent more than ${maxBytes / (1024 * 1024)} MB for one image; refusing it.")
                    }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }

        private suspend fun <T> guarded(block: suspend () -> T): T = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            throw e
        } catch (e: IOException) {
            throw RelayException(RelayException.Kind.Unreachable, "The connection to the hub dropped mid-response.")
        }
    }

    private suspend fun execute(timeoutMs: Long?, request: suspend () -> HttpResponse): Ok {
        val response = try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            throw timeout(timeoutMs)
        } catch (e: ConnectTimeoutException) {
            throw timeout(timeoutMs)
        } catch (e: SocketTimeoutException) {
            throw timeout(timeoutMs)
        } catch (e: Exception) {
            throw RelayException(RelayException.Kind.Unreachable, "Could not reach the hub at $base: ${e.message ?: e::class.simpleName}")
        }
        if (response.status.isSuccess()) return Ok(response)

        val body = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        throw classify(response.status.value, body)
    }

    private fun timeout(timeoutMs: Long?) = RelayException(
        RelayException.Kind.Timeout,
        "The hub did not answer within ${(timeoutMs ?: defaultTimeoutMs) / 1000}s.",
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** FastAPI errors are `{"detail": "..."}`; anything else (a proxy's HTML page) yields null. */
        internal fun detailOf(body: String): String? = try {
            when (val d = (json.parseToJsonElement(body) as? JsonObject)?.get("detail")) {
                is JsonPrimitive -> d.contentOrNull?.take(300)?.ifBlank { null }
                null -> null
                else -> "the request was rejected as invalid"
            }
        } catch (_: Exception) {
            null
        }

        internal fun classify(status: Int, body: String): RelayException {
            val detail = detailOf(body)
            fun ex(kind: RelayException.Kind, fallback: String) = RelayException(kind, detail ?: fallback, status)
            return when (status) {
                401, 403 -> ex(
                    RelayException.Kind.Unauthorized,
                    "The hub refused the relay credentials (HTTP $status). Check the relay user and password in Settings.",
                )
                404 -> ex(RelayException.Kind.NotFound, "Not found on the hub (HTTP 404).")
                429 -> ex(RelayException.Kind.RateLimited, "The hub is rate limiting requests (HTTP 429); try again shortly.")
                in 400..499 -> ex(RelayException.Kind.Rejected, "The hub rejected the request (HTTP $status).")
                else -> ex(RelayException.Kind.Server, "The hub failed (HTTP $status).")
            }
        }
    }
}
