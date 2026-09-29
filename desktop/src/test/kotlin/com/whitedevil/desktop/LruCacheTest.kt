package com.whitedevil.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LruCacheTest {

    private fun countBound(max: Int) = LruCache<String, String>(maxEntries = max, maxBytes = Long.MAX_VALUE / 2) { 1L }
    private fun byteBound(max: Long) = LruCache<String, String>(maxEntries = 1000, maxBytes = max) { it.length.toLong() }

    @Test
    fun puttingOnePastTheEntryBoundEvictsTheOldest() {
        val c = countBound(3)
        listOf("a", "b", "c").forEach { c.put(it, it.uppercase()) }
        assertEquals(3, c.size)

        c.put("d", "D")

        assertEquals(3, c.size)
        assertNull(c.get("a"), "the oldest entry must be evicted")
        assertEquals(listOf("b", "c", "d"), c.keysOldestFirst())
        assertEquals(1L, c.evictions)
    }

    @Test
    fun aRecentlyReadEntrySurvivesEviction() {
        val c = countBound(3)
        listOf("a", "b", "c").forEach { c.put(it, it) }

        c.get("a")        // a is now the most recently used
        c.put("d", "d")   // so b, not a, is the victim

        assertEquals("a", c.get("a"))
        assertNull(c.get("b"))
        assertEquals("c", c.get("c"))
        assertEquals("d", c.get("d"))
    }

    @Test
    fun rePuttingAKeyRefreshesItsRecency() {
        val c = countBound(2)
        c.put("a", "1")
        c.put("b", "2")
        c.put("a", "3") // refreshes a
        c.put("c", "4") // evicts b
        assertEquals("3", c.get("a"))
        assertNull(c.get("b"))
        assertEquals(2, c.size)
    }

    @Test
    fun theByteBoundEvictsOldestUntilItHoldsAgain() {
        val c = byteBound(10)
        c.put("a", "xxxx")
        c.put("b", "yyyy")
        assertEquals(8L, c.bytes)

        c.put("c", "zzzz") // 12 > 10: a must go

        assertNull(c.get("a"))
        assertEquals("yyyy", c.get("b"))
        assertEquals("zzzz", c.get("c"))
        assertEquals(8L, c.bytes)
    }

    @Test
    fun oneBigValueCanEvictSeveralSmallOnes() {
        val c = byteBound(10)
        c.put("a", "11")
        c.put("b", "22")
        c.put("c", "33")
        c.put("big", "0123456789") // exactly the whole budget
        assertEquals(listOf("big"), c.keysOldestFirst())
        assertEquals(10L, c.bytes)
    }

    @Test
    fun aValueLargerThanTheWholeBudgetIsNotStoredAndDropsTheStaleOne() {
        val c = byteBound(10)
        c.put("k", "small")
        assertFalse(c.put("k", "this is far too large"))
        assertNull(c.get("k"), "a stale image must not be served after its replacement was rejected")
        assertEquals(0L, c.bytes)
        assertEquals(0, c.size)
    }

    @Test
    fun replacingAKeyAdjustsTheByteCount() {
        val c = byteBound(100)
        c.put("k", "12345")
        c.put("k", "123")
        assertEquals(3L, c.bytes)
        assertEquals(1, c.size)
    }

    @Test
    fun clearAndRemoveResetAccounting() {
        val c = byteBound(100)
        c.put("a", "111")
        c.put("b", "22")
        c.remove("a")
        assertEquals(2L, c.bytes)
        c.clear()
        assertEquals(0L, c.bytes)
        assertEquals(0, c.size)
    }

    @Test
    fun boundsMustBePositive() {
        assertFailsWith<IllegalArgumentException> { LruCache<String, String>(0, 10) { 1 } }
        assertFailsWith<IllegalArgumentException> { LruCache<String, String>(1, 0) { 1 } }
    }

    @Test
    fun boundsHoldUnderConcurrentUse() {
        val c = LruCache<Int, ByteArray>(maxEntries = 50, maxBytes = 5_000) { it.size.toLong() }
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(8)
        repeat(8) { t ->
            pool.execute {
                val rnd = Random(t)
                repeat(3_000) {
                    val k = rnd.nextInt(200)
                    if (rnd.nextInt(3) == 0) c.get(k) else c.put(k, ByteArray(rnd.nextInt(1, 400)))
                    assertTrue(c.size <= 50)
                }
                done.countDown()
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(c.size <= 50)
        assertTrue(c.bytes <= 5_000)
        // accounting matches reality
        val actual = c.keysOldestFirst().sumOf { c.get(it)!!.size.toLong() }
        assertEquals(actual, c.bytes)
    }
}
