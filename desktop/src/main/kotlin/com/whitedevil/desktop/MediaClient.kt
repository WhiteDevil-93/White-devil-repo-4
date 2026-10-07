package com.whitedevil.desktop

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * Native client for the hub's media API (hub/media.py):
 *
 *   GET /api/media/library        JSON list of groups, each with its clips
 *   GET /api/media/thumb/{name}   image/jpeg, ~480 px wide, made on demand by ffmpeg
 *   GET /api/media/contact/{name} image/jpeg contact sheet (4 columns of frames)
 *
 * Authenticates with the relay's HTTP basic auth (Settings.relayUser/relayPass).
 * No WebView, no HTML: JSON and image bytes only.
 *
 * Every method returns a [MediaResult]; a request that fails for any reason is a
 * [MediaResult.Failure] with a user-facing [MediaError], never an empty success.
 * Only coroutine cancellation is rethrown (the caller stopped caring, e.g. the
 * thumbnail scrolled out of view).
 */
class MediaClient(
    hubUrl: String,
    relayUser: String,
    relayPass: String,
    engine: HttpClientEngine? = null,
    private val timeouts: MediaTimeouts = MediaTimeouts(),
) : AutoCloseable {

    private sealed interface Hub {
        data class Valid(val base: String, val host: String) : Hub
        data class Invalid(val error: MediaError) : Hub
    }

    private class Raw(val contentType: String?, val bytes: ByteArray)

    private val hub: Hub = parseHub(hubUrl)
    private val hasPassword = relayPass.isNotEmpty()

    // Sent only when there is something to send; the relay's 401 then says so plainly.
    private val authHeader: String? =
        if (relayUser.isBlank() && relayPass.isEmpty()) {
            null
        } else {
            "Basic " + Base64.getEncoder().encodeToString("$relayUser:$relayPass".toByteArray(Charsets.UTF_8))
        }

    private val http = HttpClient(engine ?: CIO.create()) {
        expectSuccess = false
        // Never follow a redirect with credentials attached: a 3xx is reported to the
        // user instead, so the password cannot be replayed to another host.
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = timeouts.connectMs
            requestTimeoutMillis = timeouts.libraryMs
            socketTimeoutMillis = timeouts.libraryMs
        }
    }

    /** Host (and port) shown in the UI, or a placeholder when the URL is unusable. */
    val hubLabel: String get() = (hub as? Hub.Valid)?.host ?: "(no hub)"

    suspend fun library(): MediaResult<ParsedLibrary> =
        when (val r = fetch(MediaOp.Library, "/api/media/library", MAX_LIBRARY_BYTES)) {
            is MediaResult.Failure -> r
            is MediaResult.Ok -> withContext(Dispatchers.Default) {
                try {
                    MediaResult.Ok(MediaParser.parseLibrary(String(r.value.bytes, Charsets.UTF_8)))
                } catch (e: MediaParseException) {
                    MediaResult.Failure(MediaError(MediaErrorKind.BadResponse, e.message ?: "Unreadable library response."))
                }
            }
        }

    /** JPEG bytes of the clip's thumbnail. */
    suspend fun thumb(name: String): MediaResult<ByteArray> =
        image(MediaOp.Thumb, "/api/media/thumb/", name, MAX_THUMB_BYTES)

    /** JPEG bytes of the clip's contact sheet. */
    suspend fun contactSheet(name: String): MediaResult<ByteArray> =
        image(MediaOp.ContactSheet, "/api/media/contact/", name, MAX_CONTACT_BYTES)

    /**
     * Streams the video `/clips/<name>` (the path the phone's Download button uses) to [dest] and
     * returns its size. Written to a `.part` file and renamed only when complete, so a dropped
     * connection never leaves a truncated file that looks like a finished clip.
     */
    suspend fun downloadClip(name: String, dest: Path): MediaResult<Long> {
        val target = when (val h = hub) {
            is Hub.Invalid -> return MediaResult.Failure(h.error)
            is Hub.Valid -> h
        }
        if (name.isEmpty()) {
            return MediaResult.Failure(MediaError(MediaErrorKind.ClientError, "A clip with an empty name cannot be fetched."))
        }
        val op = MediaOp.Clip
        val ms = timeouts.forOp(op)
        val part = dest.resolveSibling(dest.fileName.toString() + ".part")
        return try {
            http.prepareGet(target.base + "/clips/" + encodePathSegment(name)) {
                authHeader?.let { header(HttpHeaders.Authorization, it) }
                timeout { requestTimeoutMillis = ms; connectTimeoutMillis = timeouts.connectMs; socketTimeoutMillis = ms }
            }.execute { response ->
                val status = response.status.value
                if (status !in 200..299) {
                    return@execute MediaResult.Failure(
                        MediaErrors.fromStatus(op, status, errorText(response), response.headers[HttpHeaders.Location], hasPassword),
                    )
                }
                val type = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase()
                if (type != null && (type.startsWith("text/") || type == "application/json")) {
                    return@execute MediaResult.Failure(MediaError(MediaErrorKind.BadResponse, "The hub sent $type instead of a video."))
                }
                withContext(Dispatchers.IO) {
                    Files.createDirectories(dest.parent)
                    val written = response.bodyAsChannel().toInputStream().use { input ->
                        Files.newOutputStream(part).use { out -> input.copyTo(out) }
                    }
                    if (written == 0L) {
                        Files.deleteIfExists(part)
                        return@withContext MediaResult.Failure(MediaError(MediaErrorKind.BadResponse, "The hub sent an empty file."))
                    }
                    Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING)
                    MediaResult.Ok(written)
                }
            }
        } catch (e: Exception) {
            withContext(NonCancellable) { runCatching { Files.deleteIfExists(part) } }
            currentCoroutineContext().ensureActive()
            MediaResult.Failure(MediaErrors.fromException(op, e, target.host, timeouts))
        }
    }

    private suspend fun image(op: MediaOp, prefix: String, name: String, maxBytes: Long): MediaResult<ByteArray> {
        if (name.isEmpty()) {
            return MediaResult.Failure(MediaError(MediaErrorKind.ClientError, "A clip with an empty name cannot be fetched."))
        }
        val raw = when (val r = fetch(op, prefix + encodePathSegment(name), maxBytes)) {
            is MediaResult.Failure -> return r
            is MediaResult.Ok -> r.value
        }
        if (raw.bytes.isEmpty()) {
            return MediaResult.Failure(MediaError(MediaErrorKind.BadResponse, "The hub answered with an empty body instead of an image."))
        }
        val type = raw.contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && !type.startsWith("image/")) {
            return MediaResult.Failure(
                MediaError(MediaErrorKind.BadResponse, "The hub sent $type instead of an image."),
            )
        }
        return MediaResult.Ok(raw.bytes)
    }

    private suspend fun fetch(op: MediaOp, path: String, maxBytes: Long): MediaResult<Raw> {
        val target = when (val h = hub) {
            is Hub.Invalid -> return MediaResult.Failure(h.error)
            is Hub.Valid -> h
        }
        val ms = timeouts.forOp(op)
        return try {
            val response = http.get(target.base + path) {
                authHeader?.let { header(HttpHeaders.Authorization, it) }
                timeout {
                    requestTimeoutMillis = ms
                    connectTimeoutMillis = timeouts.connectMs
                    socketTimeoutMillis = ms
                }
            }
            val status = response.status.value
            if (status !in 200..299) {
                return MediaResult.Failure(
                    MediaErrors.fromStatus(op, status, errorText(response), response.headers[HttpHeaders.Location], hasPassword),
                )
            }
            val declared = response.contentLength()
            if (declared != null && declared > maxBytes) {
                return MediaResult.Failure(tooLarge(declared, maxBytes))
            }
            val bytes = response.body<ByteArray>()
            if (bytes.size > maxBytes) return MediaResult.Failure(tooLarge(bytes.size.toLong(), maxBytes))
            MediaResult.Ok(Raw(response.headers[HttpHeaders.ContentType], bytes))
        } catch (e: Exception) {
            // The caller cancelled (scrolled away, screen closed): not an error, propagate.
            // A timeout also surfaces as a CancellationException from inside ktor, but then the
            // caller is still active and it is mapped to a Timeout error below.
            currentCoroutineContext().ensureActive()
            MediaResult.Failure(MediaErrors.fromException(op, e, target.host, timeouts))
        }
    }

    private suspend fun errorText(response: HttpResponse): String? = try {
        response.bodyAsText().take(4000)
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        null // the status alone is still reported
    }

    private fun tooLarge(size: Long, max: Long) = MediaError(
        MediaErrorKind.TooLarge,
        "The hub sent $size bytes; refusing anything over $max.",
    )

    override fun close() {
        http.close()
    }

    companion object {
        private const val MAX_LIBRARY_BYTES = 32L * 1024 * 1024
        private const val MAX_THUMB_BYTES = 8L * 1024 * 1024
        private const val MAX_CONTACT_BYTES = 24L * 1024 * 1024

        /**
         * Percent-encodes one URL path segment: every UTF-8 byte except the RFC 3986
         * unreserved set becomes %XX. Clip names are file names, so they can hold spaces,
         * '#', '?', '%', '+', '&', non-ASCII text and (hostile or not) '/' and '\\'.
         * A '/' is encoded as %2F rather than left to split the path.
         */
        fun encodePathSegment(segment: String): String {
            val sb = StringBuilder(segment.length + 8)
            for (b in segment.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val unreserved = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code ||
                    c in '0'.code..'9'.code || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
                if (unreserved) {
                    sb.append(c.toChar())
                } else {
                    sb.append('%')
                    sb.append("0123456789ABCDEF"[c ushr 4])
                    sb.append("0123456789ABCDEF"[c and 0x0F])
                }
            }
            return sb.toString()
        }

        private fun parseHub(raw: String): Hub {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                return Hub.Invalid(MediaErrors.config("No hub URL is set. Add it in Settings."))
            }
            val uri = try {
                URI(trimmed)
            } catch (e: Exception) {
                return Hub.Invalid(MediaErrors.config("The Hub URL in Settings is not a valid address: ${MediaParser.snippet(trimmed, 60)}"))
            }
            val scheme = uri.scheme?.lowercase()
            if ((scheme != "https" && scheme != "http") || uri.host.isNullOrBlank()) {
                return Hub.Invalid(
                    MediaErrors.config("The Hub URL in Settings must look like https://host (got: ${MediaParser.snippet(trimmed, 60)})."),
                )
            }
            val host = if (uri.port != -1) "${uri.host}:${uri.port}" else uri.host
            return Hub.Valid(trimmed.trimEnd('/'), host)
        }
    }
}
