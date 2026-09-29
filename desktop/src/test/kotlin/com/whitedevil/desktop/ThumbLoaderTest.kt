package com.whitedevil.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ThumbLoaderTest {

    private fun cache(entries: Int = 100) = LruCache<String, String>(entries, 1_000_000) { it.length.toLong() }

    private fun loader(
        cache: LruCache<String, String> = cache(),
        permits: Int = 4,
        decode: (ByteArray) -> String = { String(it) },
        fetch: suspend (String) -> MediaResult<ByteArray>,
    ) = ThumbLoader(cache, fetch, decode, permits, EmptyCoroutineContext)

    private fun ok(s: String) = MediaResult.Ok(s.toByteArray())
    private fun fail(status: Int) = MediaResult.Failure(MediaError(MediaErrorKind.ServerError, "boom", status, "d"))

    @Test
    fun aSecondLoadOfTheSameKeyComesFromTheCacheWithoutARequest() = runTest {
        var fetches = 0
        val l = loader { fetches++; ok("img-$it") }

        assertEquals(ThumbOutcome.Ready("img-a"), l.load("k", "a"))
        assertEquals(ThumbOutcome.Ready("img-a"), l.load("k", "a"))

        assertEquals(1, fetches)
        assertEquals("img-a", l.cached("k"))
    }

    @Test
    fun aFailureIsReportedNotCachedAndNotRepeatedUntilExplicitRetry() = runTest {
        var fetches = 0
        var healthy = false
        val l = loader { fetches++; if (healthy) ok("fine") else fail(500) }

        val first = l.load("k", "a")
        assertIs<ThumbOutcome.Failed>(first)
        assertEquals(500, first.error.status)
        assertNull(l.cached("k"), "a failure must never look like cached data")

        // scrolling away and back must not silently hammer the hub
        assertIs<ThumbOutcome.Failed>(l.load("k", "a"))
        assertEquals(1, fetches)
        assertEquals(500, l.failure("k")?.status)

        // only an explicit retry tries again
        healthy = true
        l.retry("k")
        assertEquals(ThumbOutcome.Ready("fine"), l.load("k", "a"))
        assertEquals(2, fetches)
        assertNull(l.failure("k"))
    }

    @Test
    fun clearFailuresLetsEveryFailedKeyRetry() = runTest {
        var fetches = 0
        val l = loader { fetches++; fail(500) }
        l.load("a", "a"); l.load("b", "b")
        assertEquals(2, fetches)
        l.clearFailures()
        l.load("a", "a"); l.load("b", "b")
        assertEquals(4, fetches)
    }

    @Test
    fun aDecodeErrorIsAFailureNotABlankSuccess() = runTest {
        val l = loader(decode = { throw IllegalArgumentException("Failed to decode Image") }) { ok("not an image") }

        val r = l.load("k", "a")

        assertIs<ThumbOutcome.Failed>(r)
        assertEquals(MediaErrorKind.BadResponse, r.error.kind)
        assertTrue("Failed to decode" in (r.error.detail ?: ""))
        assertNull(l.cached("k"))
    }

    @Test
    fun anExceptionEscapingTheFetcherIsAVisibleFailureNotACrash() = runTest {
        val l = loader { throw IllegalStateException("bug in the fetcher") }

        val r = l.load("k", "a")

        assertIs<ThumbOutcome.Failed>(r)
        assertTrue("bug in the fetcher" in (r.error.detail ?: ""))
        assertNull(l.cached("k"))
    }

    @Test
    fun atMostFourRequestsRunAtOnce() = runTest {
        var active = 0
        var maxActive = 0
        val l = loader(permits = 4) {
            active++
            maxActive = maxOf(maxActive, active)
            delay(100)
            active--
            ok("img-$it")
        }

        val results = (1..20).map { i -> async { l.load("k$i", "clip$i") } }.awaitAll()

        assertEquals(4, maxActive)
        assertTrue(results.all { it is ThumbOutcome.Ready })
    }

    @Test
    fun cancellingALoadReleasesItsPermitAndIsNotRememberedAsAFailure() = runTest {
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val l = loader(permits = 1) { name ->
            fetches++
            if (name == "slow") gate.await() // never completes: the tile scrolls away
            ok("img-$name")
        }

        val slow = launch { l.load("slow-key", "slow") }
        advanceUntilIdle()
        assertEquals(1, fetches)

        slow.cancel()
        advanceUntilIdle()

        // the single permit is free again, so another tile can load
        assertEquals(ThumbOutcome.Ready("img-fast"), l.load("fast-key", "fast"))
        // and the cancelled clip is not marked as failed
        assertNull(l.failure("slow-key"))
        assertNull(l.cached("slow-key"))
    }

    @Test
    fun loadsQueuedBehindTheLimitAreCancelledWithoutEverRequesting() = runTest {
        val gate = CompletableDeferred<Unit>()
        val requested = mutableListOf<String>()
        val l = loader(permits = 1) { name -> requested += name; gate.await(); ok("img-$name") }

        val running = launch { l.load("a", "a") }
        val queued = launch { l.load("b", "b") }
        advanceUntilIdle()
        assertEquals(listOf("a"), requested)

        queued.cancel() // scrolled away while still waiting for a slot
        gate.complete(Unit)
        advanceUntilIdle()
        running.join()

        assertEquals(listOf("a"), requested, "a cancelled tile must never hit the hub")
    }

    @Test
    fun theCacheStaysBoundedAndEvictedImagesAreFetchedAgain() = runTest {
        var fetches = 0
        val l = loader(cache = cache(entries = 2)) { fetches++; ok("img-$it") }

        l.load("1", "1"); l.load("2", "2"); l.load("3", "3") // "1" evicted
        assertEquals(3, fetches)
        assertNull(l.cached("1"))

        l.load("1", "1")
        assertEquals(4, fetches)
    }

    @Test
    fun cacheKeysChangeWhenTheClipIsReRendered() {
        val before = MediaClip("a.mp4", 1, 1000.0, 1.0, null)
        val after = before.copy(mtime = 2000.0)
        assertTrue(mediaCacheKey("https://h", before) != mediaCacheKey("https://h", after))
        assertTrue(mediaCacheKey("https://h", before) != mediaCacheKey("https://other", before))
        assertEquals(mediaCacheKey("https://h", before), mediaCacheKey("https://h/", before))
        assertTrue(mediaCacheKey("https://h", before) != mediaCacheKey("https://h", before.copy(name = "b.mp4")))
    }
}
