package com.whitedevil.desktop

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VeniceWorkspaceModelTest {
    private fun j(s: String) = Json.parseToJsonElement(s)

    // Shapes as the hub returned them on 2026-10-04 (trimmed).
    private val colabOff = """{"runner_mode":"wanbot","runner_online":false,"comfy_online":false,"paused":"2026-10-03 20:52","billing":false,"instance":{"status":"none","running":false}}"""
    private val thunderNone = """{"instances":[],"snapshots":[],"pricing":{}}"""
    private val thunderQueue = """{"runner":true,"comfy":{"online":false,"why":"refused"},"jobs":[{"id":"a","status":"error"},{"id":"b","status":"queued"},{"id":"c","status":"done"}]}"""
    private val ltxOff = """{"online":false,"billing":false,"detail":"ComfyUI on Colab isn't reachable.","installs":{}}"""
    private val ltxJobs = """[{"id":"d7","kind":"chain","status":"done"},{"id":"e8","status":"running"}]"""
    private val vast = """{"credit":14.25,"instances":[{"id":"54098898","label":"e4b-128k-v3","status":"running","price":0.219},{"id":"9","status":"exited","price":0.5}]}"""

    @Test fun `tiles read the real hub answers`() {
        assertEquals(TileState("Colab", "PAUSED", "since 2026-10-03 20:52", Tone.Quiet), Sitrep.colab(j(colabOff)))
        assertEquals("RUNNING", Sitrep.colab(j("""{"runner_online":true,"comfy_online":true,"billing":true}""")).value)
        assertEquals(Tone.Bad, Sitrep.colab(j("""{"runner_online":false,"comfy_online":false,"billing":true}""")).tone, "billing while stopped is a problem")
        assertEquals("OFF", Sitrep.colab(j("""{"runner_online":false,"comfy_online":false,"billing":false}""")).value)

        val t = Sitrep.thunder(j(thunderNone), j(thunderQueue))
        assertEquals("NO BOX", t.value); assertEquals("1 in queue", t.sub); assertEquals(Tone.Warn, t.tone, "work waiting with no box to run it")
        assertEquals("RUNNING", Sitrep.thunder(j("""{"instances":[{"status":"running"}]}"""), j("""{"comfy":{"online":true},"jobs":[]}""")).value)

        val l = Sitrep.ltx(j(ltxOff), j(ltxJobs))
        assertEquals("01", l.value); assertTrue("offline" in l.sub && "1 job waiting" in l.sub, l.sub)

        val v = Sitrep.vast(j(vast))
        assertEquals("$14.25", v.value); assertEquals("1 running · $0.22/h", v.sub); assertEquals(Tone.Warn, v.tone, "a running box costs money")
        assertEquals("no boxes running", Sitrep.vast(j("""{"credit":3,"instances":[]}""")).sub)
    }

    @Test fun `a tile the hub did not answer says so instead of guessing`() {
        listOf(Sitrep.colab(null), Sitrep.thunder(null, null), Sitrep.ltx(null, null), Sitrep.vast(j("[]"))).forEach {
            assertEquals("—", it.value); assertEquals(Tone.Bad, it.tone); assertEquals("hub did not answer", it.sub)
        }
    }

    @Test fun `the client asks the six endpoints and survives one failing`() = runBlocking {
        val seen = mutableListOf<String>()
        val engine = MockEngine { req ->
            val p = req.url.encodedPath; synchronized(seen) { seen += p }
            val body = mapOf("/api/colab/state" to colabOff, "/api/thunder/state" to thunderNone, "/api/thunder/queue" to thunderQueue, "/api/ltx/status" to ltxOff, "/api/ltx/jobs" to ltxJobs)[p]
            if (body == null) respond("boom", HttpStatusCode.InternalServerError) else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val s = SitrepClient("https://hub.example", "u", "p", engine).use { it.load(nowMs = 42) }
        assertEquals(setOf("/api/colab/state", "/api/thunder/state", "/api/thunder/queue", "/api/ltx/status", "/api/ltx/jobs", "/api/vast/state"), seen.toSet())
        assertEquals(listOf("PAUSED", "NO BOX", "01", "—"), s.tiles.map { it.value }, "vast failed on its own"); assertEquals(42, s.fetchedAtMs)
    }

    private val chat = listOf(
        ChatLine(ROLE_USER, "You", "old question"),
        ChatLine(ROLE_VENICE, "Venice", "old answer"),
        ChatLine(ROLE_USER, "You", "check   the\nrenders"),
        ChatLine(ROLE_TOOL_CALL, "Tool · hub_request", """{"method":"GET","path":"/api/ltx/jobs"}"""),
        ChatLine(ROLE_TOOL_OUT, "Output · hub_request", "[]"),
        ChatLine(ROLE_TOOL_CALL, "Tool · run_laptop_command", """{"command":"dir A:\\renders"}"""),
        ChatLine(ROLE_TOOL_OUT, "Output · run_laptop_command", "Error: access denied"),
        ChatLine(ROLE_TOOL_CALL, "Tool · review_latest_render", "{}"),
    )

    @Test fun `the goal is the last request and the tools run for it`() {
        val g = currentGoal(chat, busy = true)!!
        assertEquals("check the renders", g.text)
        assertEquals(listOf("hub_request GET /api/ltx/jobs", "run_laptop_command dir A:\\renders", "review_latest_render"), g.steps.map { it.text })
        assertEquals(listOf(StepState.Done, StepState.Failed, StepState.Running), g.steps.map { it.state })
        assertEquals(StepState.Done, currentGoal(chat, busy = false)!!.steps.last().state, "nothing spins once the agent stopped")
        assertNull(currentGoal(listOf(ChatLine(ROLE_VENICE, "Venice", "hi")), false))
        assertTrue(currentGoal(chat.take(3), false)!!.steps.isEmpty())
    }

    @Test fun `the terminal shows each command and the start of its output`() {
        val t = terminalLines(chat + ChatLine(ROLE_TOOL_OUT, "Output · review_latest_render", (1..9).joinToString("\n") { "line $it" }), outLines = 4)
        assertEquals(TermLine("$ hub_request GET /api/ltx/jobs", TermKind.Cmd), t.first())
        assertTrue(TermLine("Error: access denied", TermKind.Err) in t)
        assertEquals("… 5 more lines", t.last().text)
        assertTrue(terminalLines(listOf(ChatLine(ROLE_USER, "You", "hi"))).isEmpty())
        assertEquals(400, terminalLines((1..500).map { ChatLine(ROLE_TOOL_CALL, "Tool · x", "{}") }).size, "bounded")
    }

    @Test fun `token meter`() {
        assertEquals(250, estimateTokens(1000))
        assertEquals("Tokens: ~6.8k/32.8k", tokenLabel(6800, 32768))
        assertEquals("Tokens: ~950", tokenLabel(950, null))
        assertEquals("Tokens: ~2k/128k", tokenLabel(2000, 128000))
    }
}
