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
            // The fake server paginates tools/list across two pages and interleaves an
            // unsolicited server-to-client "ping" between them; both tools must still surface.
            val definitions = client.definitions()
            val names = definitions.map { it.function.name }.toSet()
            assertEquals(setOf("fake__echo", "fake__fail"), names)

            val echoResult = client.execute("fake__echo", """{"text":"hi"}""")
            assertTrue(echoResult.contains("echo: hi"), "unexpected tool result: $echoResult")

            // The fake server reports isError: true for "fail"; it must surface as an error,
            // not be treated as a successful result.
            val failResult = client.execute("fake__fail", "{}")
            assertTrue(failResult.startsWith("Error:"), "expected an error-prefixed result: $failResult")
            assertTrue(failResult.contains("boom"), "expected the tool's message to be preserved: $failResult")
        } finally {
            client.close()
        }
    }

    @Test
    fun `rejects non-object tool arguments instead of substituting defaults`() = runBlocking {
        assumeTrue(python3Available(), "python3 not available; skipping MCP stdio integration test")

        val script = File(javaClass.classLoader.getResource("fake_mcp_server.py")!!.toURI())
        val client = McpStdioClient("fake", McpServerConfig(command = "python3", args = listOf(script.absolutePath)))
        try {
            val result = client.execute("fake__echo", "not json")
            assertTrue(result.startsWith("Error:"), "expected malformed arguments to be rejected: $result")
        } finally {
            client.close()
        }
    }
}
