package com.whitedevil.veniceagent.mcp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the stdio JSON-RPC transport end-to-end against a tiny fake MCP server, skipping
 * if python3 isn't on PATH (the runtime itself has no Python dependency; this is a test-only
 * fixture chosen because it's the simplest thing that can speak line-delimited JSON on stdio).
 */
class McpStdioClientTest {

    private fun python3Available(): Boolean =
        runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false)

    @Test
    fun `lists and calls a tool over the stdio transport`() = runBlocking {
        assumeTrue(python3Available(), "python3 not available; skipping MCP stdio integration test")

        val script = File(javaClass.classLoader.getResource("fake_mcp_server.py")!!.toURI())
        val client = McpStdioClient("fake", McpServerConfig(command = "python3", args = listOf(script.absolutePath)))
        try {
            val definitions = client.definitions()
            assertEquals(1, definitions.size)
            assertEquals("fake__echo", definitions.single().function.name)

            val result = client.execute("fake__echo", """{"text":"hi"}""")
            assertTrue(result.contains("echo: hi"), "unexpected tool result: $result")
        } finally {
            client.close()
        }
    }
}
