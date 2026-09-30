package com.whitedevil.desktop.ops

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * LTX (hub/ltx.py). The fixtures are shaped like what the routes build: `status()` at ltx.py:129,
 * the job dicts written by render (ltx.py:961), chain (ltx.py:923) and sharpen (ltx.py:654), and
 * `cycle_status()` at ltx.py:1274 over the history entries ltx_qa_cycle.py writes.
 */
class LtxTest {

    private val statusOnline = """
        {"online": true, "billing": true, "detail": null,
         "transformers": ["ltx-2.5-Stubelius-distilled.safetensors"],
         "loras": ["ltx_a.safetensors", "ltx_b.safetensors"],
         "distilled": true, "decoders": {"quality": true, "fast": false},
         "clips": ["gemma4-12b.safetensors"], "writers": [["x-ai/grok-4.5", "Grok 4.5"]],
         "installs": {"ltx_a.safetensors": {"name": "ltx_a.safetensors", "kind": "lora", "state": "done", "size_mb": 150.5, "detail": "on Colab"},
                      "bad.safetensors": {"kind": "lora", "state": "failed", "detail": "bad link"}, "junk": 5},
         "triggers": {"ltx_a.safetensors": "BEANFLK"}, "busy": 1, "frames": [49, 73], "sizes": ["landscape"], "a_future_field": [1]}
    """

    private val statusOffline = """
        {"online": false, "billing": true,
         "detail": "Colab G4 is BILLING (${'$'}0.77/h) but ComfyUI tunnel is down. Stop the runtime on the Colab screen to stop charges.",
         "installs": {}}
    """

    private val jobsJson = """
        [
          {"id": "aaaaaaaaaaaa", "created": 1790000300.5, "prompt_id": "p1", "status": "rendering", "prompt": "a long prompt",
           "frames": 97, "size": "landscape", "seed": 1, "name": "clip one", "t2v": false, "out": "ltx_clip_one.mp4", "step": null},
          {"id": "bbbbbbbbbbbb", "kind": "chain", "created": 1790000200.0, "status": "queued", "idea": "", "lines": ["one", "two", "three"],
           "parts": [{}, {}, {}], "frames": 97, "size": "portrait", "seed": 9, "name": "a chain", "t2v": false, "step": "part 2 of 3"},
          {"id": "cccccccccccc", "kind": "sharpen", "source": "aaaaaaaaaaaa", "created": 1790000100.0, "status": "done", "seconds": 312,
           "name": "2×: clip one", "parts": [], "frames": 97, "size": "landscape", "step": null},
          {"id": "dddddddddddd", "kind": "chain", "created": 1790000000.0, "status": "failed", "error": "Cancelled", "lines": ["x", "y"],
           "parts": [{}, {}], "frames": 49, "size": "square", "name": "text chain", "t2v": true},
          "not an object", 7
        ]
    """

    private val cycleRunning = """
        {"running": true, "pid": 4242, "src": "bbbbbbbbbbbb", "kept": ["aaaaaaaaaaaa"], "history_len": 3,
         "last": {"round": 2, "stack": 1, "jid": "eeeeeeeeeeee", "out": "x.mp4", "opts": {"i2v": 0.45}, "report": "Motion jitters in the last third. RETRY",
                  "verdict": "RETRY", "adjust_note": "lowered i2v"},
         "log_tail": "2026-09-30T01:00:00 wait eeeeeeeeeeee rendering 1/3 part 2 of 3\n"}
    """

    private fun status(json: String) = LtxStatus.parse(parseJsonOrNull(json) ?: JsonNull)
    private fun jobs(json: String) = LtxJob.parseList(parseJsonOrNull(json) ?: JsonNull)
    private fun cycle(json: String) = LtxCycle.parse(parseJsonOrNull(json) ?: JsonNull)

    // ---- status ---------------------------------------------------------------------------------

    @Test
    fun `status parses the online shape, skipping junk installs`() {
        val s = status(statusOnline).okValue()
        assertEquals(true, s.online)
        assertEquals(true, s.billing)
        assertNull(s.detail)
        assertEquals(1, s.queueBusy)
        assertEquals(listOf("ltx-2.5-Stubelius-distilled.safetensors"), s.transformers)
        assertEquals(2, s.loras?.size)
        assertEquals(true, s.distilled)
        assertEquals(true, s.decoderQuality)
        assertEquals(false, s.decoderFast)
        assertEquals(listOf("ltx_a.safetensors", "bad.safetensors"), s.installs.map { it.name })
        assertEquals("done", s.installs[0].state)
        assertEquals(150.5, s.installs[0].sizeMb)
        assertEquals("failed", s.installs[1].state)
    }

    @Test
    fun `status with ComfyUI unreachable leaves the lists unknown, not empty`() {
        val s = status(statusOffline).okValue()
        assertEquals(false, s.online)
        assertEquals(true, s.billing)
        assertTrue(s.detail!!.contains("BILLING"))
        assertNull(s.transformers)
        assertNull(s.loras)
        assertNull(s.queueBusy)
        assertNull(s.distilled)
        assertNull(s.decoderQuality)
    }

    @Test
    fun `ComfyUI up with no LTX model gives an empty list, which is a real answer`() {
        val s = status("""{"online": false, "billing": true, "detail": "ComfyUI is up but has no LTX-2.5 model; install the LTX kit from Setup.", "transformers": [], "loras": [], "busy": 0}""").okValue()
        assertEquals(emptyList(), s.transformers)
        assertEquals(0, s.queueBusy)
        assertEquals(false, s.online)
    }

    @Test
    fun `a status without an online field, or not an object, is an error`() {
        assertEquals(OpsErrorKind.BadShape, status("{}").errValue().kind)
        assertEquals(OpsErrorKind.BadShape, status("""{"unrelated": 1}""").errValue().kind)
        assertEquals(OpsErrorKind.BadShape, status("[]").errValue().kind)
        assertEquals(OpsErrorKind.BadShape, status("null").errValue().kind)
        assertTrue(status("""{"unrelated": 1}""").errValue().message.contains("no `online` field"))
    }

    @Test
    fun `mistyped status fields degrade to unknown instead of failing the panel`() {
        val s = status("""{"online": "yes", "billing": 1, "busy": "many", "transformers": "x", "decoders": []}""").okValue()
        assertNull(s.online)
        assertNull(s.billing)
        assertNull(s.queueBusy)
        assertNull(s.transformers)
        assertNull(s.decoderQuality)
    }

    // ---- jobs -----------------------------------------------------------------------------------

    @Test
    fun `jobs parse the three kinds and skip entries that are not objects`() {
        val list = jobs(jobsJson).okValue()
        assertEquals(4, list.size)
        val clip = list[0]
        assertEquals("aaaaaaaaaaaa", clip.id)
        assertNull(clip.kind)
        assertEquals("clip", clip.kindLabel)
        assertEquals("rendering", clip.status)
        assertTrue(clip.active)
        assertEquals(97, clip.frames)
        assertEquals(1790000300.5, clip.createdEpochSec)
        val chain = list[1]
        assertEquals("chain", chain.kindLabel)
        assertEquals(3, chain.partCount)
        assertEquals(3, chain.lineCount)
        assertEquals("part 2 of 3", chain.step)
        assertTrue(chain.active)
        val sharpen = list[2]
        assertEquals("2× sharpen", sharpen.kindLabel)
        assertFalse(sharpen.active)
        assertEquals(312, sharpen.seconds)
        val failed = list[3]
        assertEquals("Cancelled", failed.error)
        assertEquals(true, failed.textToVideo)
    }

    @Test
    fun `an empty jobs list is Ok and empty, while a non-array reply is an error`() {
        assertEquals(emptyList(), jobs("[]").okValue())
        assertEquals(OpsErrorKind.BadShape, jobs("""{"jobs": []}""").errValue().kind)
        assertEquals(OpsErrorKind.BadShape, jobs("null").errValue().kind)
    }

    @Test
    fun `mistyped job fields degrade to null`() {
        val j = jobs("""[{"id": 7, "status": ["x"], "frames": "lots", "created": "yesterday", "parts": 3, "lines": {"a": 1}}]""").okValue().single()
        assertEquals("7", j.id)
        assertNull(j.status)
        assertNull(j.frames)
        assertNull(j.createdEpochSec)
        assertNull(j.partCount)
        assertNull(j.lineCount)
        assertFalse(j.active)
        assertFalse(j.hasValidId)
    }

    @Test
    fun `job ids are the hub's twelve lowercase hex digits and nothing else`() {
        assertTrue(LtxJob.isValidId("0123456789ab"))
        listOf("", "0123456789a", "0123456789abc", "0123456789AB", "../etc/passwd", "0123456789a/", "0123456789a\n", "0123456789 b", "ghijklmnopqr")
            .forEach { assertFalse(LtxJob.isValidId(it), "accepted <$it>") }
    }

    @Test
    fun `only a chain made from a picture with lines can be a cycle source`() {
        val list = jobs(jobsJson).okValue()
        assertFalse(list[0].usableAsCycleSource, "a plain clip has no lines")
        assertTrue(list[1].usableAsCycleSource)
        assertFalse(list[2].usableAsCycleSource, "a sharpen job is not a chain")
        assertFalse(list[3].usableAsCycleSource, "a text-to-video chain has no start picture")
        // A chain with no lines (the hub stores them only when there were several) cannot be used either.
        val noLines = jobs("""[{"id": "bbbbbbbbbbbb", "kind": "chain", "frames": 97, "size": "landscape", "lines": [], "t2v": false}]""").okValue().single()
        assertFalse(noLines.usableAsCycleSource)
        val badId = jobs("""[{"id": "../x", "kind": "chain", "frames": 97, "size": "landscape", "lines": ["a"], "t2v": false}]""").okValue().single()
        assertFalse(badId.usableAsCycleSource)
        val noFrames = jobs("""[{"id": "bbbbbbbbbbbb", "kind": "chain", "size": "landscape", "lines": ["a"], "t2v": false}]""").okValue().single()
        assertFalse(noFrames.usableAsCycleSource)
    }

    @Test
    fun `the cycle cost is rounds times two recipes times the source's parts`() {
        val source = jobs(jobsJson).okValue()[1]
        val cost = LtxCycleCost.of(3, source)
        assertEquals(6, cost.chains)
        assertEquals(3, cost.clipsPerChain)
        assertEquals(18, cost.clips)
        assertEquals(97, cost.frames)
        assertEquals("portrait", cost.size)
        assertEquals(2, LtxCycleCost.of(1, source).chains)
    }

    // ---- cycle status ---------------------------------------------------------------------------

    @Test
    fun `cycle status parses a running cycle with its last result`() {
        val c = cycle(cycleRunning).okValue()
        assertTrue(c.running)
        assertEquals(4242, c.pid)
        assertEquals("bbbbbbbbbbbb", c.src)
        assertEquals(listOf("aaaaaaaaaaaa"), c.kept)
        assertEquals(3, c.historyLen)
        assertEquals(2, c.last?.round)
        assertEquals(1, c.last?.stack)
        assertEquals("eeeeeeeeeeee", c.last?.jobId)
        assertEquals("RETRY", c.last?.verdict)
        assertEquals("lowered i2v", c.last?.adjustNote)
        assertTrue(c.logTail.contains("part 2 of 3"))
    }

    @Test
    fun `an idle cycle with no state file parses as not running with nothing recorded`() {
        val c = cycle("""{"running": false, "pid": null, "src": null, "kept": [], "history_len": 0, "last": null, "log_tail": ""}""").okValue()
        assertFalse(c.running)
        assertNull(c.pid)
        assertNull(c.src)
        assertTrue(c.kept.isEmpty())
        assertNull(c.last)
        assertEquals("", c.logTail)
    }

    @Test
    fun `a failed render in the history shows its status and error`() {
        val c = cycle("""{"running": false, "last": {"round": 1, "stack": 0, "jid": "ffffffffffff", "status": "render_fail", "error": "ComfyUI gone"}}""").okValue()
        assertEquals("render_fail", c.last?.status)
        assertEquals("ComfyUI gone", c.last?.error)
        assertNull(c.last?.verdict)
    }

    @Test
    fun `a cycle reply without a boolean running field is an error, never assumed idle`() {
        assertEquals(OpsErrorKind.BadShape, cycle("{}").errValue().kind)
        assertEquals(OpsErrorKind.BadShape, cycle("""{"running": "yes"}""").errValue().kind)
        assertEquals(OpsErrorKind.BadShape, cycle("""{"pid": 1}""").errValue().kind)
        assertEquals(OpsErrorKind.BadShape, cycle("[]").errValue().kind)
    }

    // ---- the read client only ever reads --------------------------------------------------------

    @Test
    fun `the LTX read client only sends GET, on exactly its three routes`() = runTest {
        val hub = FakeHub({ req ->
            when (req.url.encodedPath) {
                "/api/ltx/status" -> jsonReply(statusOnline)
                "/api/ltx/jobs" -> jsonReply(jobsJson)
                "/api/ltx/cycle/status" -> jsonReply(cycleRunning)
                else -> jsonReply("""{"detail":"unexpected path"}""", HttpStatusCode.NotFound)
            }
        })
        val api = LtxApi(hub.reader)
        api.status().okValue()
        api.jobs().okValue()
        api.cycle().okValue()
        assertEquals(listOf("/api/ltx/status", "/api/ltx/jobs", "/api/ltx/cycle/status"), hub.paths)
        assertTrue(hub.methods.all { it == HttpMethod.Get }, "non-GET sent: ${hub.methods}")
    }

    @Test
    fun `the read client never touches a mutating route or either file route`() = runTest {
        val hub = FakeHub({ jsonReply(statusOnline) })
        val api = LtxApi(hub.reader)
        api.status()
        api.jobs()
        api.cycle()
        val forbidden = listOf("/input", "/stage", "/render", "/chain", "/sharpen", "/install", "/assist", "/cancel", "/start", "/stop")
        hub.paths.forEach { p -> forbidden.forEach { f -> assertFalse(p.contains(f), "read client hit $p") } }
    }

    @Test
    fun `read errors are Err with the reason - 401, 503, 500, garbled JSON, timeout`() = runTest {
        val cases = listOf(
            FakeHub("", HttpStatusCode.Unauthorized) to 401,
            FakeHub("""{"detail":"ComfyUI on Colab isn't reachable."}""", HttpStatusCode.ServiceUnavailable) to 503,
            FakeHub("Internal Server Error", HttpStatusCode.InternalServerError) to 500,
        )
        for ((hub, code) in cases) {
            val api = LtxApi(hub.reader)
            for (err in listOf(api.status().errValue(), api.jobs().errValue(), api.cycle().errValue())) {
                assertEquals(code, err.status)
                assertTrue(err.message.isNotBlank())
            }
        }
        val garbled = LtxApi(FakeHub("<html>oops</html>").reader)
        assertEquals(OpsErrorKind.BadJson, garbled.status().errValue().kind)
        assertEquals(OpsErrorKind.BadJson, garbled.jobs().errValue().kind)
        assertEquals(OpsErrorKind.BadJson, garbled.cycle().errValue().kind)
        val timeout = LtxApi(FakeHub({ throw io.ktor.client.plugins.HttpRequestTimeoutException("u", 1) }).reader)
        assertEquals(OpsErrorKind.Timeout, timeout.jobs().errValue().kind)
    }

    // ---- what the actions send ------------------------------------------------------------------

    private fun sent(hub: FakeHub) = hub.engine.requestHistory.single()

    @Test
    fun `start sends one POST with the rounds and the source, and the relay sign-in`() = runTest {
        val hub = FakeHub("""{"ok": true, "pid": 4242, "src": "bbbbbbbbbbbb", "rounds": 3, "message": "started"}""")
        LtxActions(hub.actor).startCycle(3, "bbbbbbbbbbbb")
        val req = sent(hub)
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("/api/ltx/cycle/start", req.url.encodedPath)
        val body = opsJson.parseToJsonElement((req.body as TextContent).text).jsonObject
        assertEquals(3, body["rounds"]!!.jsonPrimitive.int)
        assertEquals("bbbbbbbbbbbb", body["src"]!!.jsonPrimitive.content)
        assertEquals(setOf("rounds", "src"), body.keys)
        assertTrue(req.headers[HttpHeaders.Authorization]!!.startsWith("Basic "))
    }

    @Test
    fun `start refuses an out-of-range round count or a source that is not a job id, without sending anything`() = runTest {
        val hub = FakeHub("""{"ok": true}""")
        val actions = LtxActions(hub.actor)
        assertFailsWith<IllegalArgumentException> { actions.startCycle(0, "bbbbbbbbbbbb") }
        assertFailsWith<IllegalArgumentException> { actions.startCycle(11, "bbbbbbbbbbbb") }
        assertFailsWith<IllegalArgumentException> { actions.startCycle(2, "../secret") }
        assertFailsWith<IllegalArgumentException> { actions.startCycle(2, "") }
        assertTrue(hub.engine.requestHistory.isEmpty())
    }

    @Test
    fun `stop and cancel each send one POST to their own route`() = runTest {
        val stop = FakeHub("""{"ok": true, "stopped": [4242]}""")
        LtxActions(stop.actor).stopCycle()
        assertEquals(HttpMethod.Post, sent(stop).method)
        assertEquals("/api/ltx/cycle/stop", sent(stop).url.encodedPath)

        val cancel = FakeHub("""{"ok": true}""")
        LtxActions(cancel.actor).cancelJob("aaaaaaaaaaaa")
        assertEquals(HttpMethod.Post, sent(cancel).method)
        assertEquals("/api/ltx/jobs/aaaaaaaaaaaa/cancel", sent(cancel).url.encodedPath)
    }

    @Test
    fun `cancel refuses an id that could climb out of the jobs path`() = runTest {
        val hub = FakeHub("""{"ok": true}""")
        val actions = LtxActions(hub.actor)
        listOf("../cycle/stop", "aaaaaaaaaaaa/../..", "AAAAAAAAAAAA", "").forEach { id ->
            assertFailsWith<IllegalArgumentException>(id) { actions.cancelJob(id) }
        }
        assertTrue(hub.engine.requestHistory.isEmpty())
    }

    // ---- HTTP 200 is not success ----------------------------------------------------------------

    private fun start(body: String, status: HttpStatusCode = HttpStatusCode.OK): ActionOutcome = runBlocking {
        LtxActions(FakeHub(body, status).actor).startCycle(2, "bbbbbbbbbbbb")
    }

    private fun stop(body: String, status: HttpStatusCode = HttpStatusCode.OK): ActionOutcome = runBlocking {
        LtxActions(FakeHub(body, status).actor).stopCycle()
    }

    private fun cancel(body: String, status: HttpStatusCode = HttpStatusCode.OK): ActionOutcome = runBlocking {
        LtxActions(FakeHub(body, status).actor).cancelJob("aaaaaaaaaaaa")
    }

    @Test
    fun `start is Succeeded only for ok true, and says a render has not been confirmed`() {
        val out = assertIs<ActionOutcome.Succeeded>(start("""{"ok": true, "pid": 4242, "message": "Render→assess→adjust→repeat started"}"""))
        assertTrue(out.headline.contains("pid 4242"), out.headline)
        assertTrue(out.headline.contains("not confirmation that a render has begun"), out.headline)
        assertEquals("Render→assess→adjust→repeat started", out.detail)
    }

    @Test
    fun `start that answers 200 without ok true is not a success`() {
        val no = assertIs<ActionOutcome.Failed>(start("""{"ok": false}"""))
        assertFalse(no.mayHaveExecuted)
        assertTrue(no.headline.contains("ok=false"))
        val missing = assertIs<ActionOutcome.Failed>(start("""{"pid": 4242}"""))
        assertTrue(missing.mayHaveExecuted)
        val html = assertIs<ActionOutcome.Failed>(start("<html>proxy page</html>"))
        assertTrue(html.mayHaveExecuted)
        assertIs<ActionOutcome.Failed>(start("[]"))
    }

    @Test
    fun `start with an HTTP error shows the hub's own words`() {
        val unknown = assertIs<ActionOutcome.Failed>(start("""{"detail":"Unknown LTX job 'bbbbbbbbbbbb'"}""", HttpStatusCode.NotFound))
        assertEquals(404, unknown.status)
        assertTrue(unknown.headline.contains("Unknown LTX job"), unknown.headline)
        val missing = assertIs<ActionOutcome.Failed>(start("""{"detail":"ltx_qa_cycle.py missing on the relay"}""", HttpStatusCode.InternalServerError))
        assertTrue(missing.headline.contains("ltx_qa_cycle.py missing"), missing.headline)
        assertNull(unknown.detail, "a definite 404 did not run anything")
    }

    @Test
    fun `stop names the pid, and says a submitted chain is not cancelled`() {
        val out = assertIs<ActionOutcome.Succeeded>(stop("""{"ok": true, "stopped": [4242]}"""))
        assertTrue(out.headline.contains("pid 4242"), out.headline)
        assertTrue(out.headline.contains("NOT cancelled"), out.headline)
        val none = assertIs<ActionOutcome.Succeeded>(stop("""{"ok": true, "stopped": []}"""))
        assertTrue(none.headline.contains("no cycle process"), none.headline)
        assertFalse(none.headline.contains("NOT cancelled"))
    }

    @Test
    fun `stop without ok true is a failure, and an unreadable reply may have executed`() {
        assertIs<ActionOutcome.Failed>(stop("""{"ok": false}"""))
        assertTrue(assertIs<ActionOutcome.Failed>(stop("""{"stopped": [1]}""")).mayHaveExecuted)
        assertTrue(assertIs<ActionOutcome.Failed>(stop("oops")).mayHaveExecuted)
        assertIs<ActionOutcome.Failed>(stop("""{"detail":"boom"}""", HttpStatusCode.InternalServerError))
    }

    @Test
    fun `cancel is Succeeded only for ok true, and a 409 or an unreachable ComfyUI is shown as the failure it is`() {
        assertIs<ActionOutcome.Succeeded>(cancel("""{"ok": true}"""))
        assertIs<ActionOutcome.Failed>(cancel("""{"ok": false}"""))
        assertTrue(assertIs<ActionOutcome.Failed>(cancel("{}")).mayHaveExecuted)
        val notRendering = assertIs<ActionOutcome.Failed>(cancel("""{"detail":"That clip isn't rendering."}""", HttpStatusCode.Conflict))
        assertEquals(409, notRendering.status)
        assertTrue(notRendering.headline.contains("isn't rendering"), notRendering.headline)
        val comfy = assertIs<ActionOutcome.Failed>(cancel("""{"detail":"ComfyUI on Colab isn't reachable. Start the Colab runtime, then give the tunnel a minute."}""", HttpStatusCode.ServiceUnavailable))
        assertTrue(comfy.headline.contains("isn't reachable"), comfy.headline)
    }

    @Test
    fun `a timeout on an action is marked as possibly executed`() = runBlocking<Unit> {
        val hub = FakeHub({ throw io.ktor.client.plugins.HttpRequestTimeoutException("u", 1) })
        val out = assertIs<ActionOutcome.Failed>(LtxActions(hub.actor).cancelJob("aaaaaaaaaaaa"))
        assertTrue(out.mayHaveExecuted)
    }
}
