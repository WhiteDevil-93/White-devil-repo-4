package com.whitedevil.desktop.mcp

import com.whitedevil.agent.ToolDefinition
import com.whitedevil.agent.ToolExtension
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The laptop's MCP connectors. Servers come from a Claude-Desktop-style file (mcp-servers.json next to the settings)
 * and are started the first time the agent needs them, then kept for the life of the app. Reload after editing the file.
 */
class McpHost(private val file: File) : ToolExtension, AutoCloseable {
    private var registry: ToolRegistry? = null
    private var clients: List<McpStdioClient> = emptyList()

    @Synchronized private fun reg(): ToolRegistry =
        registry ?: run {
            clients = McpServerLoader.load(file)
            ToolRegistry(clients).also { registry = it }
        }

    @Synchronized override fun definitions(): List<ToolDefinition> =
        if (!file.exists()) emptyList() else runCatching { runBlocking { reg().definitions() } }.getOrDefault(emptyList())

    override fun handles(name: String) = definitions().any { it.function.name == name }

    override fun execute(name: String, argumentsJson: String): String =
        runBlocking { reg().execute(name, argumentsJson) }

    /** (server name, number of tools) for every server that started; used by the Connectors panel. */
    fun status(): List<Pair<String, Int>> {
        val defs = definitions()
        return clients.map { c -> c.serverName to defs.count { it.function.name.startsWith(c.serverName.replace(Regex("[^a-zA-Z0-9_-]"), "_") + "__") } }
    }

    @Synchronized fun reload() { close() }

    @Synchronized override fun close() {
        registry?.close(); registry = null; clients = emptyList()
    }

    fun configText(): String = if (file.exists()) file.readText() else EXAMPLE

    fun saveConfig(text: String): String? {
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) }.onFailure { return "That is not valid JSON: ${it.message?.take(120)}" }
        file.parentFile?.mkdirs(); file.writeText(text); reload(); return null
    }

    companion object {
        const val EXAMPLE = """{
  "mcpServers": {
    "fetch": { "command": "uvx", "args": ["mcp-server-fetch"] }
  }
}"""
    }
}
