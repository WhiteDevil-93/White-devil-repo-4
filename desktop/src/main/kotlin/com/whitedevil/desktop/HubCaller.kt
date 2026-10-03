package com.whitedevil.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URI
import java.util.Base64

/**
 * One authenticated way to talk to the hub, shared by the LTX and Wan builders. Relay basic auth, no
 * redirects followed with the password attached, and every failure returned as a [MediaResult.Failure]
 * that carries what the hub itself said (its FastAPI `detail`): the hub's own wording ("The plan has 3
 * clips and this run is set to 20") is the useful part.
 */
class HubCaller(
    hubUrl: String,
    relayUser: String,
    relayPass: String,
    engine: HttpClientEngine? = null,
) : AutoCloseable {
    private val base: String? = runCatching {
        val u = URI(hubUrl.trim())
        if ((u.scheme == "https" || u.scheme == "http") && !u.host.isNullOrBlank()) hubUrl.trim().trimEnd('/') else null
    }.getOrNull()
    private val host: String = base?.let { runCatching { URI(it).authority }.getOrNull() } ?: "(no hub)"
    private val hasPassword = relayPass.isNotEmpty()
    private val authHeader: String? =
        if (relayUser.isBlank() && relayPass.isEmpty()) null
        else "Basic " + Base64.getEncoder().encodeToString("$relayUser:$relayPass".toByteArray(Charsets.UTF_8))

    private val http = HttpClient(engine ?: CIO.create()) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 30_000; socketTimeoutMillis = 30_000 }
    }

    fun <T> bad(message: String): MediaResult<T> = MediaResult.Failure(MediaError(MediaErrorKind.BadResponse, message))

    /** GET when [post] is null, otherwise POST with [post] as the body ([json] sets the content type). */
    suspend fun <T> call(
        path: String,
        timeoutMs: Long,
        post: Any? = null,
        json: Boolean = false,
        parse: (String) -> MediaResult<T>,
    ): MediaResult<T> {
        val root = base ?: return MediaResult.Failure(MediaErrors.config("The Hub URL in Settings is not usable. Check it in Settings."))
        return try {
            val configure: HttpRequestBuilder.() -> Unit = {
                authHeader?.let { header(HttpHeaders.Authorization, it) }
                timeout { requestTimeoutMillis = timeoutMs; socketTimeoutMillis = timeoutMs; connectTimeoutMillis = 10_000 }
                if (post != null) {
                    if (json) contentType(ContentType.Application.Json)
                    setBody(post)
                }
            }
            val response: HttpResponse = if (post == null) http.get(root + path, configure) else http.post(root + path, configure)
            val text = response.bodyAsText()
            val status = response.status.value
            if (status !in 200..299) return failure(status, text)
            try {
                parse(text)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                bad("The hub's answer could not be read (${e.message ?: e.javaClass.simpleName}).")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            // The timeouts are quoted back in the message, so give it the one this call really used.
            MediaResult.Failure(MediaErrors.fromException(MediaOp.Library, e, host, MediaTimeouts(connectMs = 10_000, libraryMs = timeoutMs)))
        }
    }

    /** The hub's own explanation wins for 4xx/5xx (it says what to change); the generic wording covers sign-in problems. */
    private fun failure(status: Int, body: String): MediaResult.Failure {
        val generic = MediaErrors.fromStatus(MediaOp.Library, status, body.take(4000), null, hasPassword)
        val detail = hubDetail(body)
        return MediaResult.Failure(
            if (detail != null && status !in listOf(401, 403)) generic.copy(message = detail, detail = null) else generic,
        )
    }

    override fun close() { http.close() }
}
