package com.whitedevil.desktop.ops

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpsStateTest {

    // ---- PanelState: an error is an Error, never an empty Loaded --------------------------------

    @Test
    fun `a failed load becomes Error with the message and status, not an empty Loaded`() = runTest {
        val panel = PanelState<List<String>>({ OpsResult.Err(OpsError("HTTP 503 from the hub: down", status = 503)) })
        assertEquals(OpsState.Loading, panel.state)
        panel.refresh()
        val s = assertIs<OpsState.Error>(panel.state)
        assertEquals(503, s.status)
        assertTrue(s.message.contains("down"))
    }

    @Test
    fun `a load that throws becomes Error - the exception is not swallowed into empty`() = runTest {
        val panel = PanelState<List<String>>({ throw IllegalStateException("parser bug") })
        panel.refresh()
        val s = assertIs<OpsState.Error>(panel.state)
        assertTrue(s.message.contains("parser bug"), s.message)
    }

    @Test
    fun `a genuinely empty list is Loaded - distinct from Error`() = runTest {
        val panel = PanelState<List<String>>({ OpsResult.Ok(emptyList()) })
        panel.refresh()
        assertEquals(OpsState.Loaded(emptyList<String>()), panel.state)
    }

    @Test
    fun `after a failure the last good value is kept for a STALE view, but state is Error`() = runTest {
        var fail = false
        var t = 1_000L
        val panel = PanelState<String>({ if (fail) OpsResult.Err(OpsError("boom")) else OpsResult.Ok("v1") }, clock = { t })
        panel.refresh()
        assertEquals(OpsState.Loaded("v1"), panel.state)
        t = 2_000L
        fail = true
        panel.refresh()
        assertIs<OpsState.Error>(panel.state)
        assertEquals(Stamped("v1", 1_000L), panel.lastGood)
    }

    @Test
    fun `refresh is single-flight - an overlapping refresh is dropped, not queued`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val panel = PanelState<String>({ calls++; gate.await(); OpsResult.Ok("v") })
        val first = async(start = CoroutineStart.UNDISPATCHED) { panel.refresh() }
        assertTrue(panel.refreshing)
        assertFalse(panel.refresh(), "second refresh must be refused while the first is in flight")
        gate.complete(Unit)
        assertTrue(first.await())
        assertEquals(1, calls)
        assertFalse(panel.refreshing)
        assertTrue(panel.refresh(), "a later refresh is allowed again")
        assertEquals(2, calls)
    }

    @Test
    fun `a follow-up refresh during a load runs exactly one more load afterwards - it does not stack`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val panel = PanelState<Int>({
            val n = ++calls
            if (n == 1) gate.await()
            OpsResult.Ok(n)
        })
        val first = async(start = CoroutineStart.UNDISPATCHED) { panel.refresh() }
        assertFalse(panel.refresh(followUp = true))
        assertFalse(panel.refresh(followUp = true))
        assertFalse(panel.refresh(followUp = true))
        assertFalse(panel.refresh(), "a plain refresh during a load is still just dropped")
        gate.complete(Unit)
        first.await()
        assertEquals(2, calls, "three follow-ups collapse into one extra load")
        assertEquals(OpsState.Loaded(2), panel.state, "the state reflects the load that started after the action")
        assertFalse(panel.refreshing)
    }

    @Test
    fun `a follow-up with nothing in flight is just a normal refresh`() = runTest {
        var calls = 0
        val panel = PanelState<Int>({ OpsResult.Ok(++calls) })
        assertTrue(panel.refresh(followUp = true))
        assertEquals(1, calls)
    }

    @Test
    fun `the poll is held back while the panel is in error or in flight`() = runTest {
        var fail = true
        val gate = CompletableDeferred<Unit>()
        var waitForGate = false
        val panel = PanelState<String>({
            if (waitForGate) gate.await()
            if (fail) OpsResult.Err(OpsError("x")) else OpsResult.Ok("v")
        })
        assertTrue(panel.shouldPoll(), "before the first load the initial poll is fine")
        panel.refresh()
        assertFalse(panel.shouldPoll(), "after an error only an explicit Retry loads again")
        fail = false
        panel.refresh() // the operator's explicit retry
        assertTrue(panel.shouldPoll())
        waitForGate = true
        val inflight = async(start = CoroutineStart.UNDISPATCHED) { panel.refresh() }
        assertFalse(panel.shouldPoll(), "no poll while a request is in flight")
        gate.complete(Unit)
        inflight.await()
    }

    @Test
    fun `adopt replaces the state with a value the hub just returned`() = runTest {
        val panel = PanelState<String>({ OpsResult.Err(OpsError("x")) })
        panel.refresh()
        panel.adopt("fresh")
        assertEquals(OpsState.Loaded("fresh"), panel.state)
    }

    // ---- ActionController: the confirmation gate ------------------------------------------------

    private fun spec(phrase: String?, ran: MutableList<String>, outcome: ActionOutcome = ActionOutcome.Succeeded("done")) =
        ActionSpec("Stop it", listOf("costs money"), "Stop", typedPhrase = phrase, run = { ran += "ran"; outcome })

    @Test
    fun `nothing runs until confirm - opening the dialog is not an action`() = runTest {
        val ran = mutableListOf<String>()
        val c = ActionController()
        c.request(spec("STOP", ran))
        assertIs<ActionPhase.Confirming>(c.phase)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `a typed phrase gates the run - wrong or empty text does not run it`() = runTest {
        val ran = mutableListOf<String>()
        val c = ActionController()
        c.request(spec("STOP", ran))
        assertFalse(c.canConfirm())
        assertFalse(c.confirm())
        c.updateTyped("stop")
        assertFalse(c.confirm(), "case matters")
        c.updateTyped("STOP ME")
        assertFalse(c.confirm())
        assertTrue(ran.isEmpty())
        c.updateTyped("  STOP ")
        assertTrue(c.canConfirm())
        assertTrue(c.confirm())
        assertEquals(1, ran.size)
        assertIs<ActionPhase.Finished>(c.phase)
    }

    @Test
    fun `a two-step action (no phrase) runs on confirm`() = runTest {
        val ran = mutableListOf<String>()
        val c = ActionController()
        c.request(spec(null, ran))
        assertTrue(c.canConfirm())
        assertTrue(c.confirm())
        assertEquals(1, ran.size)
    }

    @Test
    fun `confirm without a request does nothing`() = runTest {
        val c = ActionController()
        assertFalse(c.confirm())
        assertEquals(ActionPhase.Idle, c.phase)
    }

    @Test
    fun `a second confirm while running does not run it again, and dismiss is refused mid-run`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val c = ActionController()
        c.request(ActionSpec("x", emptyList(), "Go", run = { runs++; gate.await(); ActionOutcome.Succeeded("ok") }))
        val first = async(start = CoroutineStart.UNDISPATCHED) { c.confirm() }
        assertIs<ActionPhase.Running>(c.phase)
        assertFalse(c.confirm(), "double click must not double-fire")
        c.dismiss()
        assertIs<ActionPhase.Running>(c.phase, "cannot dismiss an in-flight action; its outcome must be seen")
        assertFalse(c.request(ActionSpec("y", emptyList(), "Go", run = { ActionOutcome.Succeeded("y") })), "no new request mid-run")
        gate.complete(Unit)
        first.await()
        assertEquals(1, runs)
    }

    @Test
    fun `dismiss cancels without running`() = runTest {
        val ran = mutableListOf<String>()
        val c = ActionController()
        c.request(spec("STOP", ran))
        c.dismiss()
        assertEquals(ActionPhase.Idle, c.phase)
        assertFalse(c.confirm())
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `a failed outcome is kept as Failed - the gate does not turn it into success`() = runTest {
        val failed = ActionOutcome.Failed("ok=false", listOf(OutcomePart("part", false, "why")))
        val seen = mutableListOf<ActionOutcome>()
        val c = ActionController(onFinished = { seen += it })
        c.request(spec(null, mutableListOf(), failed))
        c.confirm()
        val fin = assertIs<ActionPhase.Finished>(c.phase)
        assertEquals(failed, fin.outcome)
        assertEquals(listOf<ActionOutcome>(failed), seen)
    }

    @Test
    fun `a run that throws is reported as Failed and possibly executed`() = runTest {
        val c = ActionController()
        c.request(ActionSpec("x", emptyList(), "Go", run = { throw IllegalStateException("kaput") }))
        c.confirm()
        val fin = assertIs<ActionPhase.Finished>(c.phase)
        val out = assertIs<ActionOutcome.Failed>(fin.outcome)
        assertTrue(out.headline.contains("kaput"))
        assertTrue(out.mayHaveExecuted)
        assertNotNull(out.headline)
        assertNull(out.status)
    }
}
