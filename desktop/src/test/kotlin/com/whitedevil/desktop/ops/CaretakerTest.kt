package com.whitedevil.desktop.ops

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CaretakerTest {

    // Shape copied from a real GET /caretaker/api/state on the relay (2026-10-04).
    private val stateJson = """
    {"facts": {"time": "2026-10-04T01:21:56+0200", "disk_used_pct": 26.8, "mem_available_mb": 22749, "load1": 0.19,
      "forge_hub_service": "active", "forge_hub_http": 200, "apt_upgradable": "0", "reboot_required": false,
      "running_kernel": "7.0.0-1013-oracle", "last_backup_age_h": 3.1,
      "failed_units": "lvm-activate-ocivolume.service loaded failed failed /usr/sbin/lvm vgchange -aay"},
     "anomalies": [], "inflight": ["1 interactive session(s) logged in"], "paused": false, "live": false, "model_loaded": false}
    """

    @Test
    fun `parses the real state shape`() {
        val s = CaretakerState.parse(parseJsonOrNull(stateJson)!!).okValue()
        assertEquals(26.8, s.diskUsedPct)
        assertEquals(22749, s.memAvailableMb)
        assertEquals("200", s.hubHttp, "the hub status arrives as a number when healthy")
        assertTrue(s.hubHealthy)
        assertEquals("0", s.aptUpgradable)
        assertEquals(3.1, s.lastBackupAgeHours)
        assertFalse(s.paused); assertFalse(s.live); assertFalse(s.rebootRequired)
        assertEquals(listOf("1 interactive session(s) logged in"), s.inFlight)
        assertEquals(1, s.failedUnits.size)
        assertTrue(s.anomalies.isEmpty())
    }

    @Test
    fun `a down hub, a text status and no failed units are read correctly`() {
        val down = stateJson.replace("\"forge_hub_http\": 200", "\"forge_hub_http\": \"ERR refused\"").replace("\"forge_hub_service\": \"active\"", "\"forge_hub_service\": \"failed\"")
        val s = CaretakerState.parse(parseJsonOrNull(down)!!).okValue()
        assertFalse(s.hubHealthy)
        assertEquals("ERR refused", s.hubHttp)
        val none = CaretakerState.parse(parseJsonOrNull(stateJson.replace(Regex("\"failed_units\": \"[^\"]*\""), "\"failed_units\": \"none\""))!!).okValue()
        assertTrue(none.failedUnits.isEmpty())
        val noBackup = CaretakerState.parse(parseJsonOrNull(stateJson.replace("\"last_backup_age_h\": 3.1", "\"last_backup_age_h\": null"))!!).okValue()
        assertEquals(null, noBackup.lastBackupAgeHours)
    }

    @Test
    fun `anything that is not the caretaker's state is an error, not an empty screen`() {
        assertEquals(OpsErrorKind.BadShape, CaretakerState.parse(parseJsonOrNull("[1,2]")!!).errValue().kind)
        assertEquals(OpsErrorKind.BadShape, CaretakerState.parse(parseJsonOrNull("""{"anomalies": []}""")!!).errValue().kind)
        assertEquals(OpsErrorKind.BadShape, CaretakerAudit.parse(parseJsonOrNull("""{"x": 1}""")!!).errValue().kind)
    }

    @Test
    fun `the activity log is newest first and tells what happened`() {
        val rows = CaretakerAudit.parse(
            parseJsonOrNull(
                """
                [{"event": "ok", "t": "2026-10-04T00:50:00+0200"},
                 {"event": "dry_run", "action": "reboot", "reason": "reboot required", "t": "2026-10-04T00:56:00+0200"},
                 {"event": "deferred", "action": "apt_upgrade", "why": ["ltx job j rendering", "Colab runtime is running"], "t": "2026-10-04T01:00:00+0200"},
                 {"event": "pause_toggled", "paused": true, "t": "2026-10-04T01:05:00+0200"},
                 {"no_event": true}]
                """,
            )!!,
        ).okValue()
        assertEquals(listOf("pause_toggled", "deferred", "dry_run", "ok"), rows.map { it.event })
        assertEquals("reboot · reboot required", rows[2].detail)
        assertEquals("apt_upgrade · ltx job j rendering; Colab runtime is running", rows[1].detail)
        assertEquals("paused=true", rows[0].detail)
        assertEquals("", rows[3].detail)
    }

    @Test
    fun `reading state and activity only ever sends GET to the caretaker paths`() = runBlocking {
        val hub = FakeHub({ req -> jsonReply(if (req.url.encodedPath.endsWith("/audit")) "[]" else stateJson) })
        val api = CaretakerApi(hub.reader)
        api.state().okValue(); api.audit().okValue()
        assertEquals(listOf(HttpMethod.Get, HttpMethod.Get), hub.methods)
        assertEquals(listOf("/caretaker/api/state", "/caretaker/api/audit"), hub.paths)
    }

    @Test
    fun `pause posts the requested state as JSON and succeeds only if the service agrees`() = runBlocking {
        var sent = ""
        val hub = FakeHub({ req -> sent = (req.body as TextContent).text; jsonReply("""{"paused": true}""") })
        val ok = CaretakerActions(hub.actor).pause(true)
        assertIs<ActionOutcome.Succeeded>(ok)
        assertEquals(HttpMethod.Post, hub.methods.single())
        assertEquals("/caretaker/api/pause", hub.paths.single())
        assertTrue(Json.parseToJsonElement(sent).jsonObject["paused"]!!.jsonPrimitive.boolean)
        // The service says it did NOT change: a 200 alone is not success.
        val disagree = CaretakerActions(FakeHub("""{"paused": false}""").actor).pause(true)
        assertIs<ActionOutcome.Failed>(disagree)
        // A refusal from the relay is a failure, not a success.
        val denied = CaretakerActions(FakeHub("""{"detail": "no"}""", HttpStatusCode.Unauthorized).actor).pause(false)
        assertIs<ActionOutcome.Failed>(denied)
    }

    @Test
    fun `ask sends the message with at most six turns of history and reads the reply`() = runBlocking {
        var sent = ""
        val hub = FakeHub({ req -> sent = (req.body as TextContent).text; jsonReply("""{"reply": "Disk is at 27 percent."}""") })
        val history = (1..9).map { (if (it % 2 == 1) "user" else "assistant") to "turn $it" }
        val out = CaretakerActions(hub.actor).ask("how is the disk?", history).okValue()
        assertEquals("Disk is at 27 percent.", out)
        assertEquals("/caretaker/api/chat", hub.paths.single())
        val body = Json.parseToJsonElement(sent).jsonObject
        assertEquals("how is the disk?", body["message"]!!.jsonPrimitive.content)
        assertEquals(6, body["history"]!!.jsonArray.size)
        assertEquals("turn 4", body["history"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a failed chat shows the caretaker's own error text`() = runBlocking {
        val e = CaretakerActions(FakeHub("""{"error": "OSError: model file missing"}""", HttpStatusCode.InternalServerError).actor)
            .ask("hi", emptyList()).errValue()
        assertTrue(e.message.contains("model file missing"), e.message)
        val empty = CaretakerActions(FakeHub("""{"unexpected": 1}""").actor).ask("hi", emptyList()).errValue()
        assertEquals(OpsErrorKind.BadShape, empty.kind)
    }
}
