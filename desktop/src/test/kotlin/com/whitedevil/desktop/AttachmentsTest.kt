package com.whitedevil.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.whitedevil.agent.ChatMessage
import com.whitedevil.agent.MessageContent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentsTest {
    private fun dir() = Files.createTempDirectory("att").toFile()
    private fun png(d: File, name: String = "pic.png", w: Int = 3000, h: Int = 2000) = File(d, name).also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", it) }

    @Test fun `a text file is read, a picture is shrunk to a jpeg data url`() {
        val d = dir(); val txt = File(d, "notes.md").also { it.writeText("hello\nworld") }
        val t = (Attachments.load(txt) as Attachments.Loaded.Ok).attachment
        assertEquals("hello\nworld", t.text); assertFalse(t.isImage)
        val i = (Attachments.load(png(d)) as Attachments.Loaded.Ok).attachment
        assertTrue(i.isImage && i.imageDataUrl!!.startsWith("data:image/jpeg;base64,"), "pictures go as jpeg")
        assertTrue(i.imageDataUrl!!.length < Attachments.MAX_TOTAL_TEXT_CHARS * 20, "and are shrunk, not sent at 3000 px")
    }

    @Test fun `what cannot be read is refused with a reason, never silently dropped`() {
        val d = dir()
        val pdf = File(d, "a.pdf").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val big = File(d, "big.txt").also { it.writeText("x".repeat((Attachments.MAX_TEXT_BYTES + 10).toInt())) }
        val bin = File(d, "blob.txt").also { it.writeBytes(byteArrayOf(65, 0, 66, 0)) }
        val exe = File(d, "tool.exe").also { it.writeBytes(byteArrayOf(77, 90)) }
        val fakePic = File(d, "broken.png").also { it.writeText("not really a picture") }
        for (f in listOf(pdf, big, bin, exe, fakePic, File(d, "missing.txt"))) {
            val r = Attachments.load(f)
            assertTrue(r is Attachments.Loaded.Refused && r.reason.startsWith(f.name), "${f.name} -> $r")
        }
        assertTrue((Attachments.load(big) as Attachments.Loaded.Refused).reason.contains("limit"))
    }

    @Test fun `adding keeps the good files, reports the bad ones, and stops at the file limit`() {
        val d = dir()
        val good = (1..8).map { File(d, "f$it.txt").also { f -> f.writeText("n$it") } }
        val bad = File(d, "x.zip").also { it.writeBytes(byteArrayOf(1)) }
        val (list, problems) = Attachments.addAll(emptyList(), listOf(bad) + good)
        assertEquals(Attachments.MAX_FILES, list.size); assertEquals("f1.txt", list.first().name)
        assertTrue(problems!!.contains("x.zip") && problems.contains("Only ${Attachments.MAX_FILES} files"))
        assertEquals(null, Attachments.addAll(emptyList(), listOf(good[0])).second)
    }

    @Test fun `files go into the message as fenced blocks and the chat shows only their names`() {
        val a = Attachment("log.txt", text = "line1\nline2"); val img = Attachment("p.png", imageDataUrl = "data:image/jpeg;base64,QQ==")
        val (text, images) = Attachments.compose("look at these", listOf(a, img))
        assertEquals(listOf("data:image/jpeg;base64,QQ=="), images)
        assertTrue(text.startsWith("look at these") && "[Attached file: log.txt]\n```\nline1\nline2\n```" in text)
        assertEquals("look at these\n\n📎 log.txt", Attachments.forDisplay(text))
        assertEquals("", Attachments.compose("", emptyList()).first); assertEquals("📎 log.txt", Attachments.forDisplay(Attachments.compose("", listOf(a)).first))
    }

    @Test fun `a huge pile of text is cut at the total budget and says so`() {
        val big = Attachment("a.txt", text = "y".repeat(Attachments.MAX_TOTAL_TEXT_CHARS)); val more = Attachment("b.txt", text = "z".repeat(1000))
        val (text, _) = Attachments.compose("", listOf(big, more))
        assertTrue(text.length < Attachments.MAX_TOTAL_TEXT_CHARS + 600, "bounded: ${text.length}"); assertTrue("cut: the rest of this file" in text)
    }

    @Test fun `restoring a saved chat shows file names, not file contents`() {
        val (text, _) = Attachments.compose("fix this", listOf(Attachment("big.kt", text = "fun main() {}\n".repeat(200))))
        val line = AgentSession.linesFrom(listOf(ChatMessage(role = "user", content = MessageContent.text(text)))).single()
        assertEquals("fix this\n\n📎 big.kt", line.body)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun `the chats panel searches, opens and deletes`() = runComposeUiTest {
        val d = dir(); val s = AgentSession.load(d)
        s.adopt(listOf(ChatMessage(role = "user", content = MessageContent.text("alpha topic")), ChatMessage(role = "assistant", content = MessageContent.text("ok")))); val a = s.currentId
        Thread.sleep(5); s.newChat()
        s.adopt(listOf(ChatMessage(role = "user", content = MessageContent.text("beta topic")), ChatMessage(role = "assistant", content = MessageContent.text("ok"))))
        var closed = false
        setContent { MaterialTheme(colorScheme = WhiteDevilColors) { ChatsPanel(s, busy = false, onClose = { closed = true }) } }
        mainClock.advanceTimeBy(1_000)
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("Chats (2)").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("alpha topic", substring = true).fetchSemanticsNodes().isNotEmpty() && onAllNodesWithText("beta topic", substring = true).fetchSemanticsNodes().isNotEmpty())
        onNode(hasText("alpha topic", substring = true) and hasClickAction()).performScrollTo().performClick(); waitForIdle()
        assertEquals(a, s.currentId, "clicking a chat opens it"); assertTrue(closed)
    }
}
