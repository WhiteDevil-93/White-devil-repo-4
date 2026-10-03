package com.whitedevil.desktop

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaFilterTest {
    private val utc = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 10, 3, 18, 0, 0, 0, utc)
    private val nowMs = now.toInstant().toEpochMilli()
    private fun ago(days: Long, hours: Long = 0): Double = now.minusDays(days).minusHours(hours).toEpochSecond().toDouble()

    private fun clip(name: String, mtime: Double?, mb: Double? = 10.0, source: String? = null) = MediaClip(name, null, mtime, mb, source)
    private fun group(id: String, title: String, kind: String, source: String, vararg clips: MediaClip) =
        MediaGroup(id, title, kind, source, null, clips.toList())

    private val groups = listOf(
        group("t", "Tests & experiments", "test", "ltx",
            clip("smoke_ti2v5b_CLEAN_BASE_832x480_f81_s20_seed42.mp4", ago(1), 5.0),
            clip("smoke_old_test.mp4", ago(60), 1.0)),
        group("c", "Best Friends Night V3", "chain", "vast",
            clip("bestfriends_c01.mp4", ago(0, 2), 30.0), clip("bestfriends_c02.mp4", ago(0, 1), 31.0)),
        group("p", "Pack 5 · Mirror Feedback Loop", "pack", "vast",
            clip("goon_p05_c01_1280x704_f81_s28.mp4", ago(3), 80.0)),
        group("k", "Keepers", "keeper", "vast", clip("KEEPER_011_seed20260924.mp4", ago(9), 50.0)),
        group("th", "Friends Room", "chain", "thunder", clip("friends_c01_14b.mp4", null, null)),
    )
    private val all = buildGallerySections(groups).flatMap { it.items }
    private fun run(f: MediaFilter) = filterItems(all, f, nowMs, utc).map { it.clip.name }

    @Test fun `tests are hidden by default but shown when asked`() {
        assertFalse(run(MediaFilter()).any { "smoke_" in it })
        assertTrue(run(MediaFilter(showTests = true)).any { "smoke_" in it })
    }

    @Test fun `a search reaches the hidden tests`() {
        assertEquals(listOf("smoke_ti2v5b_CLEAN_BASE_832x480_f81_s20_seed42.mp4"), run(MediaFilter(query = "seed42")))
    }

    @Test fun `every term must match and matching is case-insensitive`() {
        assertEquals(listOf("goon_p05_c01_1280x704_f81_s28.mp4"), run(MediaFilter(query = "GOON 1280x704")))
        assertEquals(emptyList(), run(MediaFilter(query = "goon thunder")))
    }

    @Test fun `search matches project title and source as well as the file name`() {
        assertEquals(2, run(MediaFilter(query = "best friends")).size)
        assertEquals(listOf("friends_c01_14b.mp4"), run(MediaFilter(query = "thunder")))
    }

    @Test fun `a minus term excludes`() {
        assertEquals(listOf("bestfriends_c02.mp4", "bestfriends_c01.mp4"), run(MediaFilter(query = "friends -thunder -room")))
        assertEquals(listOf("bestfriends_c02.mp4", "bestfriends_c01.mp4"), run(MediaFilter(query = "friends -c01_14b")))
    }

    @Test fun `a lone minus is just a character not an empty exclusion`() {
        assertEquals(emptyList(), run(MediaFilter(query = "-")))
    }

    @Test fun `source filter uses the clip source then the group source`() {
        assertEquals(listOf("friends_c01_14b.mp4"), run(MediaFilter(source = "thunder")))
        assertEquals(4, run(MediaFilter(source = "vast")).size)
    }

    @Test fun `keepers only`() {
        assertEquals(listOf("KEEPER_011_seed20260924.mp4"), run(MediaFilter(keepersOnly = true)))
    }

    @Test fun `date ranges use calendar days and drop undated clips`() {
        assertEquals(listOf("bestfriends_c02.mp4", "bestfriends_c01.mp4"), run(MediaFilter(range = DateRange.Today)))
        val week = run(MediaFilter(range = DateRange.Week))
        assertTrue("goon_p05_c01_1280x704_f81_s28.mp4" in week)
        assertFalse("KEEPER_011_seed20260924.mp4" in week)
        assertFalse("friends_c01_14b.mp4" in run(MediaFilter(range = DateRange.Month)), "undated clip must not count as recent")
    }

    @Test fun `sorting puts missing values last in both directions`() {
        assertEquals("friends_c01_14b.mp4", run(MediaFilter(sort = SortKey.Newest)).last())
        assertEquals("friends_c01_14b.mp4", run(MediaFilter(sort = SortKey.Oldest)).last())
        assertEquals("friends_c01_14b.mp4", run(MediaFilter(sort = SortKey.Largest)).last())
        assertEquals("goon_p05_c01_1280x704_f81_s28.mp4", run(MediaFilter(sort = SortKey.Largest)).first())
        assertEquals("bestfriends_c01.mp4", run(MediaFilter(sort = SortKey.Name)).first())
    }

    @Test fun `day buckets`() {
        assertEquals("Today", bucketOf(ago(0, 3), nowMs, utc))
        assertEquals("Yesterday", bucketOf(ago(1), nowMs, utc))
        assertEquals("This week", bucketOf(ago(4), nowMs, utc))
        assertEquals("September 2026", bucketOf(ago(8), nowMs, utc))
        val midMonth = ZonedDateTime.of(2026, 10, 20, 9, 0, 0, 0, utc)
        assertEquals("This month", bucketOf(midMonth.minusDays(12).toEpochSecond().toDouble(), midMonth.toInstant().toEpochMilli(), utc))
        assertEquals("August 2026", bucketOf(ago(45), nowMs, utc))
        assertEquals("Undated", bucketOf(null, nowMs, utc))
        assertEquals("Today", bucketOf(now.plusMinutes(3).toEpochSecond().toDouble(), nowMs, utc), "small clock skew")
    }

    @Test fun `timeline sections follow the sort and project view groups by project`() {
        val f = MediaFilter(showTests = true)
        val titles = buildSections(filterItems(all, f, nowMs, utc), f, nowMs, utc).map { it.title }
        assertEquals(listOf("Today", "Yesterday", "This week", "September 2026", "August 2026", "Undated"), titles)
        val big = MediaFilter(sort = SortKey.Largest)
        assertEquals(listOf("Sorted by largest"), buildSections(filterItems(all, big, nowMs, utc), big, nowMs, utc).map { it.title })
        val p = MediaFilter(view = ViewMode.Projects)
        assertEquals(listOf("Best Friends Night V3", "Pack 5 · Mirror Feedback Loop", "Keepers", "Friends Room"),
            buildSections(filterItems(all, p, nowMs, utc), p, nowMs, utc).map { it.title })
    }

    @Test fun `counts report what the filters can reach`() {
        val c = filterCounts(all, MediaFilter(), nowMs, utc)
        assertEquals(7, c.total); assertEquals(2, c.tests); assertEquals(1, c.keepers)
        // ltx exists only in the hidden test pile, so it must not be offered as a source with clips.
        assertEquals(setOf("vast" to 4, "thunder" to 1), c.sources.toSet())
    }

    @Test fun `source counts include tests once they are shown and ignore the selected source`() {
        assertEquals(setOf("vast" to 4, "thunder" to 1, "ltx" to 2), filterCounts(all, MediaFilter(showTests = true), nowMs, utc).sources.toSet())
        // Picking thunder must not collapse the other chips to zero: counts ignore the source choice.
        assertEquals(setOf("vast" to 4, "thunder" to 1), filterCounts(all, MediaFilter(source = "thunder"), nowMs, utc).sources.toSet())
    }

    @Test fun `source counts follow the other filters`() {
        assertEquals(setOf("vast" to 2), filterCounts(all, MediaFilter(range = DateRange.Today), nowMs, utc).sources.toSet())
        assertEquals(setOf("vast" to 2), filterCounts(all, MediaFilter(query = "bestfriends"), nowMs, utc).sources.toSet())
    }
}
