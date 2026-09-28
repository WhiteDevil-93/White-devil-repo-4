package com.whitedevil.veniceagent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File

object McpServerLoader {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads an optional Claude-Desktop-style config file:
     * ```json
     * { "mcpServers": { "name": { "command": "npx", "args": ["-y", "some-server"] } } }
     * ```
     * Returns one [McpStdioClient] per entry, or an empty list if [configFile] doesn't exist.
     * Each server entry is parsed independently, so one malformed entry (a missing `command`,
     * a wrong-typed field) is reported and skipped rather than losing every other valid server
     * in the file; a server that fails to launch is likewise reported on stderr and skipped
     * rather than aborting the whole agent.
     */
    fun load(configFile: File): List<McpStdioClient> {
        if (!configFile.exists()) return emptyList()

        val root = runCatching { json.parseToJsonElement(configFile.readText()) }.getOrElse {
            System.err.println("Warning: failed to parse ${configFile.path}: ${it.message}")
            return emptyList()
        }
        val servers = (root as? JsonObject)?.get("mcpServers") as? JsonObject ?: return emptyList()

        return servers.mapNotNull { (name, configElement) ->
            val config = runCatching {
                json.decodeFromJsonElement<McpServerConfig>(configElement)
            }.getOrElse {
                System.err.println("Warning: invalid config for MCP server '$name', skipping it: ${it.message}")
                return@mapNotNull null
            }
            runCatching { McpStdioClient(name, config) }
                .onFailure { System.err.println("Warning: failed to start MCP server '$name': ${it.message}") }
                .getOrNull()
        }
    }
}
