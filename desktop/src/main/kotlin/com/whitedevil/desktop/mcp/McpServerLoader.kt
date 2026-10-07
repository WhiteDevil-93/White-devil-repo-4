package com.whitedevil.desktop.mcp

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
    fun load(configFile: File, requestTimeoutMillis: Long = 30_000): List<McpStdioClient> {
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
            runCatching { McpStdioClient(name, forThisOs(config), requestTimeoutMillis) }
                .onFailure { System.err.println("Warning: failed to start MCP server '$name': ${it.message}") }
                .getOrNull()
        }
    }

    /**
     * On Windows, npx / uvx / npm are .cmd shims that ProcessBuilder cannot start by bare name; run them through cmd.
     */
    internal fun forThisOs(c: McpServerConfig, windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")): McpServerConfig {
        val bare = !c.command.contains('.') && !c.command.contains('/') && !c.command.contains('\\')
        return if (windows && bare) c.copy(command = "cmd", args = listOf("/c", c.command) + c.args) else c
    }
}
