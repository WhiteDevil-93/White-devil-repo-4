package com.whitedevil.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenCacheTest {
    private var now = 1_000_000L
    private val skew = 120_000L
    private val cache = TokenCache(clock = { now }, skewMs = skew)
    private val key = TokenCache.Key("https://hub.example", "dev1")

    @Test
    fun `a fresh token is returned and reports when it stops being trusted`() {
        val until = cache.store(key, "tok", expiresInSeconds = 3600)
        assertEquals(now + 3_600_000L - skew, until)
        assertEquals("tok", cache.get(key))
        assertEquals(until, cache.validUntil(key))
    }

    @Test
    fun `a token inside the skew margin is treated as expired`() {
        cache.store(key, "tok", 3600)
        now += 3_600_000L - skew - 1 // one millisecond before the trusted limit
        assertEquals("tok", cache.get(key))
        now += 1 // exactly at expiry minus skew: no longer trusted
        assertNull(cache.get(key))
        assertNull(cache.validUntil(key))
    }

    @Test
    fun `an expired token is gone and stays gone`() {
        cache.store(key, "tok", 3600)
        now += 3_600_000L + 5_000
        assertNull(cache.get(key))
        // Even if the clock is wound back afterwards, an entry already seen as expired was dropped.
        now -= 10_000_000L
        assertNull(cache.get(key))
    }

    @Test
    fun `clock is injected so time can move backwards too`() {
        cache.store(key, "tok", 3600)
        now -= 5_000_000L
        assertEquals("tok", cache.get(key))
    }

    @Test
    fun `a lifetime shorter than the skew is never stored`() {
        assertNull(cache.store(key, "tok", expiresInSeconds = 60))
        assertNull(cache.get(key))
        assertNull(cache.store(key, "tok", expiresInSeconds = 0))
        assertNull(cache.store(key, "tok", expiresInSeconds = -5))
        assertNull(cache.get(key))
    }

    @Test
    fun `storing an already stale token evicts the previous good one`() {
        cache.store(key, "good", 3600)
        assertNull(cache.store(key, "stale", 0))
        assertNull(cache.get(key))
    }

    @Test
    fun `blank tokens are not stored`() {
        assertNull(cache.store(key, "   ", 3600))
        assertNull(cache.get(key))
    }

    @Test
    fun `an absurd lifetime is clamped rather than overflowing`() {
        val until = cache.store(key, "tok", Long.MAX_VALUE)
        assertNotNull(until)
        assertTrue(until > now)
        assertEquals("tok", cache.get(key))
    }

    @Test
    fun `tokens are per hub and per device`() {
        cache.store(key, "a", 3600)
        assertNull(cache.get(key.copy(deviceId = "other")))
        assertNull(cache.get(key.copy(hubUrl = "https://other.example")))
        assertEquals("a", cache.get(key))
    }

    @Test
    fun `a newer token replaces the older one`() {
        cache.store(key, "old", 3600)
        cache.store(key, "new", 3600)
        assertEquals("new", cache.get(key))
    }

    @Test
    fun `clear forgets one key or everything`() {
        val other = key.copy(deviceId = "dev2")
        cache.store(key, "a", 3600)
        cache.store(other, "b", 3600)
        cache.clear(key)
        assertNull(cache.get(key))
        assertEquals("b", cache.get(other))
        cache.clear()
        assertNull(cache.get(other))
    }

    @Test
    fun `the token response never renders its token as text`() {
        assertFalse(TokenResponse("SECRET-TOKEN-VALUE", 3600, "d").toString().contains("SECRET-TOKEN-VALUE"))
    }
}
