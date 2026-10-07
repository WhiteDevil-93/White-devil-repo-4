package com.whitedevil.desktop

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupBotTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun client(e: MockEngine) = SetupBotClient("https://hub.example", "anon3", "pw", e)
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun str(o: JsonObject, k: String) = (o[k] as JsonPrimitive).content

    // shaped like the live GET /api/setup/catalog
    private val catalogJson = """{
      "recipes":[
        {"id":"ltx25","title":"LTX-2.5 NSFW kit (ComfyUI)","summary":"LTX-2.5 22B.","min_vram_gb":80,"options":[
          {"key":"TRANSFORMER","label":"Video model","type":"choice","default":"stubelius_b1","choices":[["stubelius_b1","Stubelius Remix"],["distilled","Official distilled"]]},
          {"key":"EXTRAS","label":"Extras","type":"bool","default":false},
          {"key":"CONTENT_LORAS","label":"Content LoRAs","type":"bool","default":true}]},
        {"id":"remix14","title":"Wan 2.2 Remix 14B (ComfyUI)","summary":"ComfyUI with the Remix 14B models.","min_vram_gb":40,"options":[
          {"key":"MODE","label":"Which models","type":"choice","default":"both","choices":[["both","Both"],["i2v","Image-to-video only"]]},
          {"key":"SKIP_COMFY","label":"Keep the existing ComfyUI","type":"bool","default":false}]}],
      "existing":[{"kind":"thunder","id":"7","name":"box","gpu":"l40","num_gpus":1,"vram_gb":48,"status":"RUNNING","price":0.69,"renders_here":true}],
      "new":[{"gpu_type":"a6000","num_gpus":1,"name":"RTX A6000","vram_gb":48,"price":0.35,"cpu_options":[6,8],"available":true},
             {"gpu_type":"l40","num_gpus":1,"name":"L40","vram_gb":48,"price":0.69,"cpu_options":[8,16],"available":true},
             {"gpu_type":"h100","num_gpus":1,"name":"H100","vram_gb":80,"price":2.0,"cpu_options":[16],"available":false}],
      "vast":{"credit":12.5,"error":null,
        "existing":[{"id":"53733969","label":"x","gpu":"RTX 5090","num_gpus":1,"vram_gb":32,"status":"exited","location":"Hungary, HU","price":0.678}],
        "offers":[{"id":"53396363","gpu":"A100 SXM4","num_gpus":1,"vram_gb":40,"price":0.462,"location":"California, US","reliability":97.5}]},
      "colab":{"kind":"colab","label":"Colab G4 (RTX PRO 6000, 96 GB)","vram_gb":96},"thunder_error":null}"""

    private val catalog get() = parseSetupCatalog(Json.parseToJsonElement(catalogJson))!!

    @Test fun `the catalog is read from the live shape`() {
        val c = catalog
        assertEquals(listOf("ltx25", "remix14"), c.recipes.map { it.id })
        val ltx = c.recipes[0]
        assertEquals(80, ltx.minVramGb); assertEquals(3, ltx.options.size)
        assertEquals(mapOf<String, Any>("TRANSFORMER" to "stubelius_b1", "EXTRAS" to false, "CONTENT_LORAS" to true), ltx.defaults())
        assertEquals("Official distilled", ltx.options[0].choices[1].label)
        assertTrue(c.thunderExisting.single().rendersHere); assertEquals(0.69, c.thunderExisting.single().price)
        assertEquals(3, c.thunderNew.size); assertFalse(c.thunderNew[2].available); assertEquals(listOf(6, 8), c.thunderNew[0].cpuOptions)
        assertEquals("53733969", c.vastExisting.single().id); assertEquals(97.5, c.vastOffers.single().reliability); assertEquals("97.5", c.vastOffers.single().reliabilityLabel); assertEquals("98", c.vastOffers.single().copy(reliability = 98.0).reliabilityLabel); assertNull(c.vastError)
        assertEquals("Colab G4 (RTX PRO 6000, 96 GB)", c.colabLabel)
        assertNull(parseSetupCatalog(Json.parseToJsonElement("[]")))
    }

    @Test fun `the where choices match the web page and gate the Vast one on there being offers`() {
        val keys = whereChoices(catalog).map { it.key }
        assertEquals(listOf("thunder:7", "new", "vast:53733969", "vastnew", "colab"), keys)
        assertTrue(whereChoices(catalog)[0].label.contains("14B renders here") && whereChoices(catalog)[0].label.contains("$0.69/h"))
        val none = catalog.copy(vastOffers = emptyList(), vastError = "timed out")
        assertFalse(whereChoices(none).first { it.key == "vastnew" }.enabled)
        assertTrue(whereChoices(none).first { it.key == "vastnew" }.label.endsWith("(unavailable)"))
        assertTrue(canMoveRenders("new") && canMoveRenders("thunder:7") && !canMoveRenders("colab") && !canMoveRenders("vast:1") && !canMoveRenders("vastnew"))
    }

    @Test fun `the plan the form builds is exactly the one the web form builds`() {
        val c = catalog
        val opts = mapOf<String, Any>("MODE" to "i2v", "SKIP_COMFY" to true)
        fun plan(where: String, move: Boolean = false) = buildPlan("remix14", targetFromForm(where, c, 1, 16, 200, "53396363", 150)!!, opts, move)

        val thunder = plan("thunder:7", move = true)
        assertEquals("remix14", str(thunder, "recipe"))
        assertEquals(setOf("kind", "id"), (thunder["target"] as JsonObject).keys); assertEquals("thunder", str(thunder["target"] as JsonObject, "kind"))
        assertEquals("i2v", str(thunder["options"] as JsonObject, "MODE")); assertEquals(true, ((thunder["options"] as JsonObject)["SKIP_COMFY"] as JsonPrimitive).booleanOrNull)
        assertEquals(true, (thunder["use_for_renders"] as JsonPrimitive).booleanOrNull)

        val fresh = plan("new").get("target") as JsonObject
        assertEquals(setOf("kind", "gpu_type", "num_gpus", "cpu_cores", "disk_gb"), fresh.keys)
        assertEquals("l40", str(fresh, "gpu_type")); assertEquals(16, (fresh["cpu_cores"] as JsonPrimitive).intOrNull); assertEquals(200, (fresh["disk_gb"] as JsonPrimitive).intOrNull)

        val vastNew = plan("vastnew").get("target") as JsonObject
        assertEquals(setOf("kind", "offer_id", "disk_gb", "gpu", "price"), vastNew.keys)
        assertEquals("53396363", str(vastNew, "offer_id")); assertEquals(150, (vastNew["disk_gb"] as JsonPrimitive).intOrNull); assertEquals("A100 SXM4", str(vastNew, "gpu"))

        val vastTarget = plan("vast:53733969")["target"] as JsonObject
        assertEquals(setOf("kind", "id"), vastTarget.keys); assertEquals("vast", str(vastTarget, "kind")); assertEquals("53733969", str(vastTarget, "id"))
        assertEquals("colab", str(plan("colab")["target"] as JsonObject, "kind"))
        assertNull(targetFromForm("vastnew", c, 0, null, 0, "no-such-offer", 0), "an offer that is gone is not a target")
        assertNull(targetFromForm("garbage", c, 0, null, 0, null, 0))
    }

    @Test fun `only a thunder target can take the 14B renders`() {
        val c = catalog; val o = emptyMap<String, Any>()
        fun move(where: String) = (buildPlan("r", targetFromForm(where, c, 0, 8, 0, "53396363", 0)!!, o, true)["use_for_renders"] as JsonPrimitive).booleanOrNull
        assertEquals(true, move("new")); assertEquals(true, move("thunder:7"))
        assertEquals(false, move("colab")); assertEquals(false, move("vast:1")); assertEquals(false, move("vastnew"))
    }

    @Test fun `a cpu count is chosen the way the page does`() {
        val c = catalog
        val t = targetFromForm("new", c, 0, null, 0, null, 0) as SetupTarget.ThunderNew
        assertEquals(8, t.cpuCores, "8 when the GPU offers it")
        assertEquals(16, (targetFromForm("new", c, 2, null, 0, null, 0) as SetupTarget.ThunderNew).cpuCores, "else the first offered")
        assertTrue(SetupTarget.ThunderNew("a", 1, 8, 0).createsMachine && SetupTarget.VastNew("1", 0, null, null).createsMachine && !SetupTarget.Colab.createsMachine)
    }

    // shaped like POST /api/setup/preview
    private val previewJson = """{"plan":{"recipe":"remix14","target":{"kind":"thunder_new","gpu_type":"l40","num_gpus":1,"cpu_cores":8,"disk_gb":250},"options":{"MODE":"i2v","SKIP_COMFY":false},"use_for_renders":true},
      "info":{"recipe":"Wan 2.2 Remix 14B (ComfyUI)","options":{"MODE":"i2v","SKIP_COMFY":false},"disk_need_gb":130,"minutes":25,"target":"New Thunder L40 x1, 8 CPU cores, 250 GB disk","gpu":"l40","vram_gb":48,"price":0.79},
      "steps":["Create the instance","Wait until it's running"],"warnings":["A new instance bills `$0.79/h` until you delete it."],"blocking":[],"ok":true,"needs_secret":null}"""

    @Test fun `a plan preview is read, including what blocks it`() {
        val p = parsePlanPreview(Json.parseToJsonElement(previewJson))!!
        assertTrue(p.ok); assertEquals("Wan 2.2 Remix 14B (ComfyUI)", p.recipe); assertEquals(0.79, p.price); assertEquals(130, p.diskNeedGb); assertEquals(25, p.minutes); assertEquals(48, p.vramGb)
        assertEquals("thunder_new", p.targetKind); assertTrue(p.useForRenders); assertEquals(mapOf("MODE" to "i2v", "SKIP_COMFY" to "no"), p.options)
        assertEquals(2, p.steps.size); assertEquals(1, p.warnings.size); assertNull(p.needsSecret)
        val blocked = parsePlanPreview(Json.parseToJsonElement(previewJson.replace("\"blocking\":[]", "\"blocking\":[\"Only 40 GB free\"]").replace("\"ok\":true", "\"ok\":false").replace("\"needs_secret\":null", "\"needs_secret\":\"HF_TOKEN\"")))!!
        assertFalse(blocked.ok); assertEquals(listOf("Only 40 GB free"), blocked.blocking); assertEquals("HF_TOKEN", blocked.needsSecret)
        assertNull(parsePlanPreview(Json.parseToJsonElement("""{"ok":true}""")), "no info and no plan is not a plan")
        val c = parseChatReply(Json.parseToJsonElement("""{"reply":"Here you go.","preview":$previewJson}"""))!!
        assertEquals("Here you go.", c.reply); assertNotNull(c.preview)
        assertNull(parseChatReply(Json.parseToJsonElement("""{"reply":"just words","preview":null}"""))!!.preview)
    }

    @Test fun `change it puts the plan back in the form`() {
        val p = parsePlanPreview(Json.parseToJsonElement(previewJson))!!
        assertEquals("new", whereKeyFor(p.plan))
        assertEquals("vastnew", whereKeyFor(obj("""{"target":{"kind":"vast_new"}}""")))
        assertEquals("vast:9", whereKeyFor(obj("""{"target":{"kind":"vast","id":"9"}}""")))
        assertEquals("thunder:7", whereKeyFor(obj("""{"target":{"kind":"thunder","id":"7"}}""")))
        assertEquals("colab", whereKeyFor(obj("""{"target":{"kind":"colab"}}""")))
        assertNull(whereKeyFor(obj("""{"target":{"kind":"mystery"}}""")))
    }

    @Test fun `the confirmation says what it costs and warns when a machine is created`() {
        val lines = runConsequences(parsePlanPreview(Json.parseToJsonElement(previewJson))!!).joinToString("\n")
        assertTrue("Installs Wan 2.2 Remix 14B" in lines && "$0.79/h" in lines && "130 GB" in lines && "25 minutes" in lines)
        assertTrue("NEW machine" in lines && "14B renders move" in lines)
        val colab = runConsequences(parsePlanPreview(Json.parseToJsonElement(previewJson.replace("thunder_new", "colab").replace("\"price\":0.79", "\"price\":null")))!!).joinToString("\n")
        assertTrue("Colab credits" in colab); assertFalse("NEW machine" in colab)
    }

    // shaped like GET /api/setup/runs
    private val runsJson = """[
      {"id":"a1b2c3d4e5f6","status":"installing","step":"Installing models","created":1790000000.5,"launched":1790000100.0,"log_tail":"downloading...","info":{"recipe":"LTX-2.5 NSFW kit","target":"Colab G4"},"plan":{"target":{"kind":"colab"}}},
      {"id":"b2","status":"done","step":"Done","info":{"recipe":"r","target":"t"},"plan":{"target":{"kind":"thunder"}}},
      {"id":"../bad","status":"done"}, "junk"]"""

    @Test fun `runs are read and a reply that is not a list is not 'nothing set up'`() {
        val runs = parseSetupRuns(Json.parseToJsonElement(runsJson))!!
        assertEquals(listOf("a1b2c3d4e5f6", "b2"), runs.map { it.id })
        assertTrue(runs[0].live); assertEquals("Installing", runs[0].statusWord); assertEquals("colab", runs[0].targetKind); assertEquals("downloading...", runs[0].logTail)
        assertFalse(runs[1].live); assertEquals("Done", runs[1].statusWord)
        assertNull(parseSetupRuns(Json.parseToJsonElement("""{"detail":"nope"}""")))
        assertEquals("Failed", SetupRun("x", "failed", "", "", "", null, null, "", null).statusWord)
    }

    // ---- the client, against a fake hub

    @Test fun `the catalog and runs are read from the real routes with relay auth`() = runBlocking {
        val seen = mutableListOf<String>(); var auth: String? = null
        val engine = MockEngine { req -> seen += req.method.value + " " + req.url.encodedPath; auth = req.headers[HttpHeaders.Authorization]; respond(if (req.url.encodedPath.endsWith("catalog")) catalogJson else runsJson, HttpStatusCode.OK, json) }
        val c = client(engine)
        assertEquals(2, (c.catalog() as MediaResult.Ok).value.recipes.size)
        assertEquals(2, (c.runs() as MediaResult.Ok).value.size)
        assertEquals(listOf("GET /api/setup/catalog", "GET /api/setup/runs"), seen); assertTrue(auth!!.startsWith("Basic "))
    }

    @Test fun `preview and start send the plan unchanged`() = runBlocking {
        val plan = obj(previewJson).get("plan") as JsonObject
        val bodies = mutableListOf<String>(); val paths = mutableListOf<String>()
        val engine = MockEngine { req ->
            paths += req.url.encodedPath; bodies += String(req.body.toByteArray())
            respond(if (req.url.encodedPath.endsWith("preview")) previewJson else """{"id":"c3d4e5f6a7b8","status":"planned"}""", HttpStatusCode.OK, json)
        }
        val c = client(engine)
        assertTrue((c.preview(plan) as MediaResult.Ok).value.ok)
        assertEquals("c3d4e5f6a7b8", (c.startRun(plan) as MediaResult.Ok).value)
        assertEquals(listOf("/api/setup/preview", "/api/setup/runs"), paths)
        bodies.forEach { assertEquals(plan, Json.parseToJsonElement(it)) }
    }

    @Test fun `the hub's refusal is shown in the hub's words`() = runBlocking {
        val e = client(MockEngine { respond("""{"detail":"A setup is already running on that machine."}""", HttpStatusCode.Conflict, json) })
            .startRun(obj(previewJson).get("plan") as JsonObject).let { it as MediaResult.Failure }.error
        assertEquals("A setup is already running on that machine.", e.message); assertEquals(409, e.status)
    }

    @Test fun `chat sends the sentence and only the last six turns of history`() = runBlocking {
        var body = ""
        val c = client(MockEngine { req -> body = String(req.body.toByteArray()); respond("""{"reply":"ok","preview":null}""", HttpStatusCode.OK, json) })
        val history = (1..9).map { (if (it % 2 == 1) "user" else "assistant") to "turn $it" }
        assertEquals("ok", (c.chat("  put it on vast  ", history) as MediaResult.Ok).value.reply)
        val o = obj(body)
        assertEquals("put it on vast", str(o, "text")); val h = o["history"] as JsonArray
        assertEquals(6, h.size); assertEquals("turn 4", str(h[0] as JsonObject, "text")); assertEquals("turn 9", str(h[5] as JsonObject, "text"))
        assertTrue(client(MockEngine { respond("{}", HttpStatusCode.OK, json) }).chat("  ", emptyList()) is MediaResult.Failure)
    }

    @Test fun `cancel and secrets are checked before anything is sent`() = runBlocking {
        var calls = 0; var path = ""; var method: HttpMethod? = null; var body = ""
        val c = client(MockEngine { req -> calls++; path = req.url.encodedPath; method = req.method; body = String(req.body.toByteArray()); respond("""{"ok":true}""", HttpStatusCode.OK, json) })
        assertTrue(c.cancelRun("a1b2c3d4e5f6") is MediaResult.Ok); assertEquals("/api/setup/runs/a1b2c3d4e5f6/cancel", path); assertEquals(HttpMethod.Post, method)
        assertTrue(c.cancelRun("../../etc") is MediaResult.Failure)
        assertTrue(c.saveSecret("SOMETHING_ELSE", "x") is MediaResult.Failure); assertTrue(c.saveSecret("HF_TOKEN", "  ") is MediaResult.Failure)
        assertEquals(1, calls, "only the valid cancel reached the hub")
        val r = (c.saveSecret("HF_TOKEN", " hf_abcdefghijklmnopqrstuvwxyz0123456789 ") as MediaResult.Ok).value
        assertTrue(r.ok); assertEquals("/api/setup/secret", path)
        assertEquals("hf_abcdefghijklmnopqrstuvwxyz0123456789", str(obj(body), "value")); assertEquals("HF_TOKEN", str(obj(body), "name"))
        assertEquals("Saved on the hub.", secretOutcomeMessage(r))
        assertTrue(secretOutcomeMessage(SecretResult(false, "gated")).contains("licence")); assertTrue(secretOutcomeMessage(SecretResult(false, "rejected")).contains("rejected"))
    }
}
