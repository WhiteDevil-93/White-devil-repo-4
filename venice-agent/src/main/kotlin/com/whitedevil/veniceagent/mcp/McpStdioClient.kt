package com.whitedevil.veniceagent.mcp

import com.whitedevil.veniceagent.ToolDefinition
import com.whitedevil.veniceagent.ToolFunctionSpec
import com.whitedevil.veniceagent.ToolProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val SUPPORTED_PROTOCOL_VERSION = "2024-11-05"

/**
 * A [ToolProvider] backed by a local MCP server, launched as a subprocess and spoken to over
 * newline-delimited JSON-RPC 2.0 on stdin/stdout (the MCP "stdio" transport). Tool names are
 * namespaced as `<serverName>__<toolName>` (sanitized to be a valid function name) to avoid
 * collisions with other providers.
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

    // Exposed (sanitized, namespaced) tool name -> original MCP tool name.
    private val exposedToOriginal = ConcurrentHashMap<String, String>()

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
                val element = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
                if (element.containsKey("method")) {
                    handleIncomingRequest(element)
                    continue
                }
                val response = runCatching { json.decodeFromString(JsonRpcResponse.serializer(), line) }.getOrNull()
                    ?: continue
                val id = (response.id as? JsonPrimitive)?.longOrNull ?: continue
                pending.remove(id)?.complete(response)
            }
        }
    }

    /** Handles server-to-client requests/notifications (distinct from responses to our own calls). */
    private fun handleIncomingRequest(element: JsonObject) {
        val method = (element["method"] as? JsonPrimitive)?.contentOrNull ?: return
        val id = element["id"] ?: return // a notification from the server; nothing to reply to
        if (method == "ping") {
            val pong = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", buildJsonObject {})
            }
            writeLine(pong.toString())
        }
        // Other server-to-client requests aren't supported by this client and are left unanswered.
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
        } catch (e: TimeoutCancellationException) {
            notify(
                "notifications/cancelled",
                buildJsonObject {
                    put("requestId", id)
                    put("reason", "client timed out after ${requestTimeoutMillis}ms")
                },
            )
            throw e
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
            put("protocolVersion", SUPPORTED_PROTOCOL_VERSION)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "venice-agent")
                put("version", "0.1.0")
            }
        }
        val response = call("initialize", params)
        response.error?.let { throw McpException("MCP server '$serverName' failed to initialize: ${it.message}") }

        val negotiatedVersion = ((response.result as? JsonObject)?.get("protocolVersion") as? JsonPrimitive)?.contentOrNull
        if (negotiatedVersion != SUPPORTED_PROTOCOL_VERSION) {
            throw McpException(
                "MCP server '$serverName' negotiated unsupported protocol version " +
                    "'$negotiatedVersion' (this client only speaks '$SUPPORTED_PROTOCOL_VERSION')",
            )
        }
        notify("notifications/initialized", null)
        initialized = true
    }

    override suspend fun definitions(): List<ToolDefinition> {
        cachedDefinitions?.let { return it }
        ensureInitialized()

        val rawTools = mutableListOf<JsonObject>()
        var cursor: String? = null
        do {
            val params = cursor?.let { buildJsonObject { put("cursor", it) } }
            val response = call("tools/list", params)
            response.error?.let { throw McpException("MCP server '$serverName' tools/list failed: ${it.message}") }
            val result = response.result as? JsonObject ?: break
            (result["tools"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(rawTools::add) }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull
        } while (cursor != null)

        val definitions = rawTools.mapNotNull { tool ->
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
        val arguments = runCatching { json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: return "Error: invalid arguments for tool '$name': expected a JSON object, got: $argumentsJson"

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
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
        val content = result["content"] as? JsonArray
        val rendered = content?.joinToString("\n") { renderContentItem(it) } ?: result.toString()
        return if (isError) "Error: MCP tool '$name' reported a failure: $rendered" else rendered
    }

    private fun renderContentItem(item: JsonElement): String {
        val obj = item as? JsonObject ?: return "[malformed content item]"
        return when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
            "text" -> (obj["text"] as? JsonPrimitive)?.contentOrNull ?: ""
            "image" -> "[image content: ${mimeTypeOf(obj)}, omitted]"
            "audio" -> "[audio content: ${mimeTypeOf(obj)}, omitted]"
            "resource" -> "[resource content, omitted]"
            else -> "[unsupported content type]"
        }
    }

    private fun mimeTypeOf(obj: JsonObject): String =
        (obj["mimeType"] as? JsonPrimitive)?.contentOrNull ?: "unknown mime type"

    private fun namespacedName(toolName: String): String {
        val sanitized = "${serverName}__$toolName".replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
        exposedToOriginal[sanitized] = toolName
        return sanitized
    }

    private fun originalName(exposedName: String): String =
        exposedToOriginal[exposedName] ?: exposedName.removePrefix("${serverName}__")

    override fun close() {
        runCatching { writer.close() }
        process.destroy()
        scope.cancel()
    }
}

class McpException(message: String) : Exception(message)
