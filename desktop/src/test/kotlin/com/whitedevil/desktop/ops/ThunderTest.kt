package com.whitedevil.desktop.ops

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThunderTest {

    private val stateJson = """
    {
      "instances": [
        {"id": "0", "name": "wan14b", "status": "RUNNING", "ip": "203.0.113.9", "port": 31022,
         "numGpus": 1, "gpuType": "l40", "cpuCores": 8, "storage": 200, "template": "comfy-ui",
         "createdAt": "2026-09-29T10:00:00Z", "httpPorts": [8188, 7860], "memory": 48, "future": true},
        {"id": "1", "status": "STARTING"}
      ],
      "snapshots": [{"id": "s1", "name": "base-2026", "status": "READY", "createdAt": "2026-09-01T00:00:00Z", "minimumDiskSizeGb": 100}],
      "pricing": {"pricing": {"l40_x1": 0.69, "a100xl_x1": 1.6, "disk_gb": 0.0008}},
      "specs": {"specs": {"l40_x1": {"displayName": "NVIDIA L40", "vramGB": 48, "vcpuOptions": [4, 8, 16]}}},
      "templates": [{"name": "base", "displayName": "Base"}, "ollama"],
      "status": {"specs": {"l40_x1": "available", "h100_x1": "unavailable"}}
    }
    """

    @Test
    fun `parses a Thunder state shaped like the hub output, tolerating unknown and missing fields`() {
        val s = ThunderState.parse(parseJsonOrNull(stateJson)!!).okValue()
        assertEquals(2, s.instances.size)
        val i = s.instances[0]
        assertEquals("wan14b", i.name)
        assertTrue(i.running)
        assertEquals("ssh -p 31022 ubuntu@203.0.113.9", i.sshCommand)
        assertEquals(listOf("8188", "7860"), i.httpPorts)
        assertEquals(200, i.storageGb)
        val bare = s.instances[1]
        assertNull(bare.name)
        assertNull(bare.ip)
        assertNull(bare.sshCommand)
        assertFalse(bare.running)
        assertEquals("base-2026", s.snapshots.single().name)
        assertEquals(0.69, s.ratePerHour("L40", 1))
        assertNull(s.ratePerHour("l40", 2), "no l40_x2 and no bare l40 key: the rate is unknown, not guessed")
        val bareKey = ThunderState.parse(parseJsonOrNull("""{"instances": [], "pricing": {"pricing": {"t4": 0.3}}}""")!!).okValue()
        assertEquals(0.6, bareKey.ratePerHour("t4", 2)!!, 1e-9)
        assertEquals(listOf("l40"), s.gpuTypes())
        assertEquals("L40", s.gpuLabel("l40"))
        assertEquals(listOf(ThunderTemplate("base", "Base"), ThunderTemplate("ollama", "ollama")), s.templates)
        assertEquals("unavailable", s.stock?.get("h100_x1"))
    }

    @Test
    fun `absent pricing and specs are null (unknown), and the web fallbacks apply`() {
        val s = ThunderState.parse(parseJsonOrNull("""{"instances": [], "snapshots": [], "pricing": null, "specs": null, "templates": null, "status": null}""")!!).okValue()
        assertNull(s.pricing)
        assertNull(s.specs)
        assertNull(s.templates)
        assertNull(s.stock)
        assertNull(s.ratePerHour("l40", 1))
        assertEquals(listOf("t4", "a100xl", "h100"), s.gpuTypes())
        assertTrue(s.instances.isEmpty())
    }

    @Test
    fun `a state without an instance list is an error, not zero instances`() {
        assertEquals(OpsErrorKind.BadShape, ThunderState.parse(parseJsonOrNull("""{"snapshots": []}""")!!).errValue().kind)
        assertEquals(OpsErrorKind.BadShape, ThunderState.parse(parseJsonOrNull("[]")!!).errValue().kind)
        assertEquals(OpsErrorKind.BadShape, ThunderState.parse(parseJsonOrNull("""{"instances": "none"}""")!!).errValue().kind)
    }

    @Test
    fun `queue parses runner, comfy and jobs`() {
        val q = ThunderQueue.parse(
            parseJsonOrNull(
                """{"runner": true, "comfy": {"online": false, "why": "ComfyUI did not answer within 5s"},
                    "jobs": [{"id": "abc", "name": "n", "chain_id": "c", "status": "rendering", "progress": {"done": 2, "total": 9}, "current": 3, "avg_seconds": 300, "created": "2026-09-29T10:00:00", "error": null},
                             {"id": "def", "status": "error", "error": "boom"}, 7]}""",
            )!!,
        ).okValue()
        assertEquals(true, q.runnerUp)
        assertEquals(false, q.comfy?.online)
        assertTrue(q.comfy!!.why!!.contains("5s"))
        assertEquals(2, q.jobs.size)
        assertEquals(2, q.jobs[0].done)
        assertEquals(9, q.jobs[0].total)
        assertTrue(q.jobs[0].canCancel)
        assertTrue(q.jobs[1].canRetry)
        assertFalse(q.jobs[1].canCancel)
    }

    @Test
    fun `runner false with no jobs is kept as runner false - the caller must not read it as an empty queue`() {
        val q = ThunderQueue.parse(parseJsonOrNull("""{"runner": false, "comfy": {"online": false}, "jobs": []}""")!!).okValue()
        assertEquals(false, q.runnerUp)
        assertTrue(q.jobs.isEmpty())
        assertEquals(OpsErrorKind.BadShape, ThunderQueue.parse(parseJsonOrNull("{}")!!).errValue().kind)
    }

    @Test
    fun `the Thunder read client only ever sends GET`() = runTest {
        val hub = FakeHub({ req -> if (req.url.encodedPath.endsWith("state")) jsonReply(stateJson) else jsonReply("""{"runner": true, "jobs": []}""") })
        val api = ThunderApi(hub.reader)
        api.state().okValue()
        api.queue().okValue()
        assertTrue(hub.methods.all { it == HttpMethod.Get }, hub.methods.toString())
        assertEquals(listOf("/api/thunder/state", "/api/thunder/queue"), hub.paths)
    }

    @Test
    fun `Thunder read errors keep the status and reason`() = runTest {
        for ((body, code) in listOf("" to 401, """{"detail":"Thunder Compute isn't answering right now."}""" to 503, "oops" to 500)) {
            val err = ThunderApi(FakeHub(body, HttpStatusCode.fromValue(code)).reader).state().errValue()
            assertEquals(code, err.status)
        }
        assertEquals(OpsErrorKind.BadJson, ThunderApi(FakeHub("not json").reader).queue().errValue().kind)
    }

    // ---- submit14: HTTP 200 with ok:false --------------------------------------------------------

    private fun chain(): ChainSpec = ChainSpec.fromFiles(listOf("""{"type": "chain", "chain_id": "demo", "clips": [{"index": 1, "prompt": "a"}, {"index": 2, "prompt": "b"}]}""")).okValue()

    private fun submit(reply: String, status: HttpStatusCode = HttpStatusCode.OK): ActionOutcome = runBlocking {
        ThunderActions(FakeHub(reply, status).actor).submit(chain(), "my job", null, nextUp = false)
    }

    @Test
    fun `submit with ok true is a success`() {
        val out = assertIs<ActionOutcome.Succeeded>(submit("""{"ok": true, "id": "job1", "clips": 2, "requeued": 0}"""))
        assertTrue(out.headline.contains("job1"))
        assertTrue(out.headline.contains("2 clips"))
    }

    @Test
    fun `submit answering 200 with ok false is a FAILURE that lists which part failed`() {
        val out = assertIs<ActionOutcome.Failed>(
            submit(
                """{"ok": false, "id": "job1", "clips": 2, "requeued": 1,
                    "warning": "Your job was queued, but these jobs it jumped ahead of could not be put back and are still cancelled: old7: runner offline"}""",
            ),
        )
        assertEquals(200, out.status)
        assertTrue(out.headline.contains("ok=false"), out.headline)
        val accepted = out.parts.first { it.label.contains("accepted") }
        assertTrue(accepted.ok, "the job itself did reach the runner")
        assertTrue(accepted.detail!!.contains("job1"))
        val requeue = out.parts.first { it.label.contains("put back") }
        assertFalse(requeue.ok)
        assertTrue(requeue.detail!!.contains("old7"), "must name the job that is still cancelled")
        assertTrue(out.rawBody!!.contains("old7"))
    }

    @Test
    fun `submit with no ok field is not a success, even at HTTP 200`() {
        val out = assertIs<ActionOutcome.Failed>(submit("""{"id": "job1", "clips": 2}"""))
        assertTrue(out.headline.contains("no ok flag"))
        assertTrue(out.mayHaveExecuted)
    }

    @Test
    fun `submit with ok false and nothing else is a failure`() {
        val out = assertIs<ActionOutcome.Failed>(submit("""{"ok": false}"""))
        assertFalse(out.parts.first().ok)
    }

    @Test
    fun `submit with a non-JSON 200 is a failure with unknown outcome`() {
        val out = assertIs<ActionOutcome.Failed>(submit("<html>login</html>"))
        assertTrue(out.mayHaveExecuted)
    }

    @Test
    fun `submit with an HTTP error surfaces the hub's error body`() {
        val out = assertIs<ActionOutcome.Failed>(submit("""{"detail":"The 14B runner on the relay isn't running."}""", HttpStatusCode.ServiceUnavailable))
        assertEquals(503, out.status)
        assertTrue(out.headline.contains("14B runner"))
    }

    @Test
    fun `submit body carries the spec, the name, the seed and the queue position`() = runTest {
        val hub = FakeHub("""{"ok": true, "id": "j", "clips": 2}""")
        ThunderActions(hub.actor).submit(chain(), " my job ", 42, nextUp = true)
        val sent = (hub.engine.requestHistory.single().body as TextContent).text
        val body = opsJson.parseToJsonElement(sent).jsonObject
        assertEquals("my job", body["name"]!!.jsonPrimitive.content)
        assertEquals(42, body["seed"]!!.jsonPrimitive.int)
        assertEquals("true", body["first"]!!.jsonPrimitive.content)
        assertEquals(2, body["spec"]!!.jsonObject["clips"]!!.jsonArray.size)
        assertEquals(listOf("/api/thunder/queue"), hub.paths)
        assertEquals(listOf(HttpMethod.Post), hub.methods)
    }

    @Test
    fun `an ok-false-style reply to a job cancel or a snapshot delete is also a failure`() = runBlocking {
        val cancel = ThunderActions(FakeHub("""{"ok": false}""").actor).jobAction("abc", "cancel")
        assertIs<ActionOutcome.Failed>(cancel)
        val del = ThunderActions(FakeHub("""{"success": false, "error": "not found"}""").actor).deleteSnapshot("s1")
        val failed = assertIs<ActionOutcome.Failed>(del)
        assertTrue(failed.parts.first().detail!!.contains("not found"))
    }

    @Test
    fun `a pass-through reply with no success flag is 'accepted', never 'done'`() = runBlocking {
        val out = assertIs<ActionOutcome.Succeeded>(ThunderActions(FakeHub("""{"identifier": "9", "message": "creating"}""").actor).createInstance("l40", 1, 8, "base", 100))
        assertTrue(out.headline.contains("accepted"), out.headline)
        assertFalse(out.headline.contains("done", ignoreCase = true))
    }

    @Test
    fun `bad ids are refused locally and nothing is sent`() = runBlocking {
        val hub = FakeHub("""{"ok": true}""")
        val a = ThunderActions(hub.actor)
        assertIs<ActionOutcome.Failed>(a.deleteInstance("../etc"))
        assertIs<ActionOutcome.Failed>(a.deleteInstance("a/b"))
        assertIs<ActionOutcome.Failed>(a.jobAction("x y", "cancel"))
        assertIs<ActionOutcome.Failed>(a.jobAction("abc", "delete"))
        assertIs<ActionOutcome.Failed>(a.createSnapshot("bad id", "n"))
        assertIs<ActionOutcome.Failed>(a.deleteSnapshot(""))
        assertTrue(hub.engine.requestHistory.isEmpty())
    }

    @Test
    fun `chain files merge like the web screen - sorted, deduplicated, typed chain`() {
        val a = """{"type": "chain_part", "chain_id": "demo-part01", "clips": [{"index": 2}, {"index": 1}], "part": 1, "settings": {"fps": 16}}"""
        val b = """{"type": "chain_part", "chain_id": "demo-part02", "clips": [{"index": 2}, {"index": 3}], "part": 2}"""
        val c = ChainSpec.fromFiles(listOf(a, b)).okValue()
        assertEquals(3, c.clipCount)
        assertEquals(listOf(1, 2, 3), c.spec["clips"]!!.jsonArray.map { it.jsonObject["index"]!!.jsonPrimitive.int })
        assertEquals("chain", c.spec["type"]!!.jsonPrimitive.content)
        assertEquals("demo-14b", c.spec["chain_id"]!!.jsonPrimitive.content)
        assertFalse("part" in c.spec)
        assertEquals(3, c.spec["settings"]!!.jsonObject["clip_count"]!!.jsonPrimitive.int)
        assertEquals(16, c.spec["settings"]!!.jsonObject["fps"]!!.jsonPrimitive.int)
    }

    @Test
    fun `chain files the hub would refuse are refused before sending`() {
        assertEquals(OpsErrorKind.BadShape, ChainSpec.fromFiles(emptyList()).errValue().kind)
        assertEquals(OpsErrorKind.BadShape, ChainSpec.fromFiles(listOf("not json")).errValue().kind)
        assertEquals(OpsErrorKind.BadShape, ChainSpec.fromFiles(listOf("""{"type": "chain", "clips": []}""")).errValue().kind)
        assertEquals(OpsErrorKind.BadShape, ChainSpec.fromFiles(listOf("""{"type": "other", "clips": [{"index": 1}]}""")).errValue().kind)
        assertEquals(OpsErrorKind.BadShape, ChainSpec.fromFiles(listOf("[1, 2]")).errValue().kind)
    }

    @Test
    fun `submit body helper is the only place the spec is wrapped`() {
        val b: JsonObject = ThunderActions.submitBody(chain(), "", null, nextUp = false)
        assertEquals(JsonPrimitive(false), b["first"])
        assertEquals("null", b["name"].toString(), "blank name is sent as null so the hub keeps the spec's own")
    }
}
