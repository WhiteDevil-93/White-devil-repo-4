package com.whitedevil.desktop.ops

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColabTest {

    private fun parse(json: String): OpsResult<ColabState> =
        ColabState.parse(parseJsonOrNull(json) ?: JsonNull)

    @Test
    fun `parses a state shaped like the real hub output, ignoring unknown fields`() {
        val s = parse(COLAB_STATE_JSON).okValue()
        assertEquals(true, s.billing)
        assertEquals(true, s.runnerOnline)
        assertEquals(false, s.comfyOnline)
        assertNull(s.paused)
        assertEquals("Running", s.summary?.label)
        assertEquals("ok", s.summary?.kind)
        assertEquals("G4", s.instance?.accelerator)
        assertEquals("gpu-abc123", s.instance?.endpoint)
        assertEquals(0.77, s.usage?.ratePerHr)
        assertEquals(42.5, s.usage?.balance)
        assertEquals(1, s.usage?.activeAssignments)
        assertTrue(s.usage!!.readable)
        assertEquals(1790000000.5, s.heartbeatAtEpochSec)
        assertEquals(mapOf("state" to "ok", "beat" to "17"), s.heartbeat)
        assertEquals(listOf("restarting tunnel", "runner up"), s.recoverLog)
        assertEquals(listOf("1", "2"), s.resumePacks)
        assertEquals(2, s.jobs.size)
        assertEquals("3/10", s.jobs[0].progress)
        assertEquals(4, s.jobs[0].currentClip)
        assertEquals(312, s.jobs[0].avgSeconds)
        assertEquals("boom", s.jobs[1].error)
        assertEquals("0.0", s.jobs[1].progress)
    }

    @Test
    fun `missing fields become null or empty, never invented values`() {
        val s = parse("""{"status": {"label": "No runtime", "kind": "bad", "billing": false}}""").okValue()
        assertEquals("No runtime", s.summary?.label)
        assertEquals(false, s.billing)
        assertNull(s.usage)
        assertNull(s.instance)
        assertNull(s.runnerOnline)
        assertNull(s.comfyOnline)
        assertTrue(s.jobs.isEmpty())
        assertTrue(s.heartbeat.isEmpty())
    }

    @Test
    fun `usage that the hub could not read is flagged unreadable, not shown as zero`() {
        val s = parse("""{"usage": {"balance": null, "rate_per_hr": null, "active": null, "checked": 0}, "status": {"label": "No runtime"}}""").okValue()
        assertFalse(s.usage!!.readable)
        assertNull(s.usage?.balance)
        assertNull(s.usage?.activeAssignments)
    }

    @Test
    fun `mistyped fields degrade to null instead of failing the panel`() {
        val s = parse("""{"status": {"label": "Running", "billing": "yes"}, "usage": {"balance": "n/a", "active": {"x": 1}}, "jobs": [1, "x", {"id": 7, "progress": "40%"}]}""").okValue()
        assertNull(s.summary?.billing)
        assertNull(s.usage?.balance)
        assertNull(s.usage?.activeAssignments)
        assertEquals(1, s.jobs.size)
        assertEquals("7", s.jobs[0].id)
        assertEquals("40%", s.jobs[0].progress)
    }

    @Test
    fun `a reply with none of the expected fields is an error, not an empty state`() {
        val err = parse("{}").errValue()
        assertEquals(OpsErrorKind.BadShape, err.kind)
        assertTrue(err.message.contains("none of the expected fields"), err.message)
        assertEquals(OpsErrorKind.BadShape, parse("""{"unrelated": 1}""").errValue().kind)
        assertEquals(OpsErrorKind.BadShape, parse("[]").errValue().kind)
        assertEquals(OpsErrorKind.BadShape, parse("null").errValue().kind)
        assertEquals(OpsErrorKind.BadShape, parse("\"ok\"").errValue().kind)
    }

    @Test
    fun `packs parse, and a non-array reply is an error`() {
        val ok = ColabPack.parseList(parseJsonOrNull("""[{"index": 2, "title": "Two", "clips": 5, "rendered": 3, "extra": 1}, {"index": 1, "title": "One", "clips": 4, "rendered": 0}, 5]""")!!).okValue()
        assertEquals(2, ok.size)
        assertEquals(3, ok[0].rendered)
        assertEquals(OpsErrorKind.BadShape, ColabPack.parseList(parseJsonOrNull("""{"index": 1}""")!!).errValue().kind)
    }

    // ---- The read-only client never issues a non-GET ---------------------------------------------

    @Test
    fun `the Colab read client only ever sends GET, on state and on packs`() = runTest {
        val hub = FakeHub({ req ->
            when (req.url.encodedPath) {
                "/api/colab/state" -> jsonReply(COLAB_STATE_JSON)
                "/api/colab/packs" -> jsonReply("[]")
                else -> jsonReply("""{"detail":"unexpected path"}""", HttpStatusCode.NotFound)
            }
        })
        val api = ColabApi(hub.reader)
        api.state().okValue()
        api.packs().okValue()
        api.state().okValue()
        assertEquals(3, hub.methods.size)
        assertTrue(hub.methods.all { it == HttpMethod.Get }, "non-GET sent: ${hub.methods}")
        assertEquals(listOf("/api/colab/state", "/api/colab/packs", "/api/colab/state"), hub.paths)
    }

    @Test
    fun `the read client never touches the mutating or secret-bearing routes`() = runTest {
        val hub = FakeHub({ jsonReply(COLAB_STATE_JSON) })
        val api = ColabApi(hub.reader)
        api.state()
        api.packs()
        val forbidden = listOf("stop-runtime", "recover", "runner-token", "/queue", "/jobs")
        hub.paths.forEach { p -> forbidden.forEach { f -> assertFalse(p.contains(f), "read client hit $p") } }
    }

    @Test
    fun `state errors are Err with the reason - 401, 500, garbled JSON, timeout`() = runTest {
        val cases = listOf(
            FakeHub("", HttpStatusCode.Unauthorized) to 401,
            FakeHub("""{"detail":"runner offline"}""", HttpStatusCode.ServiceUnavailable) to 503,
            FakeHub("Internal Server Error", HttpStatusCode.InternalServerError) to 500,
        )
        for ((hub, code) in cases) {
            val err = ColabApi(hub.reader).state().errValue()
            assertEquals(code, err.status)
            assertTrue(err.message.isNotBlank())
        }
        val garbled = ColabApi(FakeHub("<html>oops</html>").reader).state().errValue()
        assertEquals(OpsErrorKind.BadJson, garbled.kind)
        val timeout = ColabApi(FakeHub({ throw io.ktor.client.plugins.HttpRequestTimeoutException("u", 1) }).reader).state().errValue()
        assertEquals(OpsErrorKind.Timeout, timeout.kind)
    }

    // ---- Stop runtime: 200 is not success unless ok:true and billing has ended -------------------

    private fun stopWith(body: String, status: HttpStatusCode = HttpStatusCode.OK): ActionOutcome = runBlocking {
        ColabActions(FakeHub(body, status).actor).stopRuntime()
    }

    @Test
    fun `stop is Succeeded only for ok true with billing false`() {
        val out = stopWith("""{"ok": true, "billing": false, "output": "stopped"}""")
        assertIs<ActionOutcome.Succeeded>(out)
        assertEquals("stopped", out.detail)
    }

    @Test
    fun `stop that answers 200 with ok false and billing true is a Failed that says you are still billed`() {
        val out = assertIs<ActionOutcome.Failed>(
            stopWith("""{"ok": false, "billing": true, "usage": {"active": 1}, "output": "WARNING: Colab still shows an active assignment"}"""),
        )
        assertTrue(out.headline.contains("still being billed"), out.headline)
        assertEquals(200, out.status)
        assertEquals(listOf(false, false), out.parts.map { it.ok })
        assertTrue(out.rawBody!!.contains("active assignment"))
    }

    @Test
    fun `ok true but billing still true is not a success`() {
        assertIs<ActionOutcome.Failed>(stopWith("""{"ok": true, "billing": true}"""))
    }

    @Test
    fun `stop with no ok field or a non-object body is not a success and may have executed`() {
        val noOk = assertIs<ActionOutcome.Failed>(stopWith("""{"billing": false}"""))
        assertTrue(noOk.mayHaveExecuted)
        val html = assertIs<ActionOutcome.Failed>(stopWith("<html>proxy page</html>"))
        assertTrue(html.mayHaveExecuted)
        assertIs<ActionOutcome.Failed>(stopWith("[]"))
    }

    @Test
    fun `stop with an HTTP error shows the hub's body`() {
        val out = assertIs<ActionOutcome.Failed>(stopWith("""{"detail":"flock timed out"}""", HttpStatusCode.InternalServerError))
        assertEquals(500, out.status)
        assertTrue(out.headline.contains("flock timed out"))
    }

    @Test
    fun `recover reports launched, already running, and failure distinctly`() {
        fun rec(body: String) = runBlocking { ColabActions(FakeHub(body).actor).recover() }
        val launched = assertIs<ActionOutcome.Succeeded>(rec("""{"ok": true}"""))
        assertTrue(launched.headline.contains("not confirmation that a GPU is up"), launched.headline)
        val already = assertIs<ActionOutcome.Succeeded>(rec("""{"ok": true, "already_running": true}"""))
        assertTrue(already.headline.contains("already running"))
        assertIs<ActionOutcome.Failed>(rec("""{"ok": false}"""))
        assertIs<ActionOutcome.Failed>(rec("{}"))
    }

    @Test
    fun `stop and recover are the only Colab POSTs and target exactly their routes`() = runTest {
        val hub = FakeHub("""{"ok": true, "billing": false}""")
        val actions = ColabActions(hub.actor)
        actions.stopRuntime()
        actions.recover()
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Post), hub.methods)
        assertEquals(listOf("/api/colab/stop-runtime", "/api/colab/recover"), hub.paths)
    }
}
