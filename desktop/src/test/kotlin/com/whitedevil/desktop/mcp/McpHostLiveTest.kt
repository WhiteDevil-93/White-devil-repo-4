package com.whitedevil.desktop.mcp

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs a real connector config through the app's own McpHost. Skipped unless MCP_LIVE_CONFIG points at a
 * mcp-servers.json (it starts the servers listed there and only lists their tools; it reads no data).
 */
class McpHostLiveTest {
    @Test fun `every configured connector starts and lists tools through the app's code path`() {
        val path = System.getenv("MCP_LIVE_CONFIG")
        assumeTrue(!path.isNullOrBlank(), "MCP_LIVE_CONFIG not set; skipping live connector test")
        val host = McpHost(File(path!!))
        try {
            val started = System.currentTimeMillis()
            val status = host.status()
            val took = System.currentTimeMillis() - started
            println("LIVE MCP: ${status.joinToString { "${it.name}: ${it.tools} tools, error=${it.error}" }} in ${took} ms")
            assertTrue(status.isNotEmpty(), "no server started")
            status.forEach { assertNull(it.error, "${it.name} failed: ${it.error}"); assertTrue(it.tools > 0, "${it.name} listed no tools") }
        } finally { host.close() }
    }
}
