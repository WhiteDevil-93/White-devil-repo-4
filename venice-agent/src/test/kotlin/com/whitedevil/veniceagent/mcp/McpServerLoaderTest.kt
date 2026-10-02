package com.whitedevil.veniceagent.mcp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class McpServerLoaderTest {

    /**
     * McpStdioClient's constructor really does ProcessBuilder(...).start(), so the "good" entry
     * needs a command that exists wherever the tests run. This used to be "true", a Unix-only
     * binary: on Windows the launch failed, that entry was skipped exactly like the malformed
     * one, and the assertion saw an empty list — the test failed for a reason that had nothing
     * to do with what it is checking. The JVM running this test is a real executable on every
     * platform and already carries the right extension, so it stands in for "any command that
     * launches" without branching on the OS. It is never spoken to (no handshake happens in the
     * constructor), so it does not need to behave like an MCP server.
     */
    private fun launchableCommand(): String =
        ProcessHandle.current().info().command().orElseGet {
            val exe = if (System.getProperty("os.name").orEmpty().startsWith("Windows", true)) "java.exe" else "java"
            File(File(System.getProperty("java.home"), "bin"), exe).path
        }

    @Test
    fun `skips a malformed server entry instead of dropping the whole file`() {
        // Backslashes in a Windows path are JSON escape characters; a raw one corrupts the file.
        val command = launchableCommand().replace("\\", "\\\\")
        val configFile = File.createTempFile("mcp-servers", ".json").apply {
            deleteOnExit()
            writeText(
                """
                {
                  "mcpServers": {
                    "broken": { "args": ["missing-the-required-command-field"] },
                    "good": { "command": "$command", "args": ["-version"] }
                  }
                }
                """.trimIndent(),
            )
        }

        val clients = McpServerLoader.load(configFile)
        try {
            assertEquals(listOf("good"), clients.map { it.serverName }, "the malformed entry should be skipped, not the whole file")
        } finally {
            clients.forEach { it.close() }
        }
    }
}
