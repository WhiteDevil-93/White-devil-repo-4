package com.whitedevil.veniceagent.mcp

import com.whitedevil.agent.ToolDefinition
import com.whitedevil.agent.ToolFunctionSpec

import com.whitedevil.veniceagent.ToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.TimeUnit
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
        // Cleanup must run whether this loop ends normally (EOF) or a handler throws partway
        // through (e.g. handleIncomingRequest's pong write fails because stdin just closed):
        // either way the response loop is now permanently dead, and skipping cleanup would leave
        // stale cached tools advertised and in-flight calls waiting out their full timeout instead
        // of failing fast.
        try {
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
        } catch (e: Exception) {
            System.err.println("Warning: MCP server '$serverName' response loop died: ${e.message}")
        } finally {
            // The stream closed, or a handler threw: the process exited, its stdout pipe broke,
            // or writing back to it failed. Treat this like an invalidation so the next
            // definitions() call re-evaluates this provider instead of ToolRegistry trusting a
            // stale cache backed by a dead response loop forever.
            changeVersion.incrementAndGet()
            // No response can ever arrive for any call still waiting: fail them now instead of
            // leaving each one to block until its own timeout expires.
            val deadProcess = McpException("MCP server '$serverName' process exited or its response loop died")
            pending.keys.toList().forEach { id -> pending.remove(id)?.completeExceptionally(deadProcess) }
        }
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
            // Fired on this client's own scope through the same bounded/watchdog-protected path
            // as a request write, not written inline: handleIncomingRequest runs on the sole
            // response-loop thread, so a write that blocks (the server floods pings without
            // draining its own stdin) would otherwise wedge the whole loop -- no further
            // responses could ever be dispatched, and a blocked write never throws, so the
            // loop's own exception-triggered cleanup couldn't run either.
            scope.launch {
                runCatching { writeLineOrKill(pong.toString()) }
            }
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
            // withTimeoutOrNull, not withTimeout: a caller wrapping this whole call in its own
            // shorter withTimeout would otherwise be indistinguishable from our own deadline —
            // both surface as TimeoutCancellationException at this level (verified: an outer
            // withTimeout firing while suspended inside an inner one is observed here as a
            // TimeoutCancellationException carrying the OUTER's message, not ours). withTimeoutOrNull
            // instead resolves that by identity: it returns null only when *this* call's own
            // requestTimeoutMillis elapses, while an outer/ambient cancellation still propagates
            // out of it as an exception rather than being swallowed as null.
            val response = withTimeoutOrNull(requestTimeoutMillis) {
                val request = JsonRpcRequest(id = id, method = method, params = params)
                writeLineOrKill(json.encodeToString(JsonRpcRequest.serializer(), request))
                deferred.await()
            }
            if (response != null) return response
            sendCancelledNotification(id, "client timed out after ${requestTimeoutMillis}ms")
            // A plain Exception (never CancellationException): this is our own deadline, an
            // ordinary request failure, not something that should be treated like the ambient
            // coroutine being cancelled by ToolRegistry/Agent's cancellation-propagation logic.
            throw McpRequestTimedOutException("MCP server '$serverName' request '$method' timed out after ${requestTimeoutMillis}ms")
        } catch (e: CancellationException) {
            // Genuine external cancellation (an outer timeout, shutdown): the server may still
            // complete this operation without knowing we gave up on it, so a retry could
            // duplicate a write/deployment. Best-effort tell it to stop.
            sendCancelledNotification(id, "client cancelled the request")
            throw e
        } finally {
            // Covers writeLine() too: if it throws (e.g. the process already died and stdin
            // is closed), pending[id] was still stored and must not be left there forever.
            pending.remove(id)
        }
    }

    /**
     * Fired on this client's own [scope], not awaited: notify() writes through the same
     * synchronized [writeLine] as the original request, and if THAT write is what's stuck (the
     * server stopped draining stdin), awaiting it inline would block the caller on the same lock
     * until writeLineOrKill's watchdog eventually kills the process — holding up the caller's
     * cancelAndJoin() for up to the full deadline instead of returning promptly. A failure
     * writing it (e.g. the process already died) is swallowed: it's best-effort.
     */
    private fun sendCancelledNotification(requestId: Long, reason: String) {
        scope.launch {
            runCatching {
                notify(
                    "notifications/cancelled",
                    buildJsonObject {
                        put("requestId", requestId)
                        put("reason", reason)
                    },
                )
            }
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

    /**
     * [McpRequestTimedOutException] means this one optional MCP server is unresponsive — a
     * provider failure, no different from a malformed response — not that the caller's whole
     * operation should be cancelled. Converting it to [McpException] here keeps [ToolRegistry]
     * able to skip just this provider (its own `catch (e: CancellationException) { throw e }`
     * would otherwise treat a raw cancellation-shaped timeout exactly like genuine external
     * cancellation and abort the whole registry build) — but [McpRequestTimedOutException] is a
     * plain [Exception], never [CancellationException], so that confusion can't happen regardless.
     */
    override suspend fun definitions(): List<ToolDefinition> =
        try {
            definitionsInternal()
        } catch (e: McpRequestTimedOutException) {
            throw McpException(e.message ?: "MCP server '$serverName' timed out")
        }

    private suspend fun definitionsInternal(): List<ToolDefinition> {
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
            // A non-object member (null, a string, ...) is a failed page too, not an entry to
            // skip: silently dropping it would commit a partial list and never retry the provider.
            tools.forEach { element ->
                rawTools.add(
                    element as? JsonObject
                        ?: throw McpException("MCP server '$serverName' tools/list returned a non-object tool entry"),
                )
            }
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
        val definitions = rawTools.map { tool ->
            // A tool entry missing a name, or with a malformed inputSchema, is a failed page too
            // (see the comment above): silently dropping the entry or substituting a generic
            // schema would commit an incomplete or inaccurate tool list instead of retrying.
            val name = (tool["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw McpException("MCP server '$serverName' tools/list returned a tool entry with a missing or malformed 'name'")
            val description = (tool["description"] as? JsonPrimitive)?.contentOrNull ?: ""
            val schema = tool["inputSchema"] as? JsonObject
                ?: throw McpException("MCP server '$serverName' tools/list returned a malformed 'inputSchema' for tool '$name'")
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

    // executeInternal()'s own catch around call("tools/call", ...) already turns a plain
    // Exception into an "Error: ..." string, which McpRequestTimedOutException is — but
    // ensureInitialized() (called before that point) isn't covered by that catch, so its own
    // initialize-call timing out needs this same conversion applied uniformly here too.
    override suspend fun execute(name: String, argumentsJson: String): String =
        try {
            executeInternal(name, argumentsJson)
        } catch (e: McpRequestTimedOutException) {
            "Error: ${e.message}"
        }

    private suspend fun executeInternal(name: String, argumentsJson: String): String {
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
        // Snapshot descendants (a launcher command, e.g. a wrapper script or `npx`, may have
        // spawned its own child process that doesn't die when the launcher does) BEFORE any
        // operation that could make the launcher exit -- including closing its stdin just below,
        // which a well-behaved server treats as a shutdown signal exactly like destroy() does.
        // Once the launcher exits, an orphaned child gets reparented (typically to init) and
        // process.descendants() queried afterward would no longer find it.
        val descendantsBeforeDestroy = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
        runCatching { writer.close() }
        process.destroy()
        // destroy() only requests termination; a server that ignores stdin EOF (or traps the
        // termination signal) would otherwise keep running past this call returning. Wait
        // briefly, then escalate.
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        // Destroying an already-exited handle is a harmless no-op.
        runCatching { descendantsBeforeDestroy.forEach { it.destroyForcibly() } }
        scope.cancel()
    }
}

class McpException(message: String) : Exception(message)

/**
 * Thrown internally by [McpStdioClient.call] when its own `withTimeoutOrNull(requestTimeoutMillis)`
 * returns null. Deliberately a plain [Exception], never a [kotlinx.coroutines.CancellationException]:
 * that's what lets callers convert it into an ordinary failure (an [McpException] from
 * `definitions()`, an error string from `execute()`) without any risk of it being confused with
 * genuine external cancellation, which propagates through `call()` unchanged instead.
 */
private class McpRequestTimedOutException(message: String) : Exception(message)
