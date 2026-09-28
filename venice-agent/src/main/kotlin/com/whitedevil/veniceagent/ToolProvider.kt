package com.whitedevil.veniceagent

/**
 * A pluggable source of tools the agent can call. Built-in tools (see [ToolBox]) and MCP
 * servers (see `mcp/McpStdioClient.kt`) both implement this so [ToolRegistry] can treat them
 * uniformly.
 */
interface ToolProvider {
    suspend fun definitions(): List<ToolDefinition>

    suspend fun execute(name: String, argumentsJson: String): String

    fun close() {}
}
