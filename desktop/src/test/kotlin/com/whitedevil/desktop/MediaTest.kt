package com.whitedevil.desktop

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val LIBRARY_JSON = """
[
  {"id":"goon-p02","title":"Pack 2 · Slow Burn","kind":"pack","source":"vast","updated":1700003000.5,"count":2,
   "clips":[{"name":"smoke_goon_p2_c1_wanbot.mp4","idx":1,"mtime":1700000000.0,"mb":12.3,"source":"vast"},
            {"name":"smoke_goon_p2_c2_wanbot.mp4","idx":2,"mtime":1700003000.5,"mb":9.9,"source":"vast"}]},
  {"id":"keepers","title":"Keepers","kind":"keeper","source":"ltx","updated":1700005000.0,"count":1,
   "clips":[{"name":"KEEPER_nice one.mp4","idx":null,"mtime":1700005000.0,"mb":40.0,"source":"ltx"}]}
]
""".trimIndent()

private val json = headersOf(HttpHeaders.ContentType, "application/json")
private val jpeg = headersOf(HttpHeaders.ContentType, "image/jpeg")

private fun relay(engine: MockEngine, pass: String = "pw") = RelayHttp("https://hub.test/", "anon3", pass, engine)

class MediaClientTest {
    @Test
    fun `library parses the hub's shape including null idx`() = runBlocking<Unit> {
        val engine = MockEngine { respond(LIBRARY_JSON, HttpStatusCode.OK, json) }
        val groups = MediaClient(relay(engine)).library()
        assertEquals(2, groups.size)
        assertEquals("Pack 2 · Slow Burn", groups[0].title)
        assertEquals(2, groups[0].clips[1].idx)
        assertNull(groups[1].clips[0].idx)
        assertEquals("ltx", groups[1].source)
        assertEquals("https://hub.test/api/media/library", engine.requestHistory.single().url.toString())
    }

    @Test
    fun `an empty library is an empty list not a failure`() = runBlocking<Unit> {
        val engine = MockEngine { respond("[]", HttpStatusCode.OK, json) }
        assertTrue(MediaClient(relay(engine)).library().isEmpty())
    }

    @Test
    fun `basic auth is sent on every media request`() = runBlocking<Unit> {
        val engine = MockEngine { respond(ByteArray(4), HttpStatusCode.OK, jpeg) }
        MediaClient(relay(engine)).thumb("a.mp4")
        assertNotNull(engine.requestHistory.single().headers[HttpHeaders.Authorization])
        assertTrue(engine.requestHistory.single().headers[HttpHeaders.Authorization]!!.startsWith("Basic "))
    }

    @Test
    fun `clip names are encoded as a single path segment`() = runBlocking<Unit> {
        val engine = MockEngine { respond(ByteArray(4), HttpStatusCode.OK, jpeg) }
        val client = MediaClient(relay(engine))
        client.thumb("KEEPER_nice one #1?.mp4")
        client.contact("café 100%.mp4")
        val (thumb, contact) = engine.requestHistory.map { it.url.encodedPath }
        assertEquals("/api/media/thumb/KEEPER_nice%20one%20%231%3F.mp4", thumb)
        assertEquals("/api/media/contact/caf%C3%A9%20100%25.mp4", contact)
        assertTrue(engine.requestHistory.all { it.url.encodedQuery.isEmpty() }, "a ? in a name must not become a query string")
    }

    @Test
    fun `a name that could escape the route is refused before any request`() = runBlocking<Unit> {
        val engine = MockEngine { respond(ByteArray(4), HttpStatusCode.OK, jpeg) }
        val client = MediaClient(relay(engine))
        for (bad in listOf("../etc.mp4", "a/b.mp4", "a\\b.mp4", "")) {
            assertTrue(runCatching { client.thumb(bad) }.exceptionOrNull() is IllegalArgumentException, "accepted '$bad'")
        }
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `401 says the credentials were refused`() = runBlocking<Unit> {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        val e = runCatching { MediaClient(relay(engine)).library() }.exceptionOrNull() as RelayException
        assertEquals(RelayException.Kind.Unauthorized, e.kind)
        assertTrue(e.message!!.contains("credentials"))
    }

    @Test
    fun `a server error keeps the hub's own reason`() = runBlocking<Unit> {
        val engine = MockEngine { respond("""{"detail":"ffmpeg is not installed on this host"}""", HttpStatusCode.InternalServerError, json) }
        val e = runCatching { MediaClient(relay(engine)).thumb("a.mp4") }.exceptionOrNull() as RelayException
        assertEquals(RelayException.Kind.Server, e.kind)
        assertEquals("ffmpeg is not installed on this host", e.message)
    }

    @Test
    fun `a 404 clip is NotFound`() = runBlocking<Unit> {
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        val e = runCatching { MediaClient(relay(engine)).contact("gone.mp4") }.exceptionOrNull() as RelayException
        assertEquals(RelayException.Kind.NotFound, e.kind)
    }

    @Test
    fun `a timeout is reported as a timeout`() = runBlocking<Unit> {
        val engine = MockEngine { throw HttpRequestTimeoutException("https://hub.test/x", 1000L) }
        val e = runCatching { MediaClient(relay(engine)).library() }.exceptionOrNull() as RelayException
        assertEquals(RelayException.Kind.Timeout, e.kind)
        assertTrue(e.message!!.contains("30s"), e.message)
    }

    @Test
    fun `a connection failure is Unreachable and does not leak the password`() = runBlocking<Unit> {
        val engine = MockEngine { throw IOException("connection refused") }
        val e = runCatching { MediaClient(relay(engine, pass = "hunter2")).library() }.exceptionOrNull() as RelayException
        assertEquals(RelayException.Kind.Unreachable, e.kind)
        assertFalse(e.message!!.contains("hunter2"))
    }

    @Test
    fun `a proxy's html page in place of the library is a BadResponse`() = runBlocking<Unit> {
        val engine = MockEngine { respond("<html>Sign in</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }
        val e = runCatching { MediaClient(relay(engine)).library() }.exceptionOrNull() as RelayException
        assertEquals(RelayException.Kind.BadResponse, e.kind)
    }

    @Test
    fun `a library that is a json object rather than a list is a BadResponse`() = runBlocking<Unit> {
        val engine = MockEngine { respond("""{"detail":"nope"}""", HttpStatusCode.OK, json) }
        val e = runCatching { MediaClient(relay(engine)).library() }.exceptionOrNull() as RelayException
        assertEquals(RelayException.Kind.BadResponse, e.kind)
    }

    @Test
    fun `an oversized image body is refused`() = runBlocking<Unit> {
        val engine = MockEngine { respond(ByteArray(2_000), HttpStatusCode.OK, jpeg) }
        val e = runCatching { relay(engine).getBytes("/x", maxBytes = 1_000) }.exceptionOrNull() as RelayException
        assertEquals(RelayException.Kind.BadResponse, e.kind)
    }
}

class BoundedCacheTest {
    private fun cache(max: Long) = BoundedCache<String, String>(max) { it.length.toLong() }

    @Test
    fun `evicts least recently used until under budget`() {
        val c = cache(10)
        c["a"] = "1234"; c["b"] = "1234"
        assertNotNull(c["a"])            // a is now most recent
        c["c"] = "1234"                  // 12 > 10: b (least recent) goes
        assertNull(c["b"])
        assertNotNull(c["a"]); assertNotNull(c["c"])
        assertEquals(8, c.weight)
    }

    @Test
    fun `weight never exceeds the budget however many are added`() {
        val c = cache(100)
        repeat(1_000) { c["k$it"] = "x".repeat(7) }
        assertTrue(c.weight <= 100, "weight ${c.weight}")
        assertTrue(c.size <= 14)
    }

    @Test
    fun `a value larger than the whole budget is skipped without evicting others`() {
        val c = cache(10)
        c["a"] = "1234"
        c["huge"] = "x".repeat(50)
        assertNull(c["huge"])
        assertNotNull(c["a"])
    }

    @Test
    fun `replacing a key updates the weight`() {
        val c = cache(10)
        c["a"] = "1234567"
        c["a"] = "12"
        assertEquals(2, c.weight)
        assertEquals(1, c.size)
    }
}

class FormatAgeTest {
    private val now = 1_700_100_000_000L
    private fun ago(seconds: Long) = formatAge(now, now / 1000.0 - seconds)

    @Test
    fun `buckets`() {
        assertEquals("just now", ago(10))
        assertEquals("1m ago", ago(60))
        assertEquals("59m ago", ago(59 * 60 + 30))
        assertEquals("1h ago", ago(3_600))
        assertEquals("23h ago", ago(23 * 3_600 + 100))
        assertEquals("1d ago", ago(86_400))
        assertEquals("13d ago", ago(13 * 86_400 + 5))
        assertEquals("2w ago", ago(14 * 86_400))
        assertEquals("8w ago", ago(60 * 86_400))
    }

    @Test
    fun `a clip from the future is just now, and a missing time is unknown`() {
        assertEquals("just now", ago(-500))
        assertEquals("unknown", formatAge(now, 0.0))
    }
}

class MediaRepositoryTest {
    /** "Decodes" to the byte count, so no graphics stack is needed. */
    private val decode: (ByteArray) -> Decoded<String> = { bytes ->
        if (bytes.isEmpty() || bytes[0] == 0xFF.toByte()) throw IllegalStateException("not an image")
        Decoded("img${bytes.size}", bytes.size.toLong())
    }

    private fun repo(
        engine: MockEngine,
        thumbBudget: Long = 1_000_000,
        concurrency: Int = 4,
        unavailable: String? = null,
    ) = MediaRepository(MediaClient(relay(engine)), decode, thumbBudget, 1_000_000, concurrency, unavailableReason = unavailable)

    private fun imageBytes(n: Int = 10) = ByteArray(n) { 1 }

    @Test
    fun `refresh loads groups and clears any earlier error`() = runBlocking<Unit> {
        var up = false
        val engine = MockEngine { if (up) respond(LIBRARY_JSON, HttpStatusCode.OK, json) else respond("", HttpStatusCode.Unauthorized) }
        val r = repo(engine)
        r.refresh()
        assertNull(r.groups)
        assertNotNull(r.error)

        up = true
        r.refresh()
        assertEquals(2, r.groups!!.size)
        assertNull(r.error)
        assertNotNull(r.loadedAtMs)
    }

    @Test
    fun `a failed refresh keeps the previous data and reports the error`() = runBlocking<Unit> {
        var up = true
        val engine = MockEngine { if (up) respond(LIBRARY_JSON, HttpStatusCode.OK, json) else throw IOException("down") }
        val r = repo(engine)
        r.refresh()
        up = false
        r.refresh()
        assertEquals(2, r.groups!!.size, "stale data should stay visible")
        assertTrue(r.error!!.contains("Could not reach the hub"))
    }

    @Test
    fun `a failure with no earlier data leaves groups null - never an empty list`() = runBlocking<Unit> {
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
        val r = repo(engine)
        r.refresh()
        assertNull(r.groups, "null means unknown; an empty list would read as 'no renders'")
        assertNotNull(r.error)
    }

    @Test
    fun `an empty library is an empty list with no error`() = runBlocking<Unit> {
        val engine = MockEngine { respond("[]", HttpStatusCode.OK, json) }
        val r = repo(engine)
        r.refresh()
        assertEquals(emptyList(), r.groups)
        assertNull(r.error)
    }

    @Test
    fun `allClips is newest first across groups and carries the group title`() = runBlocking<Unit> {
        val engine = MockEngine { respond(LIBRARY_JSON, HttpStatusCode.OK, json) }
        val r = repo(engine)
        r.refresh()
        val all = r.allClips()
        assertEquals(listOf("KEEPER_nice one.mp4", "smoke_goon_p2_c2_wanbot.mp4", "smoke_goon_p2_c1_wanbot.mp4"), all.map { it.clip.name })
        assertEquals("Keepers", all.first().groupTitle)
        assertEquals("ltx", all.first().source)
    }

    @Test
    fun `a thumbnail is fetched once and then served from cache`() = runBlocking<Unit> {
        val engine = MockEngine { respond(imageBytes(), HttpStatusCode.OK, jpeg) }
        val r = repo(engine)
        assertEquals("img10", r.thumb("a.mp4").getOrThrow())
        assertEquals("img10", r.thumb("a.mp4").getOrThrow())
        assertEquals(1, engine.requestHistory.size)
        assertEquals("img10", r.cachedThumb("a.mp4"))
    }

    @Test
    fun `a failed thumbnail is returned with its reason and is not cached`() = runBlocking<Unit> {
        var ok = false
        val engine = MockEngine { if (ok) respond(imageBytes(), HttpStatusCode.OK, jpeg) else respond("""{"detail":"thumbnail failed: ffmpeg exit 1"}""", HttpStatusCode.InternalServerError, json) }
        val r = repo(engine)
        val first = r.thumb("a.mp4")
        assertTrue(first.isFailure)
        assertTrue(first.exceptionOrNull()!!.message!!.contains("ffmpeg exit 1"))
        ok = true
        assertTrue(r.thumb("a.mp4").isSuccess, "a retry after failure must go back to the hub")
    }

    @Test
    fun `undecodable image bytes are a visible failure`() = runBlocking<Unit> {
        val engine = MockEngine { respond(byteArrayOf(0xFF.toByte(), 1, 2), HttpStatusCode.OK, jpeg) }
        val r = repo(engine).thumb("a.mp4")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("could not be decoded"))
    }

    @Test
    fun `memory stays under the budget across a large library`() = runBlocking<Unit> {
        val engine = MockEngine { respond(imageBytes(100), HttpStatusCode.OK, jpeg) }
        val r = repo(engine, thumbBudget = 1_000) // room for ten
        repeat(200) { assertTrue(r.thumb("clip$it.mp4").isSuccess) }
        val cached = (0 until 200).count { r.cachedThumb("clip$it.mp4") != null }
        assertTrue(cached <= 10, "cached $cached thumbnails against a budget of 10")
        assertNotNull(r.cachedThumb("clip199.mp4"), "the most recent should survive")
    }

    @Test
    fun `concurrent thumbnail fetches are bounded`() = runBlocking<Unit> {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val engine = MockEngine {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(40)
            inFlight.decrementAndGet()
            respond(imageBytes(), HttpStatusCode.OK, jpeg)
        }
        val r = repo(engine, concurrency = 3)
        (0 until 24).map { i -> async(Dispatchers.Default) { r.thumb("c$i.mp4") } }.awaitAll().forEach { assertTrue(it.isSuccess) }
        assertTrue(peak.get() <= 3, "peak concurrency was ${peak.get()}")
        assertEquals(24, engine.requestHistory.size)
    }

    @Test
    fun `cancelling a request releases its slot - a scrolled-away tile does not block the queue`() = runBlocking<Unit> {
        val gate = AtomicInteger(0)
        val engine = MockEngine { req ->
            if (req.url.encodedPath.endsWith("slow.mp4")) { gate.incrementAndGet(); delay(60_000) }
            respond(imageBytes(), HttpStatusCode.OK, jpeg)
        }
        val r = repo(engine, concurrency = 1)
        val slow = launch(Dispatchers.Default) { r.thumb("slow.mp4") }
        while (gate.get() == 0) delay(5)
        slow.cancel()
        slow.join()
        withTimeout(5_000) { assertTrue(r.thumb("fast.mp4").isSuccess) }
    }

    @Test
    fun `unavailable settings fail loudly without touching the network`() = runBlocking<Unit> {
        val engine = MockEngine { respond(LIBRARY_JSON, HttpStatusCode.OK, json) }
        val r = repo(engine, unavailable = "Set the hub URL in Settings.")
        r.refresh()
        assertEquals("Set the hub URL in Settings.", r.error)
        assertNull(r.groups)
        assertEquals("Set the hub URL in Settings.", r.thumb("a.mp4").exceptionOrNull()?.message)
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `overlapping refreshes are coalesced`() = runBlocking<Unit> {
        val engine = MockEngine { delay(100); respond(LIBRARY_JSON, HttpStatusCode.OK, json) }
        val r = repo(engine)
        listOf(async(Dispatchers.Default) { r.refresh() }, async(Dispatchers.Default) { delay(20); r.refresh() }).awaitAll()
        assertEquals(1, engine.requestHistory.size)
    }
}
