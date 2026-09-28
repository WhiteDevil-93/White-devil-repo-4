package com.whitedevil.veniceagent.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
            // unsolicited server-to-client "ping" between them; all tools must still surface.
            // "foo.bar" and "foo_bar" both sanitize to the same string, exercising
            // collision-safe naming.
            val definitions = client.definitions()
            val names = definitions.map { it.function.name }
            assertEquals(names.size, names.toSet().size, "exposed tool names must be unique: $names")
            assertTrue(names.containsAll(listOf("fake__echo", "fake__fail")), "unexpected names: $names")

            val echoResult = client.execute("fake__echo", """{"text":"hi"}""")
            assertTrue(echoResult.contains("echo: hi"), "unexpected tool result: $echoResult")

            // The fake server reports isError: true for "fail"; it must surface as an error,
            // not be treated as a successful result.
            val failResult = client.execute("fake__fail", "{}")
            assertTrue(failResult.startsWith("Error:"), "expected an error-prefixed result: $failResult")
            assertTrue(failResult.contains("boom"), "expected the tool's message to be preserved: $failResult")

            // Both collision candidates must route back to their own distinct original tool.
            val collisionNames = names.filter { it.startsWith("fake__foo_bar") }
            assertEquals(2, collisionNames.size, "expected two disambiguated names, got: $collisionNames")
            val collisionResults = collisionNames.map { client.execute(it, "{}") }.toSet()
            assertEquals(setOf("called: foo.bar", "called: foo_bar"), collisionResults)
        } finally {
            client.close()
        }
    }

    @Test
    fun `invalidates the cached tool list after notifications tools list_changed`() = runBlocking {
        assumeTrue(python3Available(), "python3 not available; skipping MCP stdio integration test")

        val script = File(javaClass.classLoader.getResource("fake_mcp_server.py")!!.toURI())
        val client = McpStdioClient("fake", McpServerConfig(command = "python3", args = listOf(script.absolutePath)))
        try {
            val before = client.definitions().map { it.function.name }
            assertTrue("fake__new_tool" !in before, "new_tool shouldn't exist yet: $before")

            // Not in the tool list yet, but execute() routes directly to the server by name.
            client.execute("fake__trigger_list_changed", "{}")

            val after = client.definitions().map { it.function.name }
            assertTrue("fake__new_tool" in after, "expected the cache to be rebuilt with new_tool: $after")
        } finally {
            client.close()
        }
    }

    @Test
    fun `retries after a failed refresh instead of caching the gap or losing the pending change`() = runBlocking {
        assumeTrue(python3Available(), "python3 not available; skipping MCP stdio integration test")

        val script = File(javaClass.classLoader.getResource("fake_mcp_server.py")!!.toURI())
        val client = McpStdioClient("fake", McpServerConfig(command = "python3", args = listOf(script.absolutePath)))
        try {
            val before = client.definitions().map { it.function.name }
            assertTrue("fake__new_tool" !in before, "new_tool shouldn't exist yet: $before")

            // The server accepts this, but the refresh attempt it triggers fails once.
            client.execute("fake__trigger_list_changed_with_failure", "{}")
            assertFailsWith<McpException> { client.definitions() }

            // The failed attempt must not have cleared the pending-change flag: the next call
            // has to retry rather than silently serving the stale pre-change list forever.
            val after = client.definitions().map { it.function.name }
            assertTrue("fake__new_tool" in after, "expected the retry to pick up new_tool: $after")
        } finally {
            client.close()
        }
    }

    @Test
    fun `preserves an invalidation that arrives during an in-flight refresh`() = runBlocking {
        assumeTrue(python3Available(), "python3 not available; skipping MCP stdio integration test")

        val script = File(javaClass.classLoader.getResource("fake_mcp_server.py")!!.toURI())
        val client = McpStdioClient("fake", McpServerConfig(command = "python3", args = listOf(script.absolutePath)))
        try {
            client.definitions() // establish an initial cache

            // Triggers notifications/tools/list_changed, and the refresh it causes will itself
            // receive a *second* such notification between page 1 and page 2 of tools/list.
            client.execute("fake__trigger_list_changed_mid_fetch", "{}")
            client.definitions()

            assertTrue(client.hasChanged(), "a change that arrived mid-refresh must not be lost")

            // The next call must actually re-fetch (not serve the cache from the first refresh)
            // and only then settle.
            client.definitions()
            assertFalse(client.hasChanged(), "should have caught up after the follow-up refresh")
        } finally {
            client.close()
        }
    }

    @Test
    fun `marks the provider changed when its subprocess exits`() = runBlocking {
        assumeTrue(python3Available(), "python3 not available; skipping MCP stdio integration test")

        val script = File(javaClass.classLoader.getResource("fake_mcp_server.py")!!.toURI())
        val client = McpStdioClient(
            "fake",
            McpServerConfig(command = "python3", args = listOf(script.absolutePath)),
            requestTimeoutMillis = 500,
        )
        try {
            client.definitions()
            assertFalse(client.hasChanged())

            // The fake server exits without replying, simulating a crash; execute() must not
            // hang (it times out and reports an error) or throw out of this test.
            client.execute("fake__exit_process", "{}")

            // readLoop notices the closed stream asynchronously; poll briefly for it.
            withTimeout(5_000) {
                while (!client.hasChanged()) delay(20)
            }
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
