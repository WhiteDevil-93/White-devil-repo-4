package com.whitedevil.veniceagent.mcp

import com.whitedevil.veniceagent.ToolDefinition
import com.whitedevil.veniceagent.ToolFunctionSpec
import com.whitedevil.veniceagent.ToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

/** Safety net against a buggy server whose nextCursor never terminates and never repeats either. */
private const val MAX_TOOLS_LIST_PAGES = 1000

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

    // Once initialize has failed once (including timing out), the server may still be about to
    // process or reply to that request: sending a second initialize on the same session risks
    // violating the MCP lifecycle. Retire the connection instead of ever retrying initialize on it.
    @Volatile
    private var permanentlyFailed = false

    // A monotonic counter of tool-list invalidations: bumped on notifications/tools/list_changed
    // and when the subprocess exits. [fetchedVersion] records which version definitions() last
    // successfully fetched, so hasChanged() (their inequality) survives both a failed refresh
    // attempt (fetchedVersion is simply never advanced) and a new invalidation arriving *during*
    // an in-flight refresh (changeVersion moves past whatever version that refresh started at).
    private val changeVersion = AtomicLong(0)

    @Volatile
    private var fetchedVersion = -1L

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
        // The stream closed: the process exited or its stdout pipe broke. Treat this like an
        // invalidation so the next definitions() call re-evaluates this provider instead of
        // ToolRegistry trusting a stale cache backed by a dead process forever.
        changeVersion.incrementAndGet()
        // No response can ever arrive for any call still waiting: fail them now instead of
        // leaving each one to block until its own 30s timeout expires.
        val deadProcess = McpException("MCP server '$serverName' process exited before responding")
        pending.keys.toList().forEach { id -> pending.remove(id)?.completeExceptionally(deadProcess) }
    }

    /** Handles server-to-client requests/notifications (distinct from responses to our own calls). */
    private fun handleIncomingRequest(element: JsonObject) {
        val method = (element["method"] as? JsonPrimitive)?.contentOrNull ?: return
        if (method == "notifications/tools/list_changed") {
            changeVersion.incrementAndGet()
            return
        }
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
        try {
            return withTimeout(requestTimeoutMillis) {
                val request = JsonRpcRequest(id = id, method = method, params = params)
                writeLineOrKill(json.encodeToString(JsonRpcRequest.serializer(), request))
                deferred.await()
            }
        } catch (e: CancellationException) {
            // Covers our own timeout (TimeoutCancellationException, a subtype) and a caller
            // cancelling us from outside (an outer timeout, shutdown): either way, the server
            // may still complete this operation without knowing we gave up on it, so a retry
            // could duplicate a write/deployment. Best-effort tell it to stop: if the notification
            // itself can't be written (e.g. the process just died and stdin is now closed), that
            // failure must not replace the cancellation we're about to (re)throw.
            runCatching {
                notify(
                    "notifications/cancelled",
                    buildJsonObject {
                        put("requestId", id)
                        put(
                            "reason",
                            if (e is TimeoutCancellationException) {
                                "client timed out after ${requestTimeoutMillis}ms"
                            } else {
                                "client cancelled the request"
                            },
                        )
                    },
                )
            }
            throw e
        } finally {
            // Covers writeLine() too: if it throws (e.g. the process already died and stdin
            // is closed), pending[id] was still stored and must not be left there forever.
            pending.remove(id)
        }
    }

    private fun notify(method: String, params: JsonObject?) {
        val notification = JsonRpcNotification(method = method, params = params)
        writeLine(json.encodeToString(JsonRpcNotification.serializer(), notification))
    }

    /**
     * Writes [line] and waits for it to finish, but — unlike plain `withContext(Dispatchers.IO)`
     * — doesn't let a blocked write (the subprocess stopped reading stdin, so the pipe fills)
     * stall past [requestTimeoutMillis]. `withTimeout` cancellation alone can't achieve this: the
     * underlying `BufferedWriter.write`/`flush` are blocking Java I/O that ignore coroutine
     * cancellation, so a structured child coroutine running them would keep `withTimeout` waiting
     * for it to return before it could ever propagate.
     *
     * Both the write and its watchdog run on this client's own independent [scope], set up
     * *before* the only suspension point ([writeJob]'s `.await()`) — so if the caller's own
     * timeout or cancellation reaches that suspension point first, only the caller's wait ends;
     * the watchdog keeps running regardless (it's cancelled solely by [writeJob] completing, via
     * `invokeOnCompletion`, never by the caller giving up) and still forcibly kills the subprocess
     * if the write hasn't finished by the deadline. That's what actually unblocks it (closing just
     * the stream doesn't reliably interrupt an in-progress blocked write, but the read end of the
     * pipe closing does) — without it, the write would hold [writeLine]'s lock forever and wedge
     * every future call on this client too.
     */
    private suspend fun writeLineOrKill(line: String) {
        val writeJob = scope.async(Dispatchers.IO) { writeLine(line) }
        val watchdog = scope.launch {
            delay(requestTimeoutMillis)
            if (writeJob.isActive) {
                System.err.println(
                    "Warning: MCP server '$serverName' stopped reading stdin; killing it to unblock the write.",
                )
                process.destroyForcibly()
            }
        }
        writeJob.invokeOnCompletion { watchdog.cancel() }
        writeJob.await()
    }

    @Synchronized
    private fun writeLine(line: String) {
        writer.write(line)
        writer.write("\n")
        writer.flush()
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        if (permanentlyFailed) {
            throw McpException("MCP server '$serverName' failed to initialize earlier and will not be retried")
        }
        try {
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
        } catch (e: Exception) {
            // Whatever failed (a rejected/malformed response, or the initialize call itself
            // timing out), don't leave this session in limbo to be retried: close it now.
            permanentlyFailed = true
            close()
            throw e
        }
    }

    override fun hasChanged(): Boolean = changeVersion.get() != fetchedVersion

    override suspend fun definitions(): List<ToolDefinition> {
        if (cachedDefinitions != null && !hasChanged()) return cachedDefinitions!!
        val versionAtFetchStart = changeVersion.get()
        ensureInitialized()

        val rawTools = mutableListOf<JsonObject>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var pageCount = 0
        do {
            if (++pageCount > MAX_TOOLS_LIST_PAGES) {
                throw McpException("MCP server '$serverName' tools/list did not terminate after $MAX_TOOLS_LIST_PAGES pages")
            }
            val params = cursor?.let { buildJsonObject { put("cursor", it) } }
            val response = call("tools/list", params)
            response.error?.let { throw McpException("MCP server '$serverName' tools/list failed: ${it.message}") }
            // A malformed/missing result is a failed page, not the natural end of pagination:
            // throwing here (instead of silently stopping) lets the caller's "only commit on
            // full success" logic keep the previous cache and invalidation for a retry.
            val result = response.result as? JsonObject
                ?: throw McpException("MCP server '$serverName' tools/list returned a malformed result")
            // A missing/wrong-typed "tools" is a failed page too, not "zero tools this page":
            // treating it as empty would silently commit a truncated list as if it were complete.
            val tools = result["tools"] as? JsonArray
                ?: throw McpException("MCP server '$serverName' tools/list result is missing a 'tools' array")
            tools.forEach { (it as? JsonObject)?.let(rawTools::add) }
            val cursorElement = result["nextCursor"]
            val nextCursor = when {
                cursorElement == null || cursorElement is JsonNull -> null
                cursorElement is JsonPrimitive && cursorElement.isString -> cursorElement.content
                else -> throw McpException("MCP server '$serverName' tools/list returned a malformed nextCursor")
            }
            if (nextCursor != null && !seenCursors.add(nextCursor)) {
                throw McpException("MCP server '$serverName' tools/list returned a repeated cursor '$nextCursor'")
            }
            cursor = nextCursor
        } while (cursor != null)

        // Build into fresh local state and only commit once every page has been fetched
        // successfully. If tools/list throws partway through (a timeout, a bad page), the
        // previous cache, mapping, and "changed" flag are left untouched, so a stale result
        // is never served and the next call retries the refresh instead of silently going stale.
        val newExposedToOriginal = mutableMapOf<String, String>()
        val definitions = rawTools.mapNotNull { tool ->
            val name = (tool["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val description = (tool["description"] as? JsonPrimitive)?.contentOrNull ?: ""
            val schema = (tool["inputSchema"] as? JsonObject) ?: buildJsonObject { put("type", "object") }
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = namespacedName(name, newExposedToOriginal),
                    description = description,
                    parameters = schema,
                ),
            )
        }

        exposedToOriginal.clear()
        exposedToOriginal.putAll(newExposedToOriginal)
        cachedDefinitions = definitions
        // Only advance to the version this fetch actually captured: if a newer invalidation
        // arrived while we were fetching, changeVersion has already moved past it, so
        // hasChanged() correctly stays true and the next call refreshes again.
        fetchedVersion = versionAtFetchStart
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
        } catch (e: CancellationException) {
            throw e // never swallow cancellation as an ordinary tool failure
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
            "resource" -> renderResourceContent(obj)
            else -> "[unsupported content type]"
        }
    }

    /**
     * An embedded resource isn't necessarily binary: when it carries a textual `text` payload
     * (source files, documents, etc. returned this way), surface it like any other text content
     * instead of discarding it — only a genuinely binary `blob` resource stays omitted.
     */
    private fun renderResourceContent(obj: JsonObject): String {
        val resource = obj["resource"] as? JsonObject ?: return "[resource content, omitted]"
        val text = (resource["text"] as? JsonPrimitive)?.contentOrNull
        return text ?: "[resource content: ${mimeTypeOf(resource)}, omitted]"
    }

    private fun mimeTypeOf(obj: JsonObject): String =
        (obj["mimeType"] as? JsonPrimitive)?.contentOrNull ?: "unknown mime type"

    /**
     * Sanitizing and truncating to fit chat-completion function-name limits is lossy (e.g.
     * "foo.bar" and "foo_bar" both sanitize to "foo_bar"), so two distinct original tool names
     * can collide. When that happens, later ones get a numeric suffix so every tool this
     * provider exposes still gets a distinct, valid name.
     */
    private fun namespacedName(toolName: String, target: MutableMap<String, String>): String {
        val base = "${serverName}__$toolName".replace(Regex("[^a-zA-Z0-9_-]"), "_")
        var candidate = base.take(64)
        var suffix = 1
        while (target[candidate]?.let { it != toolName } == true) {
            val suffixText = "_$suffix"
            candidate = base.take((64 - suffixText.length).coerceAtLeast(0)) + suffixText
            suffix++
        }
        target[candidate] = toolName
        return candidate
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
