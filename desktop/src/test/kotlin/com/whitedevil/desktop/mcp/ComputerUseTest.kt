package com.whitedevil.desktop.mcp

import com.whitedevil.agent.ToolBox
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop and browser control for Venice: actions listed in askBefore wait for a yes, screenshots reach a vision
 * model (downscaled) and are replaced by a note for one that cannot see, and a top-level oneOf schema is flattened
 * so Venice accepts the tool list.
 */
class ComputerUseTest {
    private val py: String = listOf("python3", "python", "py").firstOrNull { runCatching { ProcessBuilder(it, "--version").start().waitFor() == 0 }.getOrDefault(false) } ?: "python3"
    private fun script() = File(javaClass.classLoader.getResource("mcp/fake_cu_server.py")!!.toURI()).absolutePath.replace("\\", "/")
    private fun host(dir: File, gate: ApprovalGate) = McpHost(File(dir, "mcp-servers.json").also {
        it.writeText("""{"mcpServers":{"cu":{"command":"$py","args":["${script()}"],"askBefore":["click","drag*"]}}}""")
    }, gate)

    private fun waitFor(cond: () -> Boolean) { val end = System.currentTimeMillis() + 10_000; while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20) }

    @Test fun `an askBefore action waits for the answer, and allow-for-this-chat lasts until a new chat`(@TempDir dir: File) {
        val gate = ApprovalGate(File(dir, "actions.log"))
        val h = host(dir, gate)
        try {
            h.definitions()
            var out = ""
            val t = thread { out = h.executeDetailed("cu__click", """{"x":5}""").text }
            waitFor { gate.pending != null }
            assertEquals("click", gate.pending?.tool); assertEquals("cu", gate.pending?.server)
            assertTrue(out.isEmpty(), "nothing ran before the answer")
            gate.answer(ApprovalGate.Answer.Deny); t.join(10_000)
            assertTrue(out.startsWith("Error: the user did not allow click"), out)

            val t2 = thread { out = h.executeDetailed("cu__click", """{"x":6}""").text }
            waitFor { gate.pending != null }; gate.answer(ApprovalGate.Answer.ForChat); t2.join(10_000)
            assertEquals("""did click {"x": 6}""", out)
            assertEquals("""did click {"x": 7}""", h.executeDetailed("cu__click", """{"x":7}""").text, "no second question in this chat")
            assertNull(gate.pending)

            gate.reset()
            val t3 = thread { out = h.executeDetailed("cu__click", """{"x":8}""").text }
            waitFor { gate.pending != null }
            assertTrue(gate.pending != null, "a new chat asks again")
            gate.denyPending(); t3.join(10_000)
            assertTrue(out.startsWith("Error: the user did not allow"), "Interrupt refuses what is waiting")

            assertTrue(h.executeDetailed("cu__get_app_state", """{"app":"x"}""").text.startsWith("did get_app_state"), "reading is not asked")
            val log = File(dir, "actions.log").readText()
            assertTrue("denied  cu/click" in log && "allowed for this chat  cu/click" in log && "auto-allowed (this chat)  cu/click" in log, log)
        } finally { h.close() }
    }

    @Test fun `a screenshot reaches a vision model downscaled, and only a note reaches one that cannot see`(@TempDir dir: File) {
        val h = host(dir, ApprovalGate())
        try {
            h.definitions()
            val blind = h.executeDetailed("cu__screenshot", "{}")
            assertTrue(blind.imageDataUrls.isEmpty())
            assertTrue("window: Notepad" in blind.text && "cannot see images" in blind.text, blind.text)

            h.visionEnabled = { true }
            val seen = h.executeDetailed("cu__screenshot", "{}")
            assertEquals(1, seen.imageDataUrls.size)
            assertTrue(seen.text.contains("[screenshot 1]") && "base64" !in seen.text, "the image is not left in the text")
            val url = seen.imageDataUrls.single()
            assertTrue(url.startsWith("data:image/jpeg;base64,"))
            val img = ImageIO.read(Base64.getDecoder().decode(url.substringAfter("base64,")).inputStream())
            assertEquals(1280, img.width, "2600x1300 is scaled to 1280 on the long side"); assertEquals(640, img.height)

            val box = ToolBox(File(dir, "ws"), "http://127.0.0.1:9", "u", "p", extension = h)
            assertEquals(1, box.executeDetailed("cu__screenshot", "{}").imageDataUrls.size, "the agent's tool box gets the image too")
        } finally { h.close() }
    }

    @Test fun `a top-level oneOf schema is flattened for the model`(@TempDir dir: File) {
        val h = host(dir, ApprovalGate())
        try {
            val schema = h.definitions().first { it.function.name == "cu__get_app_state" }.function.parameters.toString()
            assertFalse("oneOf" in schema, schema)
            assertTrue("\"type\":\"object\"" in schema && "\"app\"" in schema && "\"pid\"" in schema, schema)
        } finally { h.close() }
    }

    @Test fun `image markers are split out of tool text`() {
        val (text, images) = McpHost.split("a${IMAGE_START}data:image/png;base64,AAA${IMAGE_END}b${IMAGE_START}data:x;base64,BBB${IMAGE_END}")
        assertEquals("a[screenshot 1]b[screenshot 2]", text)
        assertEquals(listOf("data:image/png;base64,AAA", "data:x;base64,BBB"), images)
        assertEquals("plain" to emptyList(), McpHost.split("plain"))
    }
}
