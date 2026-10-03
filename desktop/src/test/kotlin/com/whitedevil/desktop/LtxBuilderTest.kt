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
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LtxBuilderTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun client(engine: MockEngine) = LtxBuilderClient("https://hub.example", "anon3", "pw", engine)
    private val pic = BuilderPicture("start.PNG", byteArrayOf(1, 2, 3, 4, 5))
    private val idea = "he looks at the camera and smiles slowly"

    // ---- which endpoint, which fields ----

    @Test fun `one fresh clip goes to render and everything else goes to chain`() {
        assertEquals("/api/ltx/render", BuildRequest(idea).endpoint)
        assertEquals("/api/ltx/chain", BuildRequest(idea, parts = 2).endpoint)
        assertEquals("/api/ltx/chain", BuildRequest(idea, continueFrom = "abc123").endpoint)
        assertEquals("/api/ltx/chain", BuildRequest(idea, parts = 1, continueFrom = "abc123").endpoint)
    }

    @Test fun `render fields use prompt and chain fields use idea, as the hub expects`() {
        val single = BuildRequest(idea, frames = 97, size = "portrait", seed = 42).fields().toMap()
        assertEquals(idea, single["prompt"]); assertEquals("97", single["frames"]); assertEquals("portrait", single["size"]); assertEquals("42", single["seed"])
        assertFalse("idea" in single); assertFalse("parts" in single)
        val chain = BuildRequest(idea, parts = 20, frames = 241).fields().toMap()
        assertEquals(idea, chain["idea"]); assertEquals("20", chain["parts"]); assertEquals("241", chain["frames"]); assertEquals("landscape", chain["size"])
        assertFalse("prompt" in chain); assertFalse("seed" in chain, "no seed field unless one was typed")
    }

    @Test fun `a continuation sends from_job and not size, because the hub takes the size from that clip`() {
        val f = BuildRequest(idea, parts = 3, continueFrom = "abc123", size = "portrait").fields().toMap()
        assertEquals("abc123", f["from_job"]); assertFalse("size" in f)
    }

    @Test fun `opts are sent as the json the hub parses`() {
        val o = BuilderOpts(transformer = "t.safetensors", loras = listOf("a.safetensors" to 0.65, "b.safetensors" to 0.45), distill = 0.8, vae = "fast", clip = null, writer = "x-ai/grok-4.5")
        val text = BuildRequest(idea, opts = o).fields().toMap().getValue("opts")
        val back = BuilderOpts.fromJson(Json.parseToJsonElement(text))
        assertEquals(o, back)
    }

    // ---- the same checks as the web builder ----

    @Test fun `validation messages match the web builder`() {
        assertEquals("Describe who is in the video and what happens.", validateRequest(BuildRequest("short")))
        assertEquals("Write what happens in the clip.", validateRequest(BuildRequest("short", picture = pic)))
        assertEquals("With no picture, describe who is in the video and what happens.", validateRequest(BuildRequest("", parts = 3)))
        assertNull(validateRequest(BuildRequest(idea)))
        assertNull(validateRequest(BuildRequest("", parts = 3, picture = pic)), "a picture alone is enough for a chain")
        assertNull(validateRequest(BuildRequest("", parts = 2, continueFrom = "abc123")), "continuing needs no text")
        assertNotNull(validateRequest(BuildRequest(idea, parts = 21)))
        assertNotNull(validateRequest(BuildRequest(idea, parts = 0)))
        assertNotNull(validateRequest(BuildRequest(idea, frames = 50)))
        assertNotNull(validateRequest(BuildRequest(idea, size = "huge")))
        assertNotNull(validateRequest(BuildRequest(idea, picture = BuilderPicture("big.png", ByteArray(30_000_001)))))
    }

    @Test fun `the picture is ignored when continuing a clip`() {
        val r = BuildRequest("", parts = 2, continueFrom = "abc123", picture = pic)
        assertFalse(r.usesPicture)
        assertEquals("image/png", pic.contentType)
    }

    @Test fun `the render button says which mode it is in`() {
        assertEquals("Text-to-video: Render", renderButtonLabel(BuildRequest(idea), false))
        assertEquals("Text-to-video: Render 20 clips", renderButtonLabel(BuildRequest(idea, parts = 20), false))
        assertEquals("Render", renderButtonLabel(BuildRequest(idea, picture = pic), false))
        assertEquals("Render 3 clips", renderButtonLabel(BuildRequest(idea, picture = pic, parts = 3), false))
        assertEquals("Continue: Render 2 clips", renderButtonLabel(BuildRequest(idea, parts = 2, continueFrom = "abc123"), false))
        assertEquals("Sending…", renderButtonLabel(BuildRequest(idea), true))
    }

    // ---- reading the hub's replies (shapes captured from the live hub) ----

    private val statusJson = """{"online":true,"billing":true,"detail":null,"busy":1,"distilled":true,
        "decoders":{"quality":true,"fast":true},"frames":[49,73,97,121,193,241],"sizes":["landscape","portrait","square"],
        "transformers":["ltx2.5-Stubelius_remix_beta1.safetensors"],"clips":["gemma4-12b.safetensors"],
        "loras":["CGS23.safetensors","Defined_Muscle.safetensors","LTX2-i2v-SexThrust.safetensors","penis-lora-by-coachbate-ltx-2.3.safetensors"],
        "writers":[["x-ai/grok-4.5","Grok 4.5 uncensored"],["x-ai/grok-4.7","Grok 4.7"]],
        "triggers":{"cumsplash_LTX2_v1.safetensors":"cumsplash"},"installs":{}}"""

    @Test fun `status is read from the live shape`() {
        val s = parseBuilderStatus(Json.parseToJsonElement(statusJson))!!
        assertTrue(s.online); assertTrue(s.billing); assertEquals("1", s.busy); assertTrue(s.distilled)
        assertEquals(4, s.loras.size); assertEquals("Grok 4.7", s.writers[1].second); assertEquals(true, s.decoders["fast"])
        assertEquals("cumsplash", s.triggers["cumsplash_LTX2_v1.safetensors"])
        assertNull(parseBuilderStatus(Json.parseToJsonElement("[1,2]")))
        assertNull(parseBuilderStatus(Json.parseToJsonElement("""{"online":true,"busy":0}"""))!!.busy, "busy 0 means idle")
    }

    @Test fun `defaults pick the anatomy LoRA then a motion LoRA and clamp to what exists`() {
        val s = parseBuilderStatus(Json.parseToJsonElement(statusJson))!!
        val d = defaultOpts(s)
        assertEquals(listOf("penis-lora-by-coachbate-ltx-2.3.safetensors" to 0.65, "LTX2-i2v-SexThrust.safetensors" to 0.45), d.loras)
        assertEquals("x-ai/grok-4.5", d.writer); assertEquals(1.0, d.distill)
        val gone = reconcileOpts(d.copy(loras = d.loras + ("Removed.safetensors" to 0.5), transformer = "missing.safetensors"), s)
        assertEquals(2, gone.loras.size); assertNull(gone.transformer)
        assertEquals(0.35, startStrength("some_camera_orbit")); assertEquals(0.45, startStrength("X-Thrust")); assertEquals(0.65, startStrength(null))
    }

    private val jobsJson = """[
      {"id":"1fc9ca3b01a7","kind":"chain","status":"rendering","step":"part 19 of 20","name":"DURATION: 10 seconds\r\nSTART STATE: Person A lies","frames":241,"size":"landscape","seed":224991995062706,"t2v":false,"out":"ltx_chain_x_1fc9ca3b01a7.mp4","error":null,"from_job":null,"idea":"","lines":[],
       "parts":[{"done":true},{"done":true},{"done":false}],"opts":{"transformer":null,"loras":[["a.safetensors",0.65]],"distill":1,"vae":"fast","clip":null,"writer":"x-ai/grok-4.5"},"created":1790000000.5},
      {"id":"b2","kind":null,"status":"done","name":"one clip","frames":49,"size":"square","seed":7,"t2v":true,"out":"ltx_one_b2_49f.mp4","prompt":"he smiles"},
      {"id":"../evil","status":"done","name":"bad id"},
      "not an object"]"""

    @Test fun `jobs are read, progress is counted and bad entries are dropped`() {
        val jobs = parseBuilderJobs(Json.parseToJsonElement(jobsJson))!!
        assertNull(parseBuilderJobs(Json.parseToJsonElement("""{"detail":"nope"}""")), "an object is not a job list")
        assertEquals(listOf("1fc9ca3b01a7", "b2"), jobs.map { it.id })
        val chain = jobs[0]
        assertTrue(chain.live); assertFalse(chain.done); assertTrue(chain.isChain)
        assertEquals(3, chain.partsTotal); assertEquals(2, chain.partsDone); assertEquals("part 19 of 20", chain.step)
        assertEquals("Person A lies", chain.shortName, "a director plan's bookkeeping line must not be the title")
        assertEquals(224991995062706L, chain.seed); assertEquals("fast", chain.opts!!.vae)
        val one = jobs[1]
        assertTrue(one.done); assertTrue(one.textToVideo); assertEquals("he smiles", one.reusePrompt)
    }

    @Test fun `job names are readable, including director plans the hub cut off at 60 characters`() {
        fun named(n: String) = parseBuilderJobs(Json.parseToJsonElement("""[{"id":"x1","status":"done","name":${Json.encodeToString(kotlinx.serialization.serializer<String>(), n)}}]"""))!![0].shortName
        assertEquals("Person A lies on black ma…", named("DURATION: 10 seconds\r\nSTART STATE: Person A lies on black ma"))
        assertEquals("two men kiss on a sofa", named("two men kiss on a sofa"))
        assertEquals("Untitled", named("   "))
        assertEquals("they sit side by side", named("GLOBAL CONTINUITY: sofa\nSTART STATE: they sit side by side\nACTION: x"))
        assertEquals("DURATION: 10 seconds", named("DURATION: 10 seconds"), "bookkeeping with nothing better stays as it is")
        assertEquals(241, longerFrames(193)); assertEquals(241, longerFrames(241)); assertEquals(73, longerFrames(49))
    }

    @Test fun `installs are read from the status and sent as json`() = runBlocking {
        val s = parseBuilderStatus(Json.parseToJsonElement("""{"online":true,"installs":{"ltx_x.safetensors":{"name":"ltx_x.safetensors","kind":"lora","state":"downloading","size_mb":412,"warn":null,"detail":"starting","trigger":"zap"}}}"""))!!
        assertEquals(listOf(InstallItem("ltx_x.safetensors", "lora", "downloading", 412, null, "starting", "zap")), s.installs)
        var body = ""; var path = ""
        val engine = MockEngine { req -> path = req.url.encodedPath; body = String(req.body.toByteArray()); respond("""{"name":"ltx_y.safetensors","kind":"transformer","state":"downloading","size_mb":9000,"warn":"listed for another base"}""", HttpStatusCode.OK, json) }
        val r = (client(engine).install("https://civitai.com/models/1?modelVersionId=2", "transformer") as MediaResult.Ok).value
        assertEquals("/api/ltx/install", path); assertTrue("\"kind\":\"transformer\"" in body && "modelVersionId=2" in body, body)
        assertEquals("listed for another base", r.warn)
        assertTrue(client(MockEngine { respond("{}", HttpStatusCode.OK, json) }).install("  ", "lora") is MediaResult.Failure, "an empty link never reaches the network")
    }

    @Test fun `hub error details are extracted from both fastapi shapes`() {
        assertEquals("The plan has 3 clips and this run is set to 20.", hubDetail("""{"detail":"The plan has 3 clips and this run is set to 20."}"""))
        assertEquals("field required; bad", hubDetail("""{"detail":[{"msg":"field required"},{"msg":"bad"}]}"""))
        assertNull(hubDetail("<html>502</html>")); assertNull(hubDetail("")); assertNull(hubDetail(null))
    }

    // ---- the saved draft ----

    @Test fun `a draft survives a round trip and a damaged one falls back safely`() {
        val d = BuilderDraft("hello there world", 193, 4, "square", "12345", BuilderOpts(vae = "fast"), "C:/pics/a.png")
        assertEquals(d, BuilderDraft.parse(d.toJson()))
        assertEquals(BuilderDraft(), BuilderDraft.parse("{ not json"))
        assertEquals(BuilderDraft(), BuilderDraft.parse(null))
        val odd = BuilderDraft.parse("""{"frames":999,"parts":99,"size":"huge","seed":"12ab34"}""")
        assertEquals(49, odd.frames); assertEquals(1, odd.parts); assertEquals("landscape", odd.size); assertEquals("1234", odd.seed)
    }

    // ---- the client, against a fake hub ----

    @Test fun `a single clip is posted to render as multipart with the picture attached and relay auth`() = runBlocking {
        var path = ""; var method: HttpMethod? = null; var auth: String? = null; var body = ""; var ctype = ""
        val engine = MockEngine { req ->
            path = req.url.encodedPath; method = req.method; auth = req.headers[HttpHeaders.Authorization]
            ctype = req.body.contentType.toString(); body = String(req.body.toByteArray(), Charsets.ISO_8859_1)
            respond("""{"id":"abc123def456","status":"queued"}""", HttpStatusCode.OK, json)
        }
        val r = client(engine).submit(BuildRequest(idea, picture = pic, frames = 97, size = "portrait", seed = 5, opts = BuilderOpts(vae = "fast")))
        assertEquals("abc123def456", (r as MediaResult.Ok).value)
        assertEquals("/api/ltx/render", path); assertEquals(HttpMethod.Post, method)
        assertTrue(auth!!.startsWith("Basic ")); assertTrue(ctype.startsWith("multipart/form-data"), ctype)
        listOf("""name=prompt""", idea, """name=frames""", "97", """name=size""", "portrait", """name=seed""", """name=opts""", """name=image""", """filename="start.PNG"""", "image/png")
            .forEach { assertTrue(it in body, "multipart body should contain <$it>") }
        assertTrue(body.contains("\"vae\":\"fast\""))
    }

    @Test fun `a continuation posts to chain with from_job and no picture`() = runBlocking {
        var path = ""; var body = ""
        val engine = MockEngine { req -> path = req.url.encodedPath; body = String(req.body.toByteArray(), Charsets.ISO_8859_1); respond("""{"id":"zz9"}""", HttpStatusCode.OK, json) }
        val r = client(engine).submit(BuildRequest("", parts = 3, continueFrom = "abc123", picture = pic))
        assertTrue(r is MediaResult.Ok)
        assertEquals("/api/ltx/chain", path)
        assertTrue("name=from_job" in body && "abc123" in body && "name=parts" in body)
        assertFalse("name=image" in body, "no picture is sent when continuing")
        assertFalse("name=size" in body)
    }

    @Test fun `an invalid request never reaches the network`() = runBlocking {
        var calls = 0
        val c = client(MockEngine { calls++; respond("{}", HttpStatusCode.OK, json) })
        val r = c.submit(BuildRequest("short"))
        assertTrue(r is MediaResult.Failure); assertEquals("Describe who is in the video and what happens.", r.error.message)
        assertEquals(0, calls)
    }

    @Test fun `the hub's own explanation is shown for a refused render`() = runBlocking {
        val e = client(MockEngine { respond("""{"detail":"The plan has 3 clips and this run is set to 20."}""", HttpStatusCode.BadRequest, json) })
            .submit(BuildRequest(idea, parts = 20)).let { it as MediaResult.Failure }.error
        assertEquals("The plan has 3 clips and this run is set to 20.", e.message)
        assertEquals(400, e.status)
    }

    @Test fun `a rejected login keeps the sign-in wording instead of the hub text`() = runBlocking {
        val e = client(MockEngine { respond("""{"detail":"nope"}""", HttpStatusCode.Unauthorized, json) })
            .jobs().let { it as MediaResult.Failure }.error
        assertEquals(MediaErrorKind.Unauthorized, e.kind)
    }

    @Test fun `a dropped connection is a failure not an empty list`() = runBlocking {
        val r = client(MockEngine { throw IOException("connection reset") }).jobs()
        assertTrue(r is MediaResult.Failure)
    }

    @Test fun `a reply that is not json is a readable failure`() = runBlocking {
        val r = client(MockEngine { respond("<html>gateway</html>", HttpStatusCode.OK) }).jobs()
        assertTrue(r is MediaResult.Failure)
    }

    @Test fun `assist posts json with the small picture and returns the written prompt`() = runBlocking {
        var path = ""; var body = ""; var ctype = ""
        val engine = MockEngine { req -> path = req.url.encodedPath; ctype = req.body.contentType.toString(); body = String(req.body.toByteArray()); respond("""{"prompt":"A full written prompt","model":"x"}""", HttpStatusCode.OK, json) }
        val r = client(engine).assist(AssistRequest("rough words", 97, 3, "data:image/jpeg;base64,AAAA", null, "x-ai/grok-4.5"))
        assertEquals("A full written prompt", (r as MediaResult.Ok).value)
        assertEquals("/api/ltx/assist", path); assertTrue(ctype.startsWith("application/json"), ctype)
        val o = Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
        assertEquals("rough words", (o["idea"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("data:image/jpeg;base64,AAAA", (o["image"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("3", (o["parts"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun `cancel posts to the job and a malformed id is refused locally`() = runBlocking {
        var path = ""; var method: HttpMethod? = null; var calls = 0
        val c = client(MockEngine { req -> calls++; path = req.url.encodedPath; method = req.method; respond("""{"ok":true}""", HttpStatusCode.OK, json) })
        assertTrue(c.cancel("abc123") is MediaResult.Ok)
        assertEquals("/api/ltx/jobs/abc123/cancel", path); assertEquals(HttpMethod.Post, method)
        assertTrue(c.cancel("../../etc") is MediaResult.Failure); assertEquals(1, calls)
    }
}
