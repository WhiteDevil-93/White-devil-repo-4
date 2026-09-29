package com.whitedevil.desktop

import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaStateTest {

    private val clip = MediaClip("a.mp4", 1, 1000.0, 1.0, null)
    private val group = MediaGroup("g", "G", "pack", null, 1000.0, listOf(clip))

    // ---- the three UI states ----

    @Test
    fun everyKindOfFailureBecomesAnErrorStateAndNeverEmpty() {
        for (kind in MediaErrorKind.entries) {
            val state = libraryStateFrom(MediaResult.Failure(MediaError(kind, "m", 500, "d")))
            assertIs<LibraryUiState.Error>(state, "for $kind")
            assertEquals(kind, state.error.kind)
        }
    }

    @Test
    fun onlyASuccessfulEmptyAnswerIsEmpty() {
        assertEquals(LibraryUiState.Empty, libraryStateFrom(MediaResult.Ok(ParsedLibrary(emptyList(), emptyList()))))
    }

    @Test
    fun aResponseWhoseEntriesAreAllUnreadableIsAnErrorNotEmpty() {
        val state = libraryStateFrom(MediaResult.Ok(ParsedLibrary(emptyList(), listOf("Group #1 has no id; skipped."))))
        assertIs<LibraryUiState.Error>(state)
        assertEquals(MediaErrorKind.BadResponse, state.error.kind)
        assertTrue("no id" in (state.error.detail ?: ""))
    }

    @Test
    fun aLibraryWithClipsIsLoadedAndKeepsItsWarnings() {
        val state = libraryStateFrom(MediaResult.Ok(ParsedLibrary(listOf(group), listOf("Clip #2 in group \"g\" has no readable name; skipped."))))
        assertIs<LibraryUiState.Loaded>(state)
        assertEquals(1, state.clipCount)
        assertEquals(1, state.warnings.size)
    }

    @Test
    fun parseFailuresFlowThroughToAnErrorState() {
        val parsed = runCatching { MediaParser.parseLibrary("<html>oops</html>") }
        val result: MediaResult<ParsedLibrary> = parsed.fold(
            { MediaResult.Ok(it) },
            { MediaResult.Failure(MediaError(MediaErrorKind.BadResponse, it.message ?: "")) },
        )
        assertIs<LibraryUiState.Error>(libraryStateFrom(result))
    }

    // ---- error text ----

    @Test
    fun errorTitlesCarryTheStatusCodeAndSummariesTheHubsReason() {
        val e = MediaError(MediaErrorKind.ServerError, "The hub failed.", 500, "ffmpeg is not installed on this host")
        assertEquals("HTTP 500 - Hub error", e.title)
        assertEquals("500: ffmpeg is not installed on this host", e.summary)

        val t = MediaError(MediaErrorKind.Timeout, "The hub did not answer within 20 s.")
        assertEquals("Timed out", t.title)
        assertEquals("Timed out: The hub did not answer within 20 s.", t.summary)
    }

    @Test
    fun hubDetailUnderstandsFastApiBodiesAndPlainText() {
        assertEquals("thumbnail failed: x", MediaErrors.hubDetail("""{"detail":"thumbnail failed: x"}"""))
        assertEquals("""[{"msg":"bad"}]""", MediaErrors.hubDetail("""{"detail":[{"msg":"bad"}]}"""))
        assertEquals("Bad gateway", MediaErrors.hubDetail("Bad gateway"))
        assertNull(MediaErrors.hubDetail(""))
        assertNull(MediaErrors.hubDetail(null))
        assertTrue(MediaErrors.hubDetail("x".repeat(5000))!!.length <= 310)
    }

    // ---- gallery keys ----

    @Test
    fun galleryKeysAreUniqueEvenWhenTheHubRepeatsGroupIdsAndClipNames() {
        val dup = MediaClip("same.mp4", null, null, null, null)
        val g1 = MediaGroup("g", "One", null, null, null, listOf(dup, dup))
        val g2 = MediaGroup("g", "Two", null, null, null, listOf(dup))

        val sections = buildGallerySections(listOf(g1, g2))

        val keys = sections.flatMap { listOf(it.headerKey) + it.items.map { i -> i.key } }
        assertEquals(keys.size, keys.toSet().size, "lazy lists crash on duplicate keys: $keys")
        assertEquals(2, sections.size)
        assertEquals(listOf(2, 1), sections.map { it.items.size })
    }

    // ---- formatting ----

    private val now = 1_000_000_000_000L // ms
    private val nowSec = now / 1000.0

    @Test
    fun ageIsRelativeToNow() {
        assertEquals("just now", formatAge(nowSec - 5, now))
        assertEquals("5m ago", formatAge(nowSec - 5 * 60 - 10, now))
        assertEquals("3h ago", formatAge(nowSec - 3 * 3600 - 100, now))
        assertEquals("2d ago", formatAge(nowSec - 2 * 86_400 - 100, now))
        assertEquals("2mo ago", formatAge(nowSec - 65 * 86_400.0, now))
        assertEquals("2y ago", formatAge(nowSec - 800 * 86_400.0, now))
    }

    @Test
    fun missingOrImplausibleTimestampsAreSaidPlainlyNotFabricated() {
        assertEquals("age unknown", formatAge(null, now))
        assertEquals("age unknown", formatAge(Double.NaN, now))
        assertEquals("just now", formatAge(nowSec + 30, now)) // small clock skew
        assertTrue("future" in formatAge(nowSec + 3600, now))
        assertEquals("unknown time", formatAbsolute(null))
    }

    @Test
    fun absoluteTimeAndSizeFormatting() {
        assertEquals("2025-09-29 20:00", formatAbsolute(1759176000.5, ZoneOffset.UTC))
        assertEquals("12.3 MB", formatMb(12.34))
        assertEquals("0.4 MB", formatMb(0.4))
        assertEquals("1.5 GB", formatMb(1500.0))
        assertEquals("size unknown", formatMb(null))
    }

    @Test
    fun kindLabelsKnownKindsAndPassesUnknownOnesThrough() {
        assertEquals("Pack", kindLabel("pack"))
        assertEquals("Keeper", kindLabel("keeper"))
        assertEquals("experimental", kindLabel("experimental"))
        assertNull(kindLabel(null))
        assertNull(kindLabel(""))
    }
}
