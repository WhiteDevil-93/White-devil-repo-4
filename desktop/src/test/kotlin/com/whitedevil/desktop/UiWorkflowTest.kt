package com.whitedevil.desktop

import com.whitedevil.agent.*
import kotlin.test.*

class UiWorkflowTest {
    @Test fun `tool completion distinguishes returned output and reported failure`() {
        assertTrue(toolOutputState("Error: offline").startsWith("Failed"))
        assertTrue(toolOutputState("""{"ok":false}""").startsWith("Failed"))
        assertTrue(toolOutputState("queued").startsWith("Complete"))
    }
    @Test fun `out of order responses and repeated refreshes are rejected`() {
        val gate = LatestRequestGate(); val first = gate.next(); val second = gate.next()
        assertFalse(gate.accepts(first)); assertTrue(gate.accepts(second))
        val third = gate.next(); assertFalse(gate.accepts(second)); assertTrue(gate.accepts(third))
    }
    @Test fun `empty or non JSON response never claims completion`() {
        assertEquals("Request accepted", actionReplySummary(""))
        assertEquals("Request accepted", actionReplySummary("transport response"))
    }
    @Test fun `queued action exposes status and job identifier`() {
        assertEquals("queued · abc", actionReplySummary("""{"status":"queued","job_id":"abc"}"""))
    }
    @Test fun `application failures are failures even after HTTP success`() {
        listOf("""{"ok":false,"error":"denied"}""", """{"success":false}""", """{"status":"failed"}""").forEach {
            assertFailsWith<IllegalStateException> { actionReplySummary(it) }
        }
    }
    @Test fun `restored agent receives previous turns and authoritative system prompt`() {
        VeniceClient("offline-test-key").use { client ->
            val agent = Agent(client, "offline-model", ToolBox(java.nio.file.Files.createTempDirectory("wd-restore-").toFile(), "http://127.0.0.1:1", "", ""), "current prompt")
            agent.restore(listOf(ChatMessage("system", MessageContent.text("stale")), ChatMessage("user", MessageContent.text("previous turn"))))
            assertEquals(listOf("current prompt", "previous turn"), agent.snapshot().map { it.textContent() })
        }
    }
}
