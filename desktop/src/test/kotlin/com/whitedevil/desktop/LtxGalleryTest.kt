package com.whitedevil.desktop

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live hub files every clip that is not in a pack, chain or keeper set under one group, "Tests &
 * experiments". On 2026-10-03 that group held 869 clips, 710 of them LTX renders. Hiding tests by default
 * therefore hid the user's LTX work, and the Gallery looked like it had no LTX at all.
 */
class LtxGalleryTest {
    private val utc = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 10, 3, 18, 0, 0, 0, utc)
    private val nowMs = now.toInstant().toEpochMilli()
    private fun t(h: Long) = now.minusHours(h).toEpochSecond().toDouble()
    private fun clip(name: String, mtime: Double, source: String? = null) = MediaClip(name, null, mtime, 5.0, source)

    // the shape of the live library: one catch-all group holding LTX renders and real smoke tests together
    private val groups = listOf(
        MediaGroup("tests", "Tests & experiments", "test", "ltx", t(1), listOf(
            clip("ltx_chain_duration-10-seconds_1fc9ca3b01a7.mp4", t(1), "ltx"),
            clip("ltx_two-men-on-a-sofa_b2c3d4e5f6a7_97f.mp4", t(5), "ltx"),
            clip("LTX-extra.mp4", t(6)),                       // the name marker alone is enough
            clip("anything.mp4", t(7), "ltx"),                 // and so is the hub's own source marker
            clip("smoke_ti2v5b_CLEAN_BASE_832x480_f81_s20_seed42.mp4", t(9)),
            clip("smoke_002_cleanfallback_seed9009_832x480_f81.mp4", t(10)),
        )),
        MediaGroup("c", "Best Friends Night V3", "chain", "vast", t(2), listOf(clip("smoke_bestfriends-night-v3_c01_wanbot.mp4", t(2)))),
    )

    @Test fun `ltx renders are split out of the test group into their own project`() {
        val split = splitLtx(groups)
        assertEquals(listOf("ltx-renders", "tests", "c"), split.map { it.id })
        val ltx = split.first { it.id == "ltx-renders" }
        assertEquals("LTX 2.5 renders", ltx.title); assertEquals("ltx", ltx.kind); assertEquals("ltx", ltx.source)
        assertEquals(4, ltx.clips.size); assertEquals(t(1), ltx.updated)
        val tests = split.first { it.id == "tests" }
        assertEquals(2, tests.clips.size); assertTrue(tests.clips.all { it.name.startsWith("smoke_") })
        assertEquals("test", tests.kind); assertNull(tests.source, "the leftover tests are not LTX, so they must not keep the ltx source")
    }

    @Test fun `groups without ltx clips and non test groups are left untouched`() {
        val plain = listOf(groups[1], MediaGroup("t2", "Tests", "test", null, null, listOf(clip("smoke_a.mp4", t(1)))))
        assertEquals(plain, splitLtx(plain))
        val ltxOnlyInChain = listOf(MediaGroup("x", "Chain", "chain", "vast", null, listOf(clip("ltx_in_a_chain.mp4", t(1)))))
        assertEquals(ltxOnlyInChain, splitLtx(ltxOnlyInChain), "only the catch-all test group is split")
    }

    @Test fun `with tests hidden the default gallery still shows every ltx render`() {
        val all = buildGallerySections(groups).flatMap { it.items }
        val shown = filterItems(all, MediaFilter(), nowMs, utc).map { it.clip.name }
        listOf("ltx_chain_duration-10-seconds_1fc9ca3b01a7.mp4", "ltx_two-men-on-a-sofa_b2c3d4e5f6a7_97f.mp4", "LTX-extra.mp4", "anything.mp4")
            .forEach { assertTrue(it in shown, "$it should be visible by default") }
        assertFalse(shown.any { it.startsWith("smoke_ti2v5b") || it.startsWith("smoke_002") }, "real tests stay hidden")
        assertTrue("smoke_bestfriends-night-v3_c01_wanbot.mp4" in shown)
    }

    @Test fun `ltx is its own source and project, and the counts say so`() {
        val all = buildGallerySections(groups).flatMap { it.items }
        val c = filterCounts(all, MediaFilter(), nowMs, utc)
        assertEquals(mapOf("ltx" to 4, "vast" to 1), c.sources.toMap())
        assertEquals(2, c.tests, "only the two real tests count as tests")
        val onlyLtx = filterItems(all, MediaFilter(source = "ltx"), nowMs, utc)
        assertEquals(4, onlyLtx.size)
        val byProject = buildSections(filterItems(all, MediaFilter(view = ViewMode.Projects), nowMs, utc), MediaFilter(view = ViewMode.Projects), nowMs, utc)
        assertEquals("LTX 2.5 renders", byProject.first().title)
        assertEquals("LTX", kindLabel("ltx"))
    }

    @Test fun `searching ltx finds them and showing tests brings back only the real tests`() {
        val all = buildGallerySections(groups).flatMap { it.items }
        assertEquals(4, filterItems(all, MediaFilter(query = "ltx"), nowMs, utc).size)
        assertEquals(2, filterItems(all, MediaFilter(showTests = true), nowMs, utc).count { it.clip.name.startsWith("smoke_0") || it.clip.name.startsWith("smoke_ti2v5b") })
    }

    // ---- Venice model picker

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val modelsJson = """{"object":"list","data":[
      {"id":"zai-org-glm-5-2","context_length":1000000,"model_spec":{"name":"GLM 5.2","availableContextTokens":1000000,"offline":false,"capabilities":{"supportsFunctionCalling":true,"supportsReasoning":true}}},
      {"id":"gemini-3-6-flash","model_spec":{"name":"Gemini 3.6 Flash","availableContextTokens":1048576,"offline":false,"capabilities":{"supportsFunctionCalling":true,"supportsReasoning":false}}},
      {"id":"old-offline","model_spec":{"name":"Offline One","availableContextTokens":8000,"offline":true,"capabilities":{"supportsFunctionCalling":true}}},
      {"id":"no-tools","model_spec":{"name":"No Tools","availableContextTokens":8000,"offline":false,"capabilities":{"supportsFunctionCalling":false}}},
      {"id":"bare"},
      {"model_spec":{"name":"no id"}}]}"""

    @Test fun `venice models are read, and only online tool-calling ones are offered`() {
        val all = parseVeniceModels(Json.parseToJsonElement(modelsJson))!!
        assertEquals(5, all.size, "the entry without an id is dropped")
        assertEquals(listOf("gemini-3-6-flash", "zai-org-glm-5-2"), usableVeniceModels(all).map { it.id }, "sorted by name; offline and no-tools removed")
        assertEquals("1M", all.first { it.id == "zai-org-glm-5-2" }.contextLabel); assertEquals("1M", all.first { it.id == "gemini-3-6-flash" }.contextLabel)
        assertEquals("8K", all.first { it.id == "no-tools" }.contextLabel); assertNull(all.first { it.id == "bare" }.contextLabel)
        assertEquals("bare", all.first { it.id == "bare" }.name, "a model with no name shows its id")
        assertNull(parseVeniceModels(Json.parseToJsonElement("[]")))
    }

    @Test fun `the model search matches name or id and needs every word`() {
        val m = usableVeniceModels(parseVeniceModels(Json.parseToJsonElement(modelsJson))!!)
        assertEquals(listOf("zai-org-glm-5-2"), filterVeniceModels(m, "glm 5").map { it.id })
        assertEquals(listOf("gemini-3-6-flash"), filterVeniceModels(m, "FLASH").map { it.id })
        assertEquals(emptyList(), filterVeniceModels(m, "glm flash"))
        assertEquals(2, filterVeniceModels(m, "  ").size)
    }

    @Test fun `fetching models sends the key and reports failures in words`() = runBlocking {
        var auth: String? = null; var path = ""
        val ok = fetchVeniceModels("sk-test", MockEngine { req -> auth = req.headers[HttpHeaders.Authorization]; path = req.url.encodedPath + "?" + req.url.encodedQuery; respond(modelsJson, HttpStatusCode.OK, json) })
        assertEquals(5, (ok as MediaResult.Ok).value.size)
        assertEquals("Bearer sk-test", auth); assertEquals("/api/v1/models?type=text", path)
        val rejected = fetchVeniceModels("bad", MockEngine { respond("{}", HttpStatusCode.Unauthorized, json) }) as MediaResult.Failure
        assertEquals("Venice rejected the API key. Check it in Settings.", rejected.error.message)
        var calls = 0
        val noKey = fetchVeniceModels("  ", MockEngine { calls++; respond("{}", HttpStatusCode.OK, json) }) as MediaResult.Failure
        assertTrue("API key" in noKey.error.message); assertEquals(0, calls, "no key means no request")
        assertTrue(fetchVeniceModels("k", MockEngine { respond("<html>", HttpStatusCode.OK) }) is MediaResult.Failure)
    }
}
