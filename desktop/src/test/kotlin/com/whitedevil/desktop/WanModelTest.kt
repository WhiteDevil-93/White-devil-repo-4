package com.whitedevil.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Wan builder must produce what hub/static/generator.html produces. golden.json is recorded from the
 * page's own JavaScript (desktop/src/test/golden/gen_wan_golden.js) on fixed form inputs; every test here
 * compares the Kotlin port with it. Numbers are compared as numbers (the page prints 5, Kotlin 5.0).
 */
class WanModelTest {
    private val golden: JsonArray = Json.parseToJsonElement(
        WanModelTest::class.java.getResourceAsStream("/wan/golden.json")!!.use { String(it.readBytes(), Charsets.UTF_8) },
    ) as JsonArray

    private fun same(a: JsonElement?, b: JsonElement?, path: String = "$"): String? = when {
        a is JsonObject && b is JsonObject ->
            if (a.keys != b.keys) "$path: keys differ ${a.keys.sorted()} vs ${b.keys.sorted()}"
            else a.keys.firstNotNullOfOrNull { k -> same(a[k], b[k], "$path.$k") }
        a is JsonArray && b is JsonArray ->
            if (a.size != b.size) "$path: sizes ${a.size} vs ${b.size}"
            else a.indices.firstNotNullOfOrNull { i -> same(a[i], b[i], "$path[$i]") }
        a is JsonPrimitive && b is JsonPrimitive -> {
            val x = a.doubleOrNull; val y = b.doubleOrNull
            if (!a.isString && !b.isString && x != null && y != null) (if (x == y) null else "$path: $x vs $y")
            else if (a.contentOrNull == b.contentOrNull) null else "$path: <${a.contentOrNull}> vs <${b.contentOrNull}>"
        }
        a is JsonNull && b is JsonNull -> null
        else -> "$path: ${a?.javaClass?.simpleName} vs ${b?.javaClass?.simpleName}"
    }

    private fun brief(form: JsonObject): WanBrief {
        fun s(k: String) = (form[k] as JsonPrimitive).content
        return WanBrief(
            idea = s("idea"), mode = s("mode"), imageDescription = s("image"), cast = s("cast").toInt(), frames = s("duration").toInt(),
            clips = s("clips").toInt(), link = s("link"), beats = s("beats"), camera = s("camera"), framing = s("framing"),
            orient = s("orient"), motion = s("motion"), forbid = s("forbid"), style = s("style"), words = s("words"),
            llmModel = "test/model:free", temperature = 0.7, appendDefaultNegative = (form["wanneg"] as JsonPrimitive).content == "true",
        )
    }

    private fun each(check: (name: String, b: WanBrief, expected: JsonObject) -> Unit) {
        assertTrue(golden.size >= 4)
        golden.forEach { f ->
            val o = f as JsonObject
            check((o["name"] as JsonPrimitive).content, brief(o["form"] as JsonObject), o["expected"] as JsonObject)
        }
    }

    @Test fun `the bundled prompt text is the page's text`() {
        val page = File("../hub/static/generator.html")
        assumeTrue(page.isFile, "needs the repo checkout (hub/static/generator.html)")
        // JavaScript normalises CRLF inside a template literal to LF when it evaluates it, so the page file is read the same way.
        val src = page.readText(Charsets.UTF_8).replace("\r\n", "\n")
        fun template(name: String): String {
            val m = Regex("const $name = (?:\\(\\) => )?`").find(src)!!
            return src.substring(m.range.last + 1, src.indexOf("`;", m.range.last))
        }
        assertEquals(template("CORE_RULES"), WanPrompts.coreRules)
        assertEquals(Regex("const WAN_DEFAULT_NEG = \"((?:[^\"\\\\]|\\\\.)*)\";").find(src)!!.groupValues[1], WanPrompts.defaultNegative)
        val chain = template("SYSTEM_CHAIN").replace("\${mspec().label}", WAN_14B.label).replace("\${mspec().fps}", "16").replace("\${CORE_RULES}", WanPrompts.coreRules)
        assertEquals(chain, WanPrompts.systemChain(WAN_14B))
    }

    @Test fun `system prompt, brief lines and beats match the page`() = each { name, b, e ->
        assertEquals((e["system"] as JsonPrimitive).content, WanPrompts.systemChain(WAN_14B), name)
        assertEquals((e["shared"] as JsonArray).map { (it as JsonPrimitive).content }, sharedLines(b), "$name shared lines")
        assertEquals((e["beats"] as JsonArray).map { (it as JsonPrimitive).content }, beatLines(b), "$name beats")
        assertEquals((e["clip_count"] as JsonPrimitive).intOrNull, b.clipCount, name)
        assertEquals((e["size"] as JsonPrimitive).content, sizeString(b, WAN_14B, "*"), name)
    }

    @Test fun `settings and brief objects match the page`() = each { name, b, e ->
        val meta = WanMeta.now(b, id = "abcdefabcdef", at = "2026-10-03T12:00:00.000Z")
        assertNull(same(meta.settings, e["base_settings"], "settings"), name)
        assertNull(same(meta.brief, e["brief"], "brief"), name)
        assertNull(same(meta.toJson(), e["meta"], "meta"), name)
    }

    @Test fun `the exported chain matches the page's chainJSON`() = each { name, b, e ->
        val meta = WanMeta.now(b, id = "abcdefabcdef", at = "2026-10-03T12:00:00.000Z")
        val chain = parseWanChain(e["chain"])!!
        val mine = chainSpec(chain, meta, b.appendDefaultNegative)
        assertNull(same(mine, e["chain_spec"], "spec"), name)
    }

    @Test fun `negatives are the clip's own then the official default`() = each { name, b, e ->
        assertEquals((e["neg_a"] as JsonPrimitive).content, negativeFor(WanClip("", "", "", "", "extra people"), b.appendDefaultNegative), name)
        assertEquals((e["neg_b"] as JsonPrimitive).content, negativeFor(WanClip("", "", "", "", ""), b.appendDefaultNegative), name)
    }

    @Test fun `the hub request carries what the relay page sends`() {
        val b = brief(((golden[0] as JsonObject)["form"] as JsonObject))
        val meta = WanMeta.now(b, id = "abcdefabcdef", at = "2026-10-03T12:00:00.000Z")
        val r = chainRequest(b, meta, key = null)
        assertEquals(setOf("idea", "system", "shared", "beats", "link", "total", "chunk", "model", "temperature", "key", "meta", "base"), r.keys)
        assertEquals(4, (r["total"] as JsonPrimitive).intOrNull); assertEquals(12, (r["chunk"] as JsonPrimitive).intOrNull)
        assertTrue(r["key"] is JsonNull)
        val base = r["base"] as JsonObject
        assertEquals(setOf("link", "frames", "size", "firstI2V", "task", "ckpt", "fps"), base.keys)
        assertEquals("1280*720", (base["size"] as JsonPrimitive).content); assertEquals("i2v-A14B", (base["task"] as JsonPrimitive).content)
        assertEquals("sk-or-v1-abc", ((chainRequest(b, meta, key = " sk-or-v1-abc ")["key"]) as JsonPrimitive).content)
    }

    @Test fun `javascript number formatting for the clip length is reproduced`() {
        assertEquals("5", jsNumber(5.0)); assertEquals("4.5", jsNumber(4.5)); assertEquals("7.5", jsNumber(7.5)); assertEquals("3", jsNumber(3.0))
    }

    @Test fun `slang is rewritten and unrelated words are left alone`() {
        assertEquals("the older, broader man", plainWords("Big Bro"))
        assertEquals("the two men", plainWords("bros"))
        assertEquals("a broken bronze sculpture", plainWords("a broken bronze sculpture"))
        assertEquals("masturbating and each other's penis", plainWords("jerking off and each others"))
    }

    @Test fun `validation asks for the scene and sane values`() {
        assertEquals("Describe the scene first.", validateBrief(WanBrief(), false))
        assertNull(validateBrief(WanBrief(idea = "a sofa"), false))
        assertNotNull(validateBrief(WanBrief(idea = "x", frames = 50), false))
        assertNotNull(validateBrief(WanBrief(idea = "x", cast = 11), false))
        assertNotNull(validateBrief(WanBrief(idea = "x", llmModel = " "), false))
        assertEquals(2, WanBrief(clips = 0).clipCount); assertEquals(60, WanBrief(clips = 999).clipCount)
    }

    @Test fun `a generation job is read from the hub's shape and describes its progress`() {
        val j = parseGenJob(Json.parseToJsonElement("""{"id":"20261003-1-abcd","status":"running","total":30,"count":3,"next":2,"idea":"x","meta":null,"file":null,"error":null,"log":[],
            "chain":{"link":"continuous","frames":81,"firstI2V":true,"fps":16,"bible":{"characters":"two men","setting":"sofa","style":"photoreal"},
            "clips":[{"title":"A","start_state":"s","prompt":"p","end_state":"e","negative":"n"}]}}"""))!!
        assertEquals("Generating batch 2/3 on the hub (1 of 30 clips so far).", genProgress(j))
        assertEquals("two men", j.chain!!.characters); assertEquals(1, j.chain!!.clips.size)
        assertEquals("Failed: boom", genProgress(j.copy(status = "error", error = "boom")))
        assertEquals("Done — 1 clips in 3 batches.", genProgress(j.copy(status = "done")))
        assertNull(parseGenJob(Json.parseToJsonElement("[]")))
    }

    @Test fun `the start picture goes in the runner block and nothing else changes`() {
        val spec = Json.parseToJsonElement("""{"type":"chain","runner":{"seed":5},"clips":[]}""") as JsonObject
        val out = withStartImage(spec, "data:image/jpeg;base64,AAAA")
        assertEquals("data:image/jpeg;base64,AAAA", (((out["runner"]) as JsonObject)["start_image_b64"] as JsonPrimitive).content)
        assertEquals(5, (((out["runner"]) as JsonObject)["seed"] as JsonPrimitive).intOrNull)
        assertEquals(spec, withStartImage(spec, null))
    }
}
