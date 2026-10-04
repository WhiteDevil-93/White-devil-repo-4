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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoraTrainTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun client(engine: MockEngine) = LoraTrainClient("https://hub.example", "anon3", "pw", engine)
    private val dsJson = """{"id":"abcdefabcdef","name":"Test Man","lora":"Test_Man","kind":"character","trigger":"ohwx_man",
        "items":[{"file":"a_1.jpg","type":"image","size":2048,"caption":"the man sits","caption_by":"x-ai/grok-4.5"},
                 {"file":"b_2.mp4","type":"video","size":4096,"caption":"","caption_error":"refused"}],
        "ready":false,"problems":["2 items; needs at least 20"],"warnings":["consent"],
        "estimate":{"minutes":95,"steps":2000,"seconds_per_step":1.2,"label":"ESTIMATE","cost_units":14.1,"units_per_hr":8.9},
        "captioning":{"status":"running","done":1,"total":2},"settings":{"steps":1500},
        "kind_info":{"recommended":"25-40 items"}}"""

    @Test fun `dataset parses with items, checks, estimate and settings`() {
        val d = parseLoraDataset(Json.parseToJsonElement(dsJson))!!
        assertEquals(2, d.items.size); assertEquals(1, d.images); assertEquals(1, d.videos); assertEquals(1, d.captioned)
        assertEquals("refused", d.items[1].error)
        assertEquals(14.1, d.estimate?.costUnits); assertEquals(1500, d.steps); assertNull(d.rank)
        assertEquals("running", d.captioning); assertEquals("25-40 items", d.recommended)
        assertTrue(d.problems.single().startsWith("2 items"))
    }

    @Test fun `run parses progress and verified pulls`() {
        val r = parseLoraRun(Json.parseToJsonElement("""{"id":"111111111111","dataset":"abcdefabcdef","name":"T","status":"training",
            "step":"training 2000 steps","step_now":250,"step_total":2000,"pulled":[{"local":"/x/01.safetensors","size":10,"verified":true}],
            "final":null,"log":"TRAIN_STAGE: training"}"""))!!
        assertTrue(r.active); assertEquals(250, r.stepNow); assertTrue(r.pulled.single().verified); assertNull(r.final)
    }

    @Test fun `the confirmation names the cost in compute units and the render pause`() {
        val c = trainConsequences(parseLoraDataset(Json.parseToJsonElement(dsJson))!!)
        assertTrue(c.any { "14.1 Colab compute units" in it }, c.toString())
        assertTrue(c.any { "renders are refused" in it }); assertTrue(c.any { "42 GB" in it }); assertTrue(c.any { "ESTIMATE" in it })
        assertTrue(c.none { "$" in it }, "Colab bills compute units, not dollars")
    }

    @Test fun `client uses the right verbs and paths`() = runBlocking {
        val seen = mutableListOf<String>()
        val engine = MockEngine { req ->
            seen += "${req.method.value} ${req.url.encodedPath}${req.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""}"
            when {
                req.url.encodedPath.endsWith("/files") -> {
                    val body = String(req.body.toByteArray())
                    assertTrue("filename=\"clip.mov\"" in body && "MOVDATA" in body, body.take(300))
                    respond("""{"added":1,"skipped":[]}""", HttpStatusCode.OK, json)
                }
                req.url.encodedPath.endsWith("/runs") -> respond("[]", HttpStatusCode.OK, json)
                req.url.encodedPath.endsWith("/cancel") || req.url.encodedPath.endsWith("/caption") -> respond("{}", HttpStatusCode.OK, json)
                req.method == HttpMethod.Delete && req.url.encodedPath.endsWith("abcdefabcdef") -> respond("""{"deleted":"abcdefabcdef"}""", HttpStatusCode.OK, json)
                else -> respond(dsJson, HttpStatusCode.OK, json)
            }
        }
        val c = client(engine)
        val f = File.createTempFile("clip", ".mov").apply { writeText("MOVDATA"); deleteOnExit() }
        val named = File(f.parentFile, "clip.mov").apply { f.copyTo(this, overwrite = true); deleteOnExit() }
        assertEquals(1, (c.upload("abcdefabcdef", named) as MediaResult.Ok).value)
        assertTrue(c.saveCaptions("abcdefabcdef", mapOf("a_1.jpg" to "x")) is MediaResult.Ok)
        assertTrue(c.settings("abcdefabcdef", 1500, 64, null) is MediaResult.Ok)
        assertTrue(c.deleteItem("abcdefabcdef", "a_1.jpg") is MediaResult.Ok)
        assertTrue(c.autoCaption("abcdefabcdef") is MediaResult.Ok)
        assertTrue(c.deleteDataset("abcdefabcdef") is MediaResult.Ok)
        assertTrue(c.cancel("222222222222") is MediaResult.Ok)
        assertEquals(listOf(
            "POST /api/loratrain/datasets/abcdefabcdef/files",
            "PUT /api/loratrain/datasets/abcdefabcdef/captions",
            "PATCH /api/loratrain/datasets/abcdefabcdef",
            "DELETE /api/loratrain/datasets/abcdefabcdef/files/a_1.jpg",
            "POST /api/loratrain/datasets/abcdefabcdef/caption?redo=false",
            "DELETE /api/loratrain/datasets/abcdefabcdef",
            "POST /api/loratrain/runs/222222222222/cancel",
        ), seen)
        assertTrue(c.dataset("../etc") is MediaResult.Failure, "ids are checked before any request")
        assertEquals(7, seen.size)
    }

    @Test fun `a refused upload reports the hub's reason`() = runBlocking {
        val c = client(MockEngine { respond("""{"added":0,"skipped":["junk.bin: not an image or video ffmpeg can read"]}""", HttpStatusCode.OK, json) })
        val f = File.createTempFile("junk", ".bin").apply { writeText("x"); deleteOnExit() }
        val r = c.upload("abcdefabcdef", f)
        assertTrue(r is MediaResult.Failure && "ffmpeg can read" in r.error.message, r.toString())
    }

    @Test fun `hub errors come through in its own words`() = runBlocking {
        val c = client(MockEngine { respond("""{"detail":"A LoRA is already training; wait for it or cancel it."}""", HttpStatusCode.Conflict, json) })
        val r = c.train("abcdefabcdef")
        assertTrue(r is MediaResult.Failure && "already training" in r.error.message, r.toString())
    }
}
