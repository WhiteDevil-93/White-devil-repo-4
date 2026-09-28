package com.whitedevil.veniceagent.mcp

import kotlinx.serialization.json.Json
import java.io.File

object McpServerLoader {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads an optional Claude-Desktop-style config file:
     * ```json
     * { "mcpServers": { "name": { "command": "npx", "args": ["-y", "some-server"] } } }
     * ```
     * Returns one [McpStdioClient] per entry, or an empty list if [configFile] doesn't exist.
     * A server that fails to launch is reported on stderr and skipped rather than aborting
     * the whole agent.
     */
    fun load(configFile: File): List<McpStdioClient> {
        if (!configFile.exists()) return emptyList()

        val parsed = runCatching {
            json.decodeFromString(McpServersFile.serializer(), configFile.readText())
        }.getOrElse {
            System.err.println("Warning: failed to parse ${configFile.path}: ${it.message}")
            return emptyList()
        }

        return parsed.mcpServers.mapNotNull { (name, config) ->
            runCatching { McpStdioClient(name, config) }
                .onFailure { System.err.println("Warning: failed to start MCP server '$name': ${it.message}") }
                .getOrNull()
        }
    }
}
