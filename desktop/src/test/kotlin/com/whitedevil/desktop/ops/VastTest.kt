package com.whitedevil.desktop.ops

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VastTest {

    private val stateJson = """
    {"credit": 12.34, "image": "pytorch/pytorch:2.7.0-cuda12.8-cudnn9-runtime",
     "instances": [
       {"id": "31415926", "label": "forge", "gpu": "RTX 5090", "num_gpus": 1, "vram_gb": 32, "status": "running",
        "intended": "running", "status_msg": "", "price": 0.412, "stopped_price": 0.02, "disk_gb": 150,
        "location": "Sweden", "image": "x", "host": "203.0.113.5", "port": 41022, "started": 1790000000.0,
        "cuda": 12.8, "gpu_util": 3.5, "disk_used_gb": 22.1, "novel": [1]},
       {"id": "27182818", "status": "exited", "intended": "stopped"},
       {"no_id": true}
     ]}
    """

    @Test
    fun `parses Vast state shaped like the hub output`() {
        val s = VastState.parse(parseJsonOrNull(stateJson)!!).okValue()
        assertEquals(12.34, s.credit)
        assertEquals(2, s.instances.size, "an instance without an id is dropped like the hub drops it")
        val i = s.instances[0]
        assertTrue(i.running)
        assertEquals(0.412, i.pricePerHour)
        assertEquals(0.02, i.stoppedPricePerHour)
        assertEquals("ssh -p 41022 root@203.0.113.5", i.sshCommand)
        val stopped = s.instances[1]
        assertTrue(stopped.stopped)
        assertNull(stopped.pricePerHour)
        assertNull(stopped.gpu)
    }

    @Test
    fun `missing credit is null, and a reply without an instance list is an error`() {
        assertNull(VastState.parse(parseJsonOrNull("""{"instances": []}""")!!).okValue().credit)
        assertEquals(OpsErrorKind.BadShape, VastState.parse(parseJsonOrNull("""{"credit": 3}""")!!).errValue().kind)
        assertEquals(OpsErrorKind.BadShape, VastState.parse(parseJsonOrNull("[]")!!).errValue().kind)
    }

    @Test
    fun `offers parse and keep the query they answered`() {
        val q = OfferQuery(diskGb = 200)
        val o = VastOffers.parse(
            parseJsonOrNull(
                """[{"id": "111", "gpu": "RTX 4090", "num_gpus": 1, "vram_gb": 24, "price": 0.35, "stopped_price": 0.03,
                     "disk_max_gb": 500, "location": "DE", "reliability": 99.1, "down_mbps": 900, "cpu_cores": 16,
                     "ram_gb": 64, "cuda": 12.9, "rentable": true}, {"id": 222}, {"nope": 1}, 3]""",
            )!!,
            q,
        ).okValue()
        assertEquals(200, o.query.diskGb)
        assertEquals(2, o.offers.size)
        assertEquals(0.35, o.offers[0].pricePerHour)
        assertEquals(true, o.offers[0].rentable)
        assertNull(o.offers[1].pricePerHour)
        assertNull(o.offers[1].rentable)
        assertEquals(OpsErrorKind.BadShape, VastOffers.parse(parseJsonOrNull("""{"offers": []}""")!!, q).errValue().kind)
    }

    @Test
    fun `offer query path uses the hub's parameter names and encodes the gpu name`() {
        assertEquals("/api/vast/offers?min_vram=40.0&disk_gb=150&num_gpus=1", OfferQuery().path())
        assertEquals(
            "/api/vast/offers?min_vram=24.0&gpu=RTX+4090&max_price=0.5&disk_gb=100&num_gpus=2",
            OfferQuery(24.0, "RTX 4090", 0.5, 100, 2).path(),
        )
        assertFalse(OfferQuery(gpu = "  ").path().contains("gpu="))
    }

    @Test
    fun `the Vast read client only ever sends GET, including the offers search`() = runTest {
        val hub = FakeHub({ req -> if (req.url.encodedPath.endsWith("state")) jsonReply(stateJson) else jsonReply("[]") })
        val api = VastApi(hub.reader)
        api.state().okValue()
        api.offers(OfferQuery()).okValue()
        assertTrue(hub.methods.all { it == HttpMethod.Get }, hub.methods.toString())
    }

    @Test
    fun `Vast read errors keep status and reason`() = runTest {
        val err = VastApi(FakeHub("""{"detail":"Vast rejected the API key; make a new one under Account > Keys."}""", HttpStatusCode.Unauthorized).reader).state().errValue()
        assertEquals(401, err.status)
        assertTrue(err.message.contains("Vast rejected"))
        assertEquals(OpsErrorKind.BadJson, VastApi(FakeHub("<html>").reader).offers(OfferQuery()).errValue().kind)
    }

    // ---- rent / start / stop / delete ------------------------------------------------------------

    @Test
    fun `rent succeeds only with an instance id`() = runBlocking<Unit> {
        val ok = VastActions(FakeHub("""{"id": "9001"}""").actor).rent("555", 150, "forge")
        val s = assertIs<ActionOutcome.Succeeded>(ok)
        assertTrue(s.headline.contains("9001"))
        assertTrue(s.headline.contains("bills from now"))

        val noId = VastActions(FakeHub("""{"success": true}""").actor).rent("555", 150, null)
        val f = assertIs<ActionOutcome.Failed>(noId)
        assertTrue(f.headline.contains("no instance id"))
        assertTrue(f.mayHaveExecuted)
        assertIs<ActionOutcome.Failed>(VastActions(FakeHub("[]").actor).rent("555", 150, null))
    }

    @Test
    fun `rent that the hub refuses shows the hub's message`() = runBlocking {
        val out = assertIs<ActionOutcome.Failed>(
            VastActions(FakeHub("""{"detail":"Vast didn't accept the rental; the offer may be gone."}""", HttpStatusCode.Conflict).actor).rent("555", 150, null),
        )
        assertEquals(409, out.status)
        assertTrue(out.headline.contains("offer may be gone"))
    }

    @Test
    fun `rent body carries the offer, the disk the price was quoted for, and the label`() = runTest {
        val hub = FakeHub("""{"id": "1"}""")
        VastActions(hub.actor).rent("555", 175, " forge ")
        val body = opsJson.parseToJsonElement((hub.engine.requestHistory.single().body as TextContent).text).jsonObject
        assertEquals("555", body["offer_id"]!!.jsonPrimitive.content)
        assertEquals(175, body["disk_gb"]!!.jsonPrimitive.int)
        assertEquals("forge", body["label"]!!.jsonPrimitive.content)
        assertEquals(listOf("/api/vast/instances"), hub.paths)
        assertEquals(listOf(HttpMethod.Post), hub.methods)
    }

    @Test
    fun `start stop delete answering 200 with success false are failures`() = runBlocking {
        for (verb in listOf<suspend (VastActions) -> ActionOutcome>({ it.start("42") }, { it.stop("42") }, { it.delete("42") })) {
            val out = assertIs<ActionOutcome.Failed>(
                verb(VastActions(FakeHub("""{"success": false, "error": "invalid_args", "msg": "instance is not stoppable"}""").actor)),
            )
            assertEquals(200, out.status)
            assertTrue(out.parts.single().detail!!.contains("invalid_args"))
        }
    }

    @Test
    fun `start stop delete with success true are reported as the hub reporting success`() = runBlocking {
        val out = assertIs<ActionOutcome.Succeeded>(VastActions(FakeHub("""{"success": true}""").actor).stop("42"))
        assertTrue(out.headline.contains("reports success"))
    }

    @Test
    fun `start stop delete hit exactly their routes with POST`() = runTest {
        val hub = FakeHub("""{"success": true}""")
        val a = VastActions(hub.actor)
        a.start("1"); a.stop("2"); a.delete("3")
        assertEquals(listOf("/api/vast/instances/1/start", "/api/vast/instances/2/stop", "/api/vast/instances/3/delete"), hub.paths)
        assertTrue(hub.methods.all { it == HttpMethod.Post })
    }

    @Test
    fun `non-numeric ids are refused locally and nothing is sent`() = runBlocking {
        val hub = FakeHub("""{"success": true}""")
        val a = VastActions(hub.actor)
        assertIs<ActionOutcome.Failed>(a.start("../1"))
        assertIs<ActionOutcome.Failed>(a.stop("12a"))
        assertIs<ActionOutcome.Failed>(a.delete(""))
        assertIs<ActionOutcome.Failed>(a.rent("x", 150, null))
        assertIs<ActionOutcome.Failed>(a.start("1234567890123"))
        assertTrue(hub.engine.requestHistory.isEmpty())
    }
}
