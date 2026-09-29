package com.whitedevil.desktop.ops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionOutcomesTest {

    private fun reply(body: String, status: Int = 200) = HubReply(status, body, parseJsonOrNull(body))

    @Test
    fun `explicit ok false or success false is a failure even at HTTP 200`() {
        for (body in listOf("""{"ok": false}""", """{"success": false}""", """{"ok": false, "success": true}""")) {
            val out = assertIs<ActionOutcome.Failed>(interpretGeneric(reply(body), "Do it"), body)
            assertEquals(200, out.status)
            assertTrue(out.rawBody!!.isNotBlank())
        }
    }

    @Test
    fun `no flag is accepted - never described as done`() {
        val out = assertIs<ActionOutcome.Succeeded>(interpretGeneric(reply("""{"state": "queued"}"""), "Do it"))
        assertTrue(out.headline.contains("accepted"))
        assertFalse(out.headline.contains("done", ignoreCase = true))
        assertTrue(out.headline.contains("no explicit success flag"))
    }

    @Test
    fun `a bare error field does not flip the verdict but is surfaced`() {
        val out = assertIs<ActionOutcome.Succeeded>(
            interpretGeneric(reply("""{"id": "j1", "status": "queued", "error": "old failure from the last attempt"}"""), "Retry job j1"),
        )
        assertTrue(out.headline.contains("error field"), out.headline)
        assertTrue(out.headline.contains("old failure"))
    }

    @Test
    fun `explicit success true is reported as the hub reporting success`() {
        val out = assertIs<ActionOutcome.Succeeded>(interpretGeneric(reply("""{"success": true}"""), "Stop"))
        assertTrue(out.headline.contains("reports success"))
    }

    @Test
    fun `an array or non-JSON reply is not a clean success`() {
        assertIs<ActionOutcome.Succeeded>(interpretGeneric(reply("[]"), "Do it")) // accepted, with no flag to lean on
        val bad = assertIs<ActionOutcome.Failed>(interpretGeneric(HubReply(200, "<html>", null), "Do it"))
        assertTrue(bad.mayHaveExecuted)
    }

    @Test
    fun `bodies are capped so a huge reply cannot flood the dialog`() {
        val huge = "x".repeat(50_000)
        val out = assertIs<ActionOutcome.Succeeded>(interpretGeneric(reply("""{"blob": "$huge"}"""), "Do it"))
        assertTrue(out.rawBody!!.length < MAX_BODY_CHARS + 100)
        assertTrue(out.rawBody!!.contains("more characters not shown"))
    }

    @Test
    fun `transport errors become Failed outcomes with the status and body`() {
        val e = describeHttpFailure(409, """{"detail":"Vast didn't accept the rental; the offer may be gone."}""")
        val out = e.toFailedOutcome()
        assertEquals(409, out.status)
        assertTrue(out.headline.contains("offer may be gone"))
        assertFalse(out.mayHaveExecuted, "a 409 is a refusal, not an unknown")
        assertTrue(describeHttpFailure(504, "").toFailedOutcome().mayHaveExecuted)
        assertNull(describeHttpFailure(400, "").toFailedOutcome().detail)
    }

    // ---- formatting: unknowns are spelled out, never zero ---------------------------------------

    @Test
    fun `format helpers never turn unknown into zero`() {
        assertEquals("unknown", money(null))
        assertEquals("unknown", plainNumber(null))
        assertEquals("unknown", orUnknown(null))
        assertEquals("unknown", yesNoUnknown(null))
        assertEquals("$0.00", money(0.0))
        assertEquals("$1.235", money(1.2345, 3))
        assertEquals("0.77", plainNumber(0.77))
        assertNull(ageOfEpochSec(null))
        assertNull(ageOfEpochSec(0.0), "a zero stamp means never, not 1970")
        assertEquals(90L, ageOfEpochSec(910.0, nowMillis = 1_000_000))
    }

    @Test
    fun `ages read naturally and never go negative`() {
        assertEquals("0s", formatAge(-5))
        assertEquals("45s", formatAge(45))
        assertEquals("3m 05s", formatAge(185))
        assertEquals("2h 10m", formatAge(7_800))
    }
}
