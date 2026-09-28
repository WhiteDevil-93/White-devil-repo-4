package com.whitedevil.veniceagent.mcp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class McpServerLoaderTest {

    @Test
    fun `skips a malformed server entry instead of dropping the whole file`() {
        val configFile = File.createTempFile("mcp-servers", ".json").apply {
            deleteOnExit()
            writeText(
                """
                {
                  "mcpServers": {
                    "broken": { "args": ["missing-the-required-command-field"] },
                    "good": { "command": "true", "args": [] }
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
