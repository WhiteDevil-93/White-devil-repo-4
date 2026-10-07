package com.whitedevil

import com.whitedevil.agent.*
import org.junit.Assert.*
import org.junit.Test

class UiWorkflowTest {
    @Test fun `attachment only submission is allowed but empty or busy submission is blocked`() {
        assertTrue(canSubmitGoal(true, "", 1))
        assertTrue(canSubmitGoal(true, "goal", 0))
        assertFalse(canSubmitGoal(true, "   ", 0))
        assertFalse(canSubmitGoal(false, "goal", 1))
        assertFalse(canSubmitGoal(false, "", 1))
    }
    @Test fun `old refreshes cannot overwrite newer data even when returning to same screen`() {
        val gate = LatestRequestGate()
        val first = gate.next(); gate.next(); val returned = gate.next()
        assertFalse(gate.accepts(first)); assertTrue(gate.accepts(returned))
    }
    @Test fun `HTTP success with application error is not Done`() {
        try { actionReplySummary("""{"ok":false,"error":"denied"}"""); fail("Should reject application failure") }
        catch (expected: IllegalStateException) { assertEquals("denied", expected.message) }
        assertEquals("queued · test-job", actionReplySummary("""{"status":"queued","job_id":"test-job"}"""))
        assertEquals("Request accepted", actionReplySummary("{}"))
    }
}
