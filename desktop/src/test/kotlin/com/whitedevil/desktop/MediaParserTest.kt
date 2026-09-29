package com.whitedevil.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaParserTest {

    @Test
    fun parsesRealShapedLibrary() {
        val lib = MediaParser.parseLibrary(SAMPLE_LIBRARY)

        assertEquals(emptyList(), lib.warnings)
        assertEquals(2, lib.groups.size)
        assertEquals(3, lib.clipCount)

        val pack = lib.groups[0]
        assertEquals("goon-p03", pack.id)
        assertEquals("Pack 3 · Neon Nights", pack.title) // non-ASCII survives
        assertEquals("pack", pack.kind)
        assertEquals("thunder", pack.source)
        assertEquals(1759176100.25, pack.updated)
        assertEquals(2, pack.clips.size)
        assertEquals(MediaClip("smoke_goon_p03_c01_14b.mp4", 1, 1759176000.5, 12.3, "thunder"), pack.clips[0])
        assertEquals(24.2, pack.totalMb!!, 1e-9)

        val tests = lib.groups[1]
        assertNull(tests.clips[0].idx) // JSON null stays null, not 0
        assertEquals("scratch test #1 (final).mp4", tests.clips[0].name)
    }

    @Test
    fun emptyListIsAGenuinelyEmptyLibrary() {
        val lib = MediaParser.parseLibrary("[]")
        assertEquals(0, lib.groups.size)
        assertEquals(emptyList(), lib.warnings)
    }

    @Test
    fun unknownFieldsAreIgnoredAndMissingOptionalFieldsAreNullNotInvented() {
        val lib = MediaParser.parseLibrary(
            """[{"id":"g","future_field":{"x":1},"clips":[{"name":"a.mp4","surprise":[1,2,3]}]}]""",
        )
        assertEquals(emptyList(), lib.warnings)
        val g = lib.groups.single()
        assertEquals("g", g.title)          // falls back to the id
        assertNull(g.kind)
        assertNull(g.source)
        assertNull(g.updated)               // no mtime anywhere: unknown, not epoch 0
        val c = g.clips.single()
        assertEquals("a.mp4", c.name)
        assertNull(c.idx)
        assertNull(c.mtime)
        assertNull(c.mb)
        assertNull(c.source)
        assertNull(g.totalMb)               // a partial sum would understate
    }

    @Test
    fun groupUpdatedIsDerivedFromClipsOnlyWhenTheHubOmittedIt() {
        val lib = MediaParser.parseLibrary(
            """[{"id":"g","clips":[{"name":"a.mp4","mtime":10.0},{"name":"b.mp4","mtime":30.0},{"name":"c.mp4"}]}]""",
        )
        assertEquals(30.0, lib.groups.single().updated)
    }

    @Test
    fun wrongTypedFieldsBecomeNullRatherThanCrashing() {
        val lib = MediaParser.parseLibrary(
            """[{"id":"g","kind":5,"clips":[{"name":"a.mp4","idx":"three","mtime":"yesterday","mb":null}]}]""",
        )
        val c = lib.groups.single().clips.single()
        assertNull(lib.groups.single().kind)
        assertNull(c.idx)
        assertNull(c.mtime)
        assertNull(c.mb)
    }

    @Test
    fun integralFloatIdxIsAccepted() {
        val lib = MediaParser.parseLibrary("""[{"id":"g","clips":[{"name":"a.mp4","idx":4.0}]}]""")
        assertEquals(4, lib.groups.single().clips.single().idx)
    }

    @Test
    fun malformedEntriesAreSkippedWithAVisibleWarningAndGoodOnesSurvive() {
        val lib = MediaParser.parseLibrary(
            """[
              {"id":"good","clips":[{"name":"ok.mp4"},{"idx":2},{"name":""},7]},
              {"title":"no id here","clips":[]},
              "not an object",
              {"id":"noclips"}
            ]""",
        )
        assertEquals(listOf("good", "noclips"), lib.groups.map { it.id })
        assertEquals(listOf("ok.mp4"), lib.groups[0].clips.map { it.name })
        // 3 bad clips + 1 group without id + 1 non-object + 1 group without a clips list
        assertEquals(6, lib.warnings.size, lib.warnings.toString())
        assertTrue(lib.warnings.any { "no id" in it })
        assertTrue(lib.warnings.any { "not an object" in it })
        assertTrue(lib.warnings.any { "no clips list" in it })
    }

    @Test
    fun manyWarningsAreCappedButTheHiddenCountIsStated() {
        val bad = (1..25).joinToString(",") { "{\"idx\":$it}" }
        val lib = MediaParser.parseLibrary("""[{"id":"g","clips":[$bad]}]""")
        assertEquals(11, lib.warnings.size)
        assertTrue(lib.warnings.last().contains("15 more"))
    }

    @Test
    fun badBodiesAreParseErrorsNotEmptyLists() {
        val bodies = listOf(
            "" to "empty",
            "   \n " to "empty",
            "<!DOCTYPE html><html><body>Bad gateway</body></html>" to "not valid JSON",
            """[{"id":"g","clips":[""" to "not valid JSON",           // truncated
            "null" to "null",
            "42" to "number",
            "\"oops\"" to "string",
            """{"detail":"Not Found"}""" to "Not Found",             // FastAPI error body
            "{}" to "object",
        )
        for ((body, expectedFragment) in bodies) {
            val e = assertFailsWith<MediaParseException>("body: $body") { MediaParser.parseLibrary(body) }
            assertTrue(expectedFragment in e.message!!, "message for `$body` was: ${e.message}")
        }
    }

    @Test
    fun aBareWordOrTagWithoutSpacesIsNotMistakenForANumber() {
        // kotlinx parses an unquoted bare word as a top-level literal; a proxy's login page
        // such as <html>login</html> must still be reported as "not valid JSON".
        for (body in listOf("<html>login</html>", "Unauthorized", "Bad_Gateway", "<h1>502</h1>")) {
            val e = assertFailsWith<MediaParseException>("body: $body") { MediaParser.parseLibrary(body) }
            assertTrue("not valid JSON" in e.message!!, "message for `$body` was: ${e.message}")
            assertTrue(body in e.message!!, "the message should quote what the hub sent: ${e.message}")
        }
        // ...while real primitives keep their honest description
        assertTrue("a boolean" in assertFailsWith<MediaParseException> { MediaParser.parseLibrary("true") }.message!!)
        assertTrue("a number" in assertFailsWith<MediaParseException> { MediaParser.parseLibrary("-3.5e2") }.message!!)
    }

    @Test
    fun byteOrderMarkIsTolerated() {
        assertEquals(0, MediaParser.parseLibrary("﻿[]").groups.size)
    }
}
