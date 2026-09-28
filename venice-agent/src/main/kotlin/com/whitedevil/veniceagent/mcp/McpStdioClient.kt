package com.whitedevil.veniceagent.mcp

import com.whitedevil.veniceagent.ToolDefinition
import com.whitedevil.veniceagent.ToolFunctionSpec
import com.whitedevil.veniceagent.ToolProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A [ToolProvider] backed by a local MCP server, launched as a subprocess and spoken to over
 * newline-delimited JSON-RPC 2.0 on stdin/stdout (the MCP "stdio" transport). Tool names are
 * namespaced as `<serverName>__<toolName>` to avoid collisions with other providers.
 */
class McpStdioClient(
    val serverName: String,
    config: McpServerConfig,
    private val requestTimeoutMillis: Long = 30_000,
) : ToolProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val process = ProcessBuilder(listOf(config.command) + config.args)
        .also { it.environment().putAll(config.env) }
        .redirectErrorStream(false)
        .start()

    private val writer = process.outputStream.bufferedWriter()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonRpcResponse>>()
    private val nextId = AtomicLong(1)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var cachedDefinitions: List<ToolDefinition>? = null
    private var initialized = false

    init {
        scope.launch { readLoop() }
        scope.launch { drainStderr() }
    }

    private fun readLoop() {
        process.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val response = runCatching { json.decodeFromString(JsonRpcResponse.serializer(), line) }.getOrNull()
                    ?: continue
                val id = (response.id as? JsonPrimitive)?.longOrNull ?: continue
                pending.remove(id)?.complete(response)
            }
        }
    }

    private fun drainStderr() {
        process.errorStream.bufferedReader().forEachLine { /* MCP servers may log diagnostics here; discarded */ }
    }

    private suspend fun call(method: String, params: JsonObject?): JsonRpcResponse {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonRpcResponse>()
        pending[id] = deferred
        val request = JsonRpcRequest(id = id, method = method, params = params)
        writeLine(json.encodeToString(JsonRpcRequest.serializer(), request))
        return try {
            withTimeout(requestTimeoutMillis) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun notify(method: String, params: JsonObject?) {
        val notification = JsonRpcNotification(method = method, params = params)
        writeLine(json.encodeToString(JsonRpcNotification.serializer(), notification))
    }

    @Synchronized
    private fun writeLine(line: String) {
        writer.write(line)
        writer.write("\n")
        writer.flush()
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        val params = buildJsonObject {
            put("protocolVersion", "2024-11-05")
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "venice-agent")
                put("version", "0.1.0")
            }
        }
        val response = call("initialize", params)
        response.error?.let { throw McpException("MCP server '$serverName' failed to initialize: ${it.message}") }
        notify("notifications/initialized", null)
        initialized = true
    }

    override suspend fun definitions(): List<ToolDefinition> {
        cachedDefinitions?.let { return it }
        ensureInitialized()

        val response = call("tools/list", null)
        response.error?.let { throw McpException("MCP server '$serverName' tools/list failed: ${it.message}") }
        val tools = (response.result as? JsonObject)?.get("tools") as? JsonArray ?: JsonArray(emptyList())

        val definitions = tools.mapNotNull { element ->
            val tool = element as? JsonObject ?: return@mapNotNull null
            val name = (tool["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val description = (tool["description"] as? JsonPrimitive)?.contentOrNull ?: ""
            val schema = (tool["inputSchema"] as? JsonObject) ?: buildJsonObject { put("type", "object") }
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = namespacedName(name),
                    description = description,
                    parameters = schema,
                ),
            )
        }
        cachedDefinitions = definitions
        return definitions
    }

    override suspend fun execute(name: String, argumentsJson: String): String {
        ensureInitialized()
        val arguments = runCatching { json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull()
            ?: buildJsonObject {}
        val params = buildJsonObject {
            put("name", originalName(name))
            put("arguments", arguments)
        }
        val response = try {
            call("tools/call", params)
        } catch (e: Exception) {
            return "Error: MCP tool call to '$name' failed: ${e.message}"
        }
        response.error?.let { return "Error: MCP tool '$name' returned an error: ${it.message}" }

        val result = response.result as? JsonObject ?: return "(no result)"
        val content = result["content"] as? JsonArray ?: return result.toString()
        return content.joinToString("\n") { item ->
            val obj = item as? JsonObject
            (obj?.get("text") as? JsonPrimitive)?.contentOrNull ?: obj.toString()
        }
    }

    private fun namespacedName(toolName: String) = "${serverName}__$toolName"

    private fun originalName(namespacedName: String) = namespacedName.removePrefix("${serverName}__")

    override fun close() {
        runCatching { writer.close() }
        process.destroy()
        scope.cancel()
    }
}

class McpException(message: String) : Exception(message)
