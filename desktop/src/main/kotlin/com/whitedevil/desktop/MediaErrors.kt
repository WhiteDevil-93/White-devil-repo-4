package com.whitedevil.desktop

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.ConnectException
import java.net.UnknownHostException

/** Which hub call failed; the same status code means different things for each. */
enum class MediaOp { Library, Thumb, ContactSheet }

enum class MediaErrorKind(val title: String) {
    Config("Hub not configured"),
    Unauthorized("Sign-in rejected"),
    Forbidden("Access refused"),
    NotFound("Not found"),
    RateLimited("Rate limited"),
    ClientError("Request rejected"),
    ServerError("Hub error"),
    Unavailable("Hub unavailable"),
    Redirect("Unexpected redirect"),
    Timeout("Timed out"),
    Network("Connection failed"),
    BadResponse("Unreadable response"),
    TooLarge("Response too large"),
}

/**
 * A failed hub call, phrased for the user. Every non-success outcome becomes one
 * of these; there is deliberately no path that turns a failure into an empty
 * result, because that is indistinguishable from "the library is empty".
 */
data class MediaError(
    val kind: MediaErrorKind,
    /** One actionable sentence. */
    val message: String,
    val status: Int? = null,
    /** What the hub itself said (FastAPI `detail`, or the start of the body), if anything. */
    val detail: String? = null,
) {
    /** Heading for a panel: carries the HTTP status code when there is one. */
    val title: String get() = status?.let { "HTTP $it - ${kind.title}" } ?: kind.title

    /** Compact single description for small places such as a thumbnail tile. */
    val summary: String
        get() = if (status != null) "$status: ${detail ?: message}" else "${kind.title}: $message"
}

sealed interface MediaResult<out T> {
    data class Ok<out T>(val value: T) : MediaResult<T>
    data class Failure(val error: MediaError) : MediaResult<Nothing>
}

/** Timeouts (ms) the client was configured with; quoted back in timeout messages. */
data class MediaTimeouts(
    val connectMs: Long = 10_000,
    // The hub generates a missing thumbnail with ffmpeg on demand (up to ~2 x 20 s,
    // plus a wait for one of two ffmpeg slots), so a thumbnail can legitimately be slow.
    val libraryMs: Long = 20_000,
    val thumbMs: Long = 60_000,
    // A contact sheet is 12-24 ffmpeg seeks (up to 60 s each in the worst case).
    val contactMs: Long = 150_000,
) {
    fun forOp(op: MediaOp): Long = when (op) {
        MediaOp.Library -> libraryMs
        MediaOp.Thumb -> thumbMs
        MediaOp.ContactSheet -> contactMs
    }
}

/** Pure mapping from an HTTP status / exception to a [MediaError]. Unit-tested. */
object MediaErrors {
    private val json = Json

    fun config(message: String) = MediaError(MediaErrorKind.Config, message)

    fun fromStatus(
        op: MediaOp,
        status: Int,
        body: String?,
        location: String?,
        hasPassword: Boolean,
    ): MediaError {
        val detail = hubDetail(body)
        return when (status) {
            401 -> MediaError(
                MediaErrorKind.Unauthorized,
                "The relay rejected the sign-in. Check the relay user and password in Settings." +
                    if (hasPassword) "" else " No relay password is set.",
                status,
                detail,
            )
            403 -> MediaError(MediaErrorKind.Forbidden, "The relay refused this account access to the media API.", status, detail)
            404 -> MediaError(
                MediaErrorKind.NotFound,
                if (op == MediaOp.Library) {
                    "The hub has no media library at this address. Check the Hub URL in Settings, or update the hub."
                } else {
                    "The hub does not have this clip. It may have been deleted or renamed; refresh the library."
                },
                status,
                detail,
            )
            429 -> MediaError(MediaErrorKind.RateLimited, "The relay is rate-limiting requests. Wait a moment, then retry.", status, detail)
            502, 503, 504 -> MediaError(
                MediaErrorKind.Unavailable,
                "The relay could not get an answer from the hub. The hub may be down or restarting.",
                status,
                detail,
            )
            in 300..399 -> MediaError(
                MediaErrorKind.Redirect,
                "The hub answered with a redirect" + (location?.let { " to $it" } ?: "") +
                    ". Set the Hub URL in Settings to the address the hub actually serves from.",
                status,
                null,
            )
            in 500..599 -> MediaError(MediaErrorKind.ServerError, "The hub failed while handling the request.", status, detail)
            in 400..499 -> MediaError(MediaErrorKind.ClientError, "The hub rejected the request.", status, detail)
            else -> MediaError(MediaErrorKind.BadResponse, "The hub answered with an unexpected status.", status, detail)
        }
    }

    fun fromException(op: MediaOp, e: Throwable, hubHost: String, timeouts: MediaTimeouts): MediaError {
        val chain = generateSequence(e) { it.cause }.take(6).toList()
        chain.firstOrNull { it is HttpRequestTimeoutException }?.let {
            return MediaError(
                MediaErrorKind.Timeout,
                "The hub did not answer within ${timeouts.forOp(op) / 1000} s.",
            )
        }
        chain.firstOrNull { it is ConnectTimeoutException }?.let {
            return MediaError(
                MediaErrorKind.Timeout,
                "Could not open a connection to $hubHost within ${timeouts.connectMs / 1000} s.",
            )
        }
        // ktor's SocketTimeoutException and java.net.SocketTimeoutException share this simple name.
        chain.firstOrNull { it.javaClass.simpleName == "SocketTimeoutException" }?.let {
            return MediaError(
                MediaErrorKind.Timeout,
                "The connection to $hubHost went quiet: no data arrived within ${timeouts.forOp(op) / 1000} s.",
            )
        }
        val root = chain.last()
        val reason = when {
            chain.any { it is UnknownHostException } -> "the host name could not be resolved"
            chain.any { it is ConnectException } -> "the connection was refused or dropped"
            else -> root.message?.takeIf { it.isNotBlank() }?.let { MediaParser.snippet(it, 160) } ?: root.javaClass.simpleName
        }
        return MediaError(MediaErrorKind.Network, "Could not reach $hubHost: $reason.", null, root.javaClass.simpleName)
    }

    /** FastAPI errors are `{"detail": "..."}`; anything else is quoted verbatim (shortened). */
    fun hubDetail(body: String?): String? {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return null
        val detail = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?.let { it as? JsonObject }?.get("detail")
        if (detail != null) {
            val s = (detail as? JsonPrimitive)?.content ?: detail.toString()
            return MediaParser.snippet(s, 300)
        }
        return MediaParser.snippet(text, 300)
    }
}
