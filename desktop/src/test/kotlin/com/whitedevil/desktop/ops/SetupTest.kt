package com.whitedevil.desktop.ops

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupTest {

    /** Before the first save: hub/setup.py merge() with saved=False (setup.py:86-92). */
    private val beforeFirstSave = """
    {"saved": false, "count": 3, "enabled": 0, "lightricks": 2, "nsfw": 1, "target": "ltx-2.5",
     "dest": "~/civitai_dl/ltx-2.5/",
     "loras": [
       {"id": "distilled", "name": "Distilled 450", "kind": "speed", "pack": "lightricks", "filename": "d.safetensors", "enabled": false},
       {"id": "water", "name": "Water", "kind": "ic", "pack": "lightricks", "filename": "w.safetensors", "enabled": false},
       {"id": "mylo", "name": "mylo", "kind": "nsfw", "pack": "nsfw", "filename": "m.safetensors", "enabled": false}
     ]}
    """

    private val afterSave = """
    {"saved": true, "count": 3, "enabled": 2, "lightricks": 2, "nsfw": 1, "target": "ltx-2.5", "dest": "~/civitai_dl/ltx-2.5/",
     "loras": [
       {"id": "distilled", "name": "Distilled 450", "pack": "lightricks", "enabled": true},
       {"id": "water", "name": "Water", "pack": "lightricks", "enabled": false},
       {"id": "mylo", "name": "mylo", "pack": "nsfw", "enabled": true}
     ]}
    """

    @Test
    fun `enabled=0 before the first save is 'not yet saved', and the values that are present are kept`() {
        val s = SetupState.parse(parseJsonOrNull(beforeFirstSave)!!).okValue()
        assertEquals(SetupPhase.NotYetSaved, s.phase)
        assertEquals(3, s.count)
        assertEquals(2, s.lightricks)
        assertEquals(1, s.nsfw)
        assertEquals(0, s.enabledCount)
        assertEquals("~/civitai_dl/ltx-2.5/", s.dest)
        assertEquals(3, s.loras.size, "the catalog is real even though nothing is saved")
        assertEquals("nsfw", s.loras[2].pack)
    }

    @Test
    fun `an older hub without the saved flag - enabled=0 is still 'not yet saved', not 'unconfigured'`() {
        val s = SetupState.parse(parseJsonOrNull("""{"count": 20, "enabled": 0, "lightricks": 11, "nsfw": 9}""")!!).okValue()
        assertEquals(SetupPhase.NotYetSaved, s.phase)
        assertEquals(20, s.count)
        assertEquals(11, s.lightricks)
        assertEquals(9, s.nsfw)
        assertTrue(s.loras.isEmpty())
    }

    @Test
    fun `saved with some LoRAs on is Saved with the counts`() {
        val s = SetupState.parse(parseJsonOrNull(afterSave)!!).okValue()
        assertEquals(SetupPhase.Saved(enabled = 2, of = 3), s.phase)
        assertEquals(setOf("distilled", "mylo"), s.enabledIds)
    }

    @Test
    fun `a nonzero enabled count without the flag counts as saved`() {
        assertEquals(SetupPhase.Saved(5, 20), SetupState.parse(parseJsonOrNull("""{"count": 20, "enabled": 5}""")!!).okValue().phase)
    }

    @Test
    fun `the saved flag wins over the count when both are present`() {
        assertEquals(SetupPhase.NotYetSaved, SetupState.parse(parseJsonOrNull("""{"saved": false, "count": 20, "enabled": 4}""")!!).okValue().phase)
    }

    @Test
    fun `missing and mistyped fields degrade to null, and unusable replies are errors`() {
        val s = SetupState.parse(parseJsonOrNull("""{"saved": true, "count": "many", "loras": [{"id": "a"}, {"name": "no id"}, 3]}""")!!).okValue()
        assertNull(s.count)
        assertEquals(1, s.loras.size)
        assertEquals(false, s.loras[0].enabled)
        assertEquals(OpsErrorKind.BadShape, SetupState.parse(parseJsonOrNull("{}")!!).errValue().kind)
        assertEquals(OpsErrorKind.BadShape, SetupState.parse(parseJsonOrNull("[]")!!).errValue().kind)
    }

    @Test
    fun `the Setup read client only sends GET and reports errors with their reason`() = runTest {
        val hub = FakeHub(beforeFirstSave)
        SetupApi(hub.reader).state().okValue()
        assertEquals(listOf(HttpMethod.Get), hub.methods)
        assertEquals(listOf("/api/setup"), hub.paths)
        val err = SetupApi(FakeHub("""{"detail":"Setup catalog missing: x"}""", HttpStatusCode.InternalServerError).reader).state().errValue()
        assertEquals(500, err.status)
        assertTrue(err.message.contains("Setup catalog missing"))
        assertEquals(OpsErrorKind.BadJson, SetupApi(FakeHub("nope").reader).state().errValue().kind)
    }

    // ---- save is a write; it is confirmed by the hub's own reply --------------------------------

    @Test
    fun `a save the hub confirms exactly is a success and returns the new state`() = runBlocking {
        val (out, state) = SetupActions(FakeHub(afterSave).actor).save(setOf("distilled", "mylo"))
        val ok = assertIs<ActionOutcome.Succeeded>(out)
        assertTrue(ok.headline.contains("2 of 3"), ok.headline)
        assertEquals(SetupPhase.Saved(2, 3), state!!.phase)
    }

    @Test
    fun `a 200 that does not report saved is not a success`() = runBlocking {
        val (out, _) = SetupActions(FakeHub(beforeFirstSave).actor).save(setOf("distilled"))
        val f = assertIs<ActionOutcome.Failed>(out)
        assertTrue(f.parts.any { !it.ok })
    }

    @Test
    fun `when the hub kept different ids than requested the mismatch is reported`() = runBlocking {
        val (out, state) = SetupActions(FakeHub(afterSave).actor).save(setOf("distilled", "water", "mylo"))
        val f = assertIs<ActionOutcome.Failed>(out)
        val kept = f.parts.first { it.label.contains("kept exactly") }
        assertEquals(false, kept.ok)
        assertTrue(kept.detail!!.contains("water"), kept.detail)
        assertEquals(setOf("distilled", "mylo"), state!!.enabledIds, "the state the hub reported is what the screen adopts")
    }

    @Test
    fun `a save with an unreadable reply is a failure that may have executed`() = runBlocking {
        val (out, state) = SetupActions(FakeHub("""{"unrelated": true}""").actor).save(setOf("a"))
        assertTrue(assertIs<ActionOutcome.Failed>(out).mayHaveExecuted)
        assertNull(state)
        val (garbled, _) = SetupActions(FakeHub("<html>").actor).save(setOf("a"))
        assertTrue(assertIs<ActionOutcome.Failed>(garbled).mayHaveExecuted)
    }

    @Test
    fun `a save rejected by the hub shows the status and detail`() = runBlocking {
        val (out, state) = SetupActions(FakeHub("""{"detail":"Setup catalog must list all 11 LTX 2.5 LoRAs."}""", HttpStatusCode.InternalServerError).actor).save(setOf("a"))
        val f = assertIs<ActionOutcome.Failed>(out)
        assertEquals(500, f.status)
        assertTrue(f.headline.contains("11 LTX 2.5 LoRAs"))
        assertNull(state)
    }

    @Test
    fun `an empty selection is refused locally - the hub would silently save everything`() = runBlocking {
        val hub = FakeHub(afterSave)
        val (out, state) = SetupActions(hub.actor).save(emptySet())
        assertIs<ActionOutcome.Failed>(out)
        assertNull(state)
        assertTrue(hub.engine.requestHistory.isEmpty())
    }

    @Test
    fun `save posts the sorted ids to the setup route`() = runTest {
        val hub = FakeHub(afterSave)
        SetupActions(hub.actor).save(setOf("mylo", "distilled"))
        assertEquals(listOf(HttpMethod.Post), hub.methods)
        assertEquals(listOf("/api/setup"), hub.paths)
        val body = opsJson.parseToJsonElement((hub.engine.requestHistory.single().body as TextContent).text).jsonObject
        assertEquals(listOf("distilled", "mylo"), body["enabled"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
