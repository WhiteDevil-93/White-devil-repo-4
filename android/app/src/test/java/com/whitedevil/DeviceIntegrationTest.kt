package com.whitedevil

import com.whitedevil.agent.Attachments
import com.whitedevil.agent.Agent
import com.whitedevil.agent.ChatMessage
import com.whitedevil.agent.MessageContent
import com.whitedevil.agent.ToolBox
import com.whitedevil.agent.VeniceClient
import com.whitedevil.agent.stripBlobs
import com.whitedevil.agent.textContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceIntegrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun testPlainTextContentRoundTrip() {
        val msg = ChatMessage(role = "user", content = MessageContent.text("hello"))
        assertEquals("hello", msg.textContent())
    }

    @Test
    fun testMultimodalContentShape() {
        val content = MessageContent.multimodal(
            "what is this?",
            listOf("data:image/jpeg;base64,AAA", "data:image/jpeg;base64,BBB"),
        )
        assertTrue(content is JsonArray)
        val parts = (content as JsonArray)
        assertEquals(3, parts.size)
        // Text comes first, then the images in order.
        assertTrue(parts[0].toString().contains("what is this?"))
        assertTrue(parts[1].toString().contains("image_url"))
        assertTrue(parts[2].toString().contains("BBB"))
        // textContent() recovers the text for UI display.
        val msg = ChatMessage(role = "user", content = content)
        assertEquals("what is this?", msg.textContent())
    }

    @Test
    fun testMultimodalWithoutImagesFallsBackToText() {
        val content = MessageContent.multimodal("just text", emptyList())
        assertTrue(content is JsonPrimitive)
        assertEquals("just text", content.textContent())
    }

    @Test
    fun testAttachmentKindMapping() {
        assertEquals(Attachments.Kind.IMAGE, Attachments.kindOf("image/jpeg"))
        assertEquals(Attachments.Kind.VIDEO, Attachments.kindOf("video/mp4"))
        assertEquals(Attachments.Kind.AUDIO, Attachments.kindOf("audio/mpeg"))
        assertEquals(Attachments.Kind.DOCUMENT, Attachments.kindOf("application/pdf"))
        assertEquals(Attachments.Kind.DOCUMENT, Attachments.kindOf(null))
    }

    @Test
    fun testSafeFileNameBlocksTraversal() {
        assertEquals("notes.txt", Attachments.safeFileName("../../notes.txt", "fallback.bin"))
        assertEquals("fallback.bin", Attachments.safeFileName("...", "fallback.bin"))
        assertEquals("fallback.bin", Attachments.safeFileName(null, "fallback.bin"))
    }

    @Test
    fun testFormatSize() {
        assertEquals("500 B", Attachments.formatSize(500))
        assertEquals("2.0 KB", Attachments.formatSize(2048))
        assertEquals("5.0 MB", Attachments.formatSize(5 * 1024 * 1024))
    }

    @Test
    fun testWorkspaceNoteMentionsPath() {
        val note = Attachments.workspaceNote("uploads/clip.mp4", "video/mp4", 2048)
        assertTrue(note.contains("uploads/clip.mp4"))
        assertTrue(note.contains("video"))
    }

    @Test
    fun testStripBlobsRemovesImageData() {
        val content = MessageContent.multimodal("look at this", listOf("data:image/jpeg;base64,AAA"))
        val stripped = stripBlobs(content)
        assertEquals("look at this", stripped.textContent())
        assertTrue(!stripped.toString().contains("AAA"))
    }

    @Test
    fun testStripBlobsKeepsPlainText() {
        val content = MessageContent.text("plain")
        assertEquals("plain", stripBlobs(content).textContent())
    }

    @Test
    fun testAgentHistorySnapshotRestore() {
        val dir = folder.newFolder("agent_history")
        val box = ToolBox(
            workspaceDir = dir,
            relayBaseUrl = "https://84-12-112-249.sslip.io",
            relayUser = "anon3",
            relayPass = "secret",
        )
        val client = VeniceClient(apiKey = "test-key")
        try {
            val agent = Agent(
                client = client,
                model = "test-model",
                toolBox = box,
                systemPrompt = "live-sys",
            )
            agent.restore(
                listOf(
                    ChatMessage(role = "system", content = MessageContent.text("stale-sys")),
                    ChatMessage(role = "user", content = MessageContent.text("hi")),
                    ChatMessage(role = "assistant", content = MessageContent.text("hello")),
                    ChatMessage(role = "bogus", content = MessageContent.text("dropped")),
                ),
            )
            val snap = agent.snapshot()
            // Live system prompt re-anchored first; stale system + bogus role dropped.
            assertEquals(3, snap.size)
            assertEquals("system", snap[0].role)
            assertEquals("live-sys", snap[0].textContent())
            assertEquals("user", snap[1].role)
            assertEquals("hi", snap[1].textContent())
            assertEquals("assistant", snap[2].role)

            // Snapshot serializes (persistence format check).
            val encoded = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()),
                snap,
            )
            assertTrue(encoded.contains("hello"))
        } finally {
            client.close()
        }
    }
}
