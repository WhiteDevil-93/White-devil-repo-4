package com.whitedevil.desktop.mcp

import com.whitedevil.agent.ToolBox
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpHostTest {
    private val py: String = listOf("python3", "python").firstOrNull { runCatching { ProcessBuilder(it, "--version").start().waitFor() == 0 }.getOrDefault(false) } ?: "python3"
    private fun script() = File(javaClass.classLoader.getResource("mcp/fake_mcp_server.py")!!.toURI()).absolutePath.replace("\\", "/")
    private fun config(dir: File, name: String = "fake") = File(dir, "mcp-servers.json").also {
        it.writeText("""{"mcpServers":{"$name":{"command":"$py","args":["${script()}"]}}}""")
    }

    @Test fun `a connector's tools reach the agent's tool box and calls are routed to it`(@TempDir dir: File) {
        val host = McpHost(config(dir))
        try {
            val box = ToolBox(File(dir, "ws"), "http://127.0.0.1:9", "u", "p", extension = host)
            val names = box.definitions.map { it.function.name }
            assertTrue("fake__echo" in names, "the MCP tool is offered to the model: $names")
            assertTrue("remember" in names && "hub_request" in names, "built-in tools are still there")
            assertEquals("hello", box.executeDetailed("fake__echo", """{"text":"hello"}""").text.trim().removePrefix("echo: "))
            assertTrue(box.executeDetailed("nope", "{}").text.startsWith("Error: unknown tool"), "unknown names are still refused")
            assertEquals(listOf("fake" to names.count { it.startsWith("fake__") }), host.status())
        } finally { host.close() }
    }

    @Test fun `no config means no extra tools and nothing is started`(@TempDir dir: File) {
        val host = McpHost(File(dir, "mcp-servers.json"))
        val box = ToolBox(File(dir, "ws"), "http://127.0.0.1:9", "u", "p", extension = host)
        assertTrue(box.definitions.none { "__" in it.function.name }); assertTrue(host.status().isEmpty())
        assertNotNull(host.configText().takeIf { "mcpServers" in it }, "the panel starts from an example")
    }

    @Test fun `saving validates the json, writes it, and restarts the servers`(@TempDir dir: File) {
        val host = McpHost(File(dir, "mcp-servers.json"))
        try {
            assertTrue(host.saveConfig("{ nope")!!.startsWith("That is not valid JSON"))
            assertTrue(!File(dir, "mcp-servers.json").exists(), "a bad file is never written")
            assertNull(host.saveConfig(config(dir, "other").readText()))
            assertTrue(host.definitions().any { it.function.name == "other__echo" })
        } finally { host.close() }
    }

    @Test fun `on windows bare launcher names run through cmd, paths and real executables do not`() {
        val npx = McpServerConfig("npx", listOf("-y", "srv"))
        assertEquals(McpServerConfig("cmd", listOf("/c", "npx", "-y", "srv")), McpServerLoader.forThisOs(npx, windows = true))
        assertEquals(npx, McpServerLoader.forThisOs(npx, windows = false))
        val exe = McpServerConfig("C:\\tools\\srv.exe", listOf("a"))
        assertEquals(exe, McpServerLoader.forThisOs(exe, windows = true))
        val rel = McpServerConfig("python.exe")
        assertEquals(rel, McpServerLoader.forThisOs(rel, windows = true))
    }
}
