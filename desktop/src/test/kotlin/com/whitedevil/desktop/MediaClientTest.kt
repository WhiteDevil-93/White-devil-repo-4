package com.whitedevil.desktop

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaClientTest {

    // ---- success paths ----

    @Test
    fun libraryIsFetchedFromTheRealRouteWithRelayBasicAuth() = runBlocking {
        var seenPath = ""
        var seenAuth: String? = null
        val engine = MockEngine { req ->
            seenPath = req.url.encodedPath
            seenAuth = req.headers[HttpHeaders.Authorization]
            respond(SAMPLE_LIBRARY, HttpStatusCode.OK, jsonHeaders)
        }
        val lib = client(engine, user = "anon3", pass = "p@ss:word ü").library().ok()

        assertEquals("/api/media/library", seenPath)
        val expected = "Basic " + Base64.getEncoder().encodeToString("anon3:p@ss:word ü".toByteArray(Charsets.UTF_8))
        assertEquals(expected, seenAuth)
        assertEquals(3, lib.clipCount)
        assertEquals("Pack 3 · Neon Nights", lib.groups[0].title)
    }

    @Test
    fun emptyLibraryIsOkAndEmpty() = runBlocking {
        val engine = MockEngine { respond("[]", HttpStatusCode.OK, jsonHeaders) }
        val lib = client(engine).library().ok()
        assertEquals(0, lib.groups.size)
    }

    @Test
    fun noAuthorizationHeaderIsSentWhenNoCredentialsAreConfigured() = runBlocking {
        var auth: String? = "unset"
        val engine = MockEngine { req -> auth = req.headers[HttpHeaders.Authorization]; respond("[]", HttpStatusCode.OK, jsonHeaders) }
        client(engine, user = "", pass = "").library().ok()
        assertEquals(null, auth)
    }

    @Test
    fun thumbnailAndContactSheetUseTheirOwnRoutesAndReturnTheBytes() = runBlocking {
        val paths = mutableListOf<String>()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val engine = MockEngine { req -> paths += req.url.encodedPath; respond(jpeg, HttpStatusCode.OK, jpegHeaders) }
        val c = client(engine)

        assertContentEquals(jpeg, c.thumb("a.mp4").ok())
        assertContentEquals(jpeg, c.contactSheet("a.mp4").ok())
        assertEquals(listOf("/api/media/thumb/a.mp4", "/api/media/contact/a.mp4"), paths)
    }

    @Test
    fun anImageWithNoContentTypeIsPassedToTheDecoderToJudge() = runBlocking {
        val engine = MockEngine { respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK) }
        assertContentEquals(byteArrayOf(1, 2, 3), client(engine).thumb("a.mp4").ok())
    }

    // ---- clip names in URLs ----

    @Test
    fun clipNamesArePercentEncodedAndRoundTripToTheOriginalName() = runBlocking {
        val names = listOf(
            "plain.mp4",
            "smoke goon#1 100%.mp4",
            "what?is=this&that+other.mp4",
            "café 日本 🎬.mp4",
            "semi;colon,comma@at.mp4",
            "a/b.mp4",      // hostile: must not split the path
            "a\\b.mp4",
            "dots..and..more.mp4",
            "quote\"'<>.mp4",
        )
        for (name in names) {
            var rawPath = ""
            var decodedLast = ""
            var segments = 0
            val engine = MockEngine { req ->
                rawPath = req.url.encodedPath
                decodedLast = req.url.pathSegments.last()
                segments = req.url.pathSegments.size
                respond(byteArrayOf(1), HttpStatusCode.OK, jpegHeaders)
            }
            client(engine).thumb(name).ok()

            assertEquals("/api/media/thumb/" + MediaClient.encodePathSegment(name), rawPath, "raw path for `$name`")
            assertEquals(name, decodedLast, "the hub must decode back to `$name`")
            // "", "api", "media", "thumb", <name>: the name (even with '/') stays a single segment.
            assertEquals(5, segments, "`$name` must stay one path segment")
        }
    }

    @Test
    fun encodePathSegmentEncodesEverythingButUnreserved() {
        assertEquals("abcXYZ019-._~", MediaClient.encodePathSegment("abcXYZ019-._~"))
        assertEquals("a%20b", MediaClient.encodePathSegment("a b"))
        assertEquals("%23%3F%25%2B%26%2F%5C", MediaClient.encodePathSegment("#?%+&/\\"))
        assertEquals("caf%C3%A9", MediaClient.encodePathSegment("café"))
        assertEquals("%E6%97%A5", MediaClient.encodePathSegment("日"))
        assertEquals("%F0%9F%8E%AC", MediaClient.encodePathSegment("🎬"))
        assertEquals("", MediaClient.encodePathSegment(""))
    }

    @Test
    fun hubUrlTrailingSlashAndPathPrefixAreHandled() = runBlocking {
        val paths = mutableListOf<String>()
        val engine = MockEngine { req -> paths += req.url.encodedPath; respond("[]", HttpStatusCode.OK, jsonHeaders) }
        client(engine, hub = "https://hub.example/").library().ok()
        client(engine, hub = "  https://hub.example/forge//  ").library().ok()
        assertEquals(listOf("/api/media/library", "/forge/api/media/library"), paths)
    }

    // ---- HTTP failures are visible and distinct ----

    private fun failureFor(status: Int, body: String = "", op: MediaOp = MediaOp.Thumb, headers: io.ktor.http.Headers = headersOf()): MediaError = runBlocking {
        val engine = MockEngine { respond(body, HttpStatusCode.fromValue(status), headers) }
        val c = client(engine)
        when (op) {
            MediaOp.Library -> c.library().failure()
            MediaOp.Thumb -> c.thumb("a.mp4").failure()
            MediaOp.ContactSheet -> c.contactSheet("a.mp4").failure()
            MediaOp.Clip -> c.downloadClip("a.mp4", java.nio.file.Files.createTempDirectory("mc").resolve("a.mp4")).failure()
        }
    }

    @Test
    fun http401MapsToASignInMessageWithTheStatusCode() {
        val e = failureFor(401, op = MediaOp.Library)
        assertEquals(MediaErrorKind.Unauthorized, e.kind)
        assertEquals(401, e.status)
        assertTrue("401" in e.title)
        assertTrue("Settings" in e.message)
    }

    @Test
    fun blankRelayPasswordIsCalledOutOn401() = runBlocking {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        val e = client(engine, pass = "").library().failure()
        assertTrue("No relay password" in e.message, e.message)
    }

    @Test
    fun http404DiffersBetweenTheLibraryRouteAndAMissingClip() {
        val lib = failureFor(404, """{"detail":"Not Found"}""", MediaOp.Library)
        val clip = failureFor(404, """{"detail":"Not Found"}""", MediaOp.Thumb)
        assertEquals(MediaErrorKind.NotFound, lib.kind)
        assertEquals(MediaErrorKind.NotFound, clip.kind)
        assertTrue(lib.message != clip.message)
        assertEquals(404, clip.status)
    }

    @Test
    fun http500CarriesTheHubsOwnReason() {
        val e = failureFor(500, """{"detail":"thumbnail failed: ffmpeg timed out after 20s"}""")
        assertEquals(MediaErrorKind.ServerError, e.kind)
        assertEquals(500, e.status)
        assertEquals("thumbnail failed: ffmpeg timed out after 20s", e.detail)
        assertTrue("ffmpeg timed out" in e.summary)
    }

    @Test
    fun otherStatusesAreEachMappedToTheirOwnKind() {
        assertEquals(MediaErrorKind.Forbidden, failureFor(403).kind)
        assertEquals(MediaErrorKind.RateLimited, failureFor(429).kind)
        assertEquals(MediaErrorKind.Unavailable, failureFor(502).kind)
        assertEquals(MediaErrorKind.Unavailable, failureFor(503).kind)
        assertEquals(MediaErrorKind.Unavailable, failureFor(504).kind)
        assertEquals(MediaErrorKind.ClientError, failureFor(422, """{"detail":[{"msg":"bad"}]}""").kind)
        assertEquals(MediaErrorKind.ServerError, failureFor(500).kind)
    }

    @Test
    fun aRedirectIsReportedNotFollowedSoCredentialsAreNotReplayed() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://elsewhere.example/api/media/library"))
        }
        val e = client(engine).library().failure()
        assertEquals(1, calls, "must not follow the redirect")
        assertEquals(MediaErrorKind.Redirect, e.kind)
        assertEquals(302, e.status)
        assertTrue("elsewhere.example" in e.message)
    }

    @Test
    fun the401404500AndTimeoutMessagesAreAllDistinct() = runBlocking {
        val messages = listOf(
            failureFor(401, op = MediaOp.Thumb),
            failureFor(404, op = MediaOp.Thumb),
            failureFor(500, op = MediaOp.Thumb),
            client(MockEngine { throw HttpRequestTimeoutException("https://hub.example/x", 1000) }).thumb("a.mp4").failure(),
        )
        assertEquals(4, messages.map { it.message }.toSet().size, messages.toString())
        assertEquals(4, messages.map { it.kind }.toSet().size)
    }

    // ---- transport failures ----

    private fun failureThrowing(t: Throwable): MediaError = runBlocking {
        client(MockEngine { throw t }, hub = "https://hub.example:8443").library().failure()
    }

    @Test
    fun timeoutsOfEveryFlavourMapToATimeoutError() {
        for (t in listOf<Throwable>(
            HttpRequestTimeoutException("https://hub.example/x", 5000),
            ConnectTimeoutException("connect"),
            SocketTimeoutException("socket"),
            java.net.SocketTimeoutException("java"),
            IOException("wrapped", HttpRequestTimeoutException("https://hub.example/x", 5000)),
        )) {
            val e = failureThrowing(t)
            assertEquals(MediaErrorKind.Timeout, e.kind, "for $t")
            assertEquals(null, e.status)
        }
    }

    @Test
    fun timeoutMessagesQuoteTheConfiguredLimit() = runBlocking {
        val timeouts = MediaTimeouts(libraryMs = 7_000, connectMs = 3_000)
        val e = client(MockEngine { throw HttpRequestTimeoutException("u", 7000) }, timeouts = timeouts).library().failure()
        assertTrue("7 s" in e.message, e.message)
        val c = client(MockEngine { throw ConnectTimeoutException("x") }, timeouts = timeouts).library().failure()
        assertTrue("3 s" in c.message, c.message)
        assertTrue("hub.example" in c.message)
    }

    @Test
    fun aRealRequestTimeoutFiresAndIsReportedNotSwallowed() = runBlocking {
        val engine = MockEngine { delay(10_000); respond("[]", HttpStatusCode.OK, jsonHeaders) }
        val started = System.nanoTime()
        val e = withTimeout(8_000) {
            client(engine, timeouts = MediaTimeouts(libraryMs = 150)).library().failure()
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(MediaErrorKind.Timeout, e.kind, e.toString())
        assertTrue(tookMs < 5_000, "took ${tookMs}ms")
    }

    @Test
    fun connectionLevelFailuresAreNetworkErrorsThatNameTheHost() {
        for (t in listOf<Throwable>(UnknownHostException("nope"), ConnectException("refused"), IOException("reset by peer"), javax.net.ssl.SSLException("bad cert"))) {
            val e = failureThrowing(t)
            assertEquals(MediaErrorKind.Network, e.kind, "for $t")
            assertTrue("hub.example:8443" in e.message, e.message)
        }
        assertTrue("could not be resolved" in failureThrowing(UnknownHostException("x")).message)
    }

    @Test
    fun anUnexpectedExceptionIsStillAFailureNotACrashOrAnEmptyResult() {
        val e = failureThrowing(IllegalStateException("boom"))
        assertEquals(MediaErrorKind.Network, e.kind)
        assertTrue("boom" in e.message)
    }

    // ---- 200 responses that are not what they claim ----

    @Test
    fun aBadJsonBodyOn200IsAVisibleParseError() = runBlocking {
        val bodies = listOf(
            "<html>Login required</html>", "", "not json at all", "[{\"id\":", "null", "{\"detail\":\"Not Found\"}",
        )
        for (b in bodies) {
            val engine = MockEngine { respond(b, HttpStatusCode.OK, jsonHeaders) }
            val e = client(engine).library().failure()
            assertEquals(MediaErrorKind.BadResponse, e.kind, "for body `$b`")
            assertTrue(e.message.isNotBlank())
        }
    }

    @Test
    fun aNonImageThumbnailBodyIsRejectedEvenOn200() = runBlocking {
        val html = MockEngine { respond("<html>hi</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")) }
        val e = client(html).thumb("a.mp4").failure()
        assertEquals(MediaErrorKind.BadResponse, e.kind)
        assertTrue("text/html" in e.message)

        val empty = MockEngine { respond(ByteArray(0), HttpStatusCode.OK, jpegHeaders) }
        assertEquals(MediaErrorKind.BadResponse, client(empty).thumb("a.mp4").failure().kind)
    }

    @Test
    fun anEnormousDeclaredBodyIsRefusedBeforeItIsRead() = runBlocking {
        val engine = MockEngine {
            respond(ByteArray(10), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("image/jpeg"), HttpHeaders.ContentLength to listOf("999999999")))
        }
        assertEquals(MediaErrorKind.TooLarge, client(engine).thumb("a.mp4").failure().kind)
    }

    // ---- configuration ----

    @Test
    fun anUnusableHubUrlFailsVisiblyWithoutMakingARequest() = runBlocking {
        for (bad in listOf("", "   ", "hub.example", "ftp://hub.example", "https://", "http://bad host", "not a url")) {
            var calls = 0
            val engine = MockEngine { calls++; respond("[]", HttpStatusCode.OK, jsonHeaders) }
            val c = client(engine, hub = bad)
            assertEquals(MediaErrorKind.Config, c.library().failure().kind, "for `$bad`")
            assertEquals(MediaErrorKind.Config, c.thumb("a.mp4").failure().kind, "for `$bad`")
            assertEquals(0, calls, "no request may be made for `$bad`")
        }
    }

    // ---- cancellation is not an error ----

    @Test
    fun cancellingTheCallerCancelsTheRequestInsteadOfReturningAFailure() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val engine = MockEngine { started.complete(Unit); delay(60_000); respond("[]", HttpStatusCode.OK, jsonHeaders) }
        val c = client(engine)
        var result: MediaResult<*>? = null
        var cancelled = false
        val job = launch {
            try {
                result = c.thumb("a.mp4")
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        started.await()
        job.cancel()
        job.join()
        assertTrue(cancelled, "CancellationException must propagate")
        assertEquals(null, result, "a cancelled request must not produce any result, least of all an empty one")
    }
}
