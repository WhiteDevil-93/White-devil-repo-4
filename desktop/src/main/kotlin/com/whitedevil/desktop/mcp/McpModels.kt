package com.whitedevil.desktop.mcp

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcRequest(
    // Must be on the wire: the client's Json has encodeDefaults = false, which would otherwise drop it, and real
    // MCP servers ignore messages that lack "jsonrpc":"2.0".
    @EncodeDefault val jsonrpc: String = "2.0",
    val id: Long,
    val method: String,
    val params: JsonElement? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcNotification(
    // Must be on the wire: the client's Json has encodeDefaults = false, which would otherwise drop it, and real
    // MCP servers ignore messages that lack "jsonrpc":"2.0".
    @EncodeDefault val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class JsonRpcResponse(
    // Must be on the wire: the client's Json has encodeDefaults = false, which would otherwise drop it, and real
    // MCP servers ignore messages that lack "jsonrpc":"2.0".
    @EncodeDefault val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

@Serializable
data class McpServersFile(
    @SerialName("mcpServers") val mcpServers: Map<String, McpServerConfig> = emptyMap(),
)

@Serializable
data class McpServerConfig(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
)
