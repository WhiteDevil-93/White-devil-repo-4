package com.whitedevil.desktop

import com.whitedevil.agent.*
import java.nio.file.Files
import kotlin.test.*

class AgentSessionTest {
    private fun file() = Files.createTempDirectory("wd-agent-test-").toFile().resolve("history.json")
    private fun message(role: String, text: String) = ChatMessage(role, MessageContent.text(text))

    @Test fun `history and transcript survive reload`() {
        val file = file()
        AgentSession(file).useSession { it.adopt(listOf(message("user", "first goal"), message("assistant", "first answer"))) }
        AgentSession(file).useSession {
            assertEquals(listOf("first goal", "first answer"), it.history.map { m -> m.textContent() })
            assertEquals(2, it.lines.size)
            assertEquals("Saved locally", it.saveStatus)
        }
    }
    @Test fun `clear persists an empty history`() {
        val file = file()
        AgentSession(file).useSession { it.adopt(listOf(message("user", "old"))); it.clear() }
        AgentSession(file).useSession { assertTrue(it.history.isEmpty()); assertTrue(it.lines.isEmpty()) }
    }
    @Test fun `clear cannot erase an active run`() {
        val file = file()
        AgentSession(file).useSession {
            it.adopt(listOf(message("user", "keep"))); it.busy = true; it.clear()
            assertEquals("keep", it.history.single().textContent())
        }
    }
    @Test fun `images and stale system messages do not enter saved history`() {
        val file = file()
        AgentSession(file).useSession { it.adopt(listOf(message("system", "old"), ChatMessage("user", MessageContent.multimodal("goal", listOf("data:image/png;base64,PRIVATE"))))) }
        assertFalse(file.readText().contains("PRIVATE"))
        AgentSession(file).useSession { assertEquals("goal", it.history.single().textContent()) }
    }
    @Test fun `unreadable history is never overwritten without explicit clear`() {
        val file = file(); file.writeText("broken history")
        AgentSession(file).useSession {
            assertTrue(it.loadFailed); it.save()
            assertEquals("broken history", file.readText())
            it.clear(); assertFalse(it.loadFailed)
        }
    }
    @Test fun `save failure is visible and history stays available for retry`() {
        val parent = Files.createTempFile("wd-blocked-parent-", ".tmp").toFile()
        AgentSession(parent.resolve("history.json")).useSession {
            it.adopt(listOf(message("user", "keep in memory")))
            assertTrue(it.saveStatus.startsWith("Not saved"))
            assertEquals("keep in memory", it.history.single().textContent())
        }
    }
    @Test fun `history is bounded and draft survives using the same window session`() {
        AgentSession(file()).useSession {
            it.input = "unfinished draft"
            it.adopt((0..120).map { n -> message("user", "$n") })
            assertEquals(Agent.MAX_HISTORY_MESSAGES, it.history.size)
            assertEquals("unfinished draft", it.input)
        }
    }
    private fun AgentSession.useSession(block: (AgentSession) -> Unit) { try { block(this) } finally { close() } }
}
