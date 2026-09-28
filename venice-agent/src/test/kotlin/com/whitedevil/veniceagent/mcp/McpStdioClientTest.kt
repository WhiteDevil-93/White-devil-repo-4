package com.whitedevil.veniceagent.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.system.measureTimeMillis
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
    fun `marks the provider changed and fails the in-flight call fast when its subprocess exits`() = runBlocking {
        assumeTrue(python3Available(), "python3 not available; skipping MCP stdio integration test")

        val script = File(javaClass.classLoader.getResource("fake_mcp_server.py")!!.toURI())
        // A timeout much longer than this test should ever take: if execute() below only
        // returns because it timed out rather than because the closed stream failed it
        // immediately, this proves the fix isn't in effect.
        val client = McpStdioClient(
            "fake",
            McpServerConfig(command = "python3", args = listOf(script.absolutePath)),
            requestTimeoutMillis = 10_000,
        )
        try {
            client.definitions()
            assertFalse(client.hasChanged())

            // The fake server exits without replying, simulating a crash.
            val elapsedMillis = measureTimeMillis {
                val result = client.execute("fake__exit_process", "{}")
                assertTrue(result.startsWith("Error:"), "expected an error result, got: $result")
            }
            assertTrue(elapsedMillis < 5_000, "expected the dead process to fail the call immediately, took ${elapsedMillis}ms")

            // readLoop notices the closed stream asynchronously; poll briefly for it.
            withTimeout(5_000) {
                while (!client.hasChanged()) delay(20)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `rejects a malformed tools list page instead of silently truncating`() = runBlocking {
        assumeTrue(python3Available(), "python3 not available; skipping MCP stdio integration test")

        val script = File(javaClass.classLoader.getResource("fake_mcp_server.py")!!.toURI())
        val client = McpStdioClient("fake", McpServerConfig(command = "python3", args = listOf(script.absolutePath)))
        try {
            client.definitions() // establish an initial cache

            client.execute("fake__trigger_malformed_list", "{}")
            assertFailsWith<McpException> { client.definitions() }

            // The failed attempt must not have poisoned the cache or the pending-change flag:
            // a normal refresh (once the server stops misbehaving) still works afterward.
            val after = client.definitions().map { it.function.name }
            assertTrue("fake__echo" in after)
        } finally {
            client.close()
        }
    }

    @Test
    fun `stops pagination when an mcp cursor repeats instead of looping forever`() = runBlocking {
        assumeTrue(python3Available(), "python3 not available; skipping MCP stdio integration test")

        val script = File(javaClass.classLoader.getResource("fake_mcp_server.py")!!.toURI())
        val client = McpStdioClient("fake", McpServerConfig(command = "python3", args = listOf(script.absolutePath)))
        try {
            client.definitions()

            client.execute("fake__trigger_cursor_loop", "{}")
            withTimeout(5_000) {
                assertFailsWith<McpException> { client.definitions() }
            }
        } finally {
            client.close()
        }
        Unit // assertFailsWith above returns the caught exception; without this the function's
        // inferred return type stops being Unit and JUnit silently won't register it as a @Test.
    }

    @Test
    fun `retires the connection after initialize times out instead of retrying it`() = runBlocking {
        assumeTrue(python3Available(), "python3 not available; skipping MCP stdio integration test")

        val script = File(javaClass.classLoader.getResource("fake_mcp_server.py")!!.toURI())
        val client = McpStdioClient(
            "fake",
            McpServerConfig(command = "python3", args = listOf(script.absolutePath, "--hang-initialize")),
            requestTimeoutMillis = 300,
        )
        try {
            assertFailsWith<TimeoutCancellationException> { client.definitions() }

            // The connection must be retired, not retried: the next attempt fails immediately
            // with a clear error instead of sending a second initialize to the same session.
            val secondAttempt = assertFailsWith<McpException> { client.definitions() }
            assertTrue(
                secondAttempt.message.orEmpty().contains("will not be retried"),
                "unexpected message: ${secondAttempt.message}",
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `propagates cancellation from a tool call instead of returning an error string`() = runBlocking {
        assumeTrue(python3Available(), "python3 not available; skipping MCP stdio integration test")

        val script = File(javaClass.classLoader.getResource("fake_mcp_server.py")!!.toURI())
        val client = McpStdioClient(
            "fake",
            McpServerConfig(command = "python3", args = listOf(script.absolutePath)),
            requestTimeoutMillis = 10_000,
        )
        try {
            var result: String? = null
            var caughtCancellation = false
            val job = launch {
                try {
                    result = client.execute("fake__hang_forever", "{}")
                } catch (e: CancellationException) {
                    caughtCancellation = true
                    throw e
                }
            }
            delay(200) // let the call actually reach the server and register in `pending`
            job.cancelAndJoin()

            assertTrue(caughtCancellation, "cancellation must propagate out of execute(), not become a result string")
            assertEquals(null, result)
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
