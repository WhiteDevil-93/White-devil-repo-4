package com.whitedevil.desktop.mcp

import com.whitedevil.agent.ToolDefinition
import com.whitedevil.agent.ToolExtension
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * The laptop's MCP connectors. Servers come from a Claude-Desktop-style file (mcp-servers.json next to the settings).
 *
 * - They are started in the background when the app opens ([warmUp]); some, like the Google Drive server, take a minute
 *   or two to load, so the agent never waits for them: a message sent before they are ready simply runs without those
 *   tools, and the next one has them.
 * - Per server, `enabledTools` (only these) and `disabledTools` (never these) trim a long tool list. Both take tool
 *   names with `*` wildcards, e.g. `"disabledTools": ["delete*", "*Permission"]`.
 * - Reload after editing the file.
 */
class McpHost(private val file: File) : ToolExtension, AutoCloseable {
    private val lock = ReentrantLock()
    private var registry: ToolRegistry? = null
    private var clients: List<McpStdioClient> = emptyList()
    private var filters: Map<String, Filter> = emptyMap()
    @Volatile private var cached: List<ToolDefinition> = emptyList()
    @Volatile private var warming = false

    internal class Filter(val enabled: List<Regex>, val disabled: List<Regex>) {
        fun allows(tool: String) = (enabled.isEmpty() || enabled.any { it.matches(tool) }) && disabled.none { it.matches(tool) }
    }

    /** Must be called with [lock] held. */
    private fun reg(): ToolRegistry =
        registry ?: run {
            // npx may have to download the server on first use, so allow far longer than a normal request.
            clients = McpServerLoader.load(file, START_TIMEOUT_MS)
            filters = readFilters(file)
            ToolRegistry(clients).also { registry = it }
        }

    /** Starts the servers and lists their tools on a background thread. Safe to call more than once. */
    fun warmUp() {
        if (!file.exists() || warming) return
        warming = true
        Thread({ try { runCatching { status() } } finally { warming = false } }, "mcp-warmup").apply { isDaemon = true }.start()
    }

    /** True while the servers are still starting. */
    val starting: Boolean get() = warming

    /**
     * The tools the agent may use right now. Never blocks the chat for long: if the servers are still starting (the lock
     * is held by the start-up), the agent gets whatever was ready last time, which is nothing on the first message.
     */
    override fun definitions(): List<ToolDefinition> {
        if (!file.exists()) return emptyList()
        if (!lock.tryLock(WAIT_MS, TimeUnit.MILLISECONDS)) return cached
        try {
            val all = runCatching { runBlocking { reg().definitions() } }.getOrDefault(emptyList())
            cached = all.filter { allowed(it.function.name) }
            return cached
        } finally { lock.unlock() }
    }

    private fun allowed(exposed: String): Boolean {
        val split = exposed.indexOf("__")
        if (split < 0) return true
        return (filters[exposed.substring(0, split)] ?: return true).allows(exposed.substring(split + 2))
    }

    override fun handles(name: String) = cached.any { it.function.name == name } || definitions().any { it.function.name == name }

    override fun execute(name: String, argumentsJson: String): String {
        if (!handles(name)) return "Error: unknown tool '$name'."
        lock.lock()
        try { return runBlocking { reg().execute(name, argumentsJson) } } finally { lock.unlock() }
    }

    /** One server's state for the Connectors panel: how many tools it offers, or why it could not list them. */
    data class ServerStatus(val name: String, val tools: Int, val error: String?)

    /** Starts the configured servers if needed and reports each one separately, with the real error when it fails. */
    fun status(): List<ServerStatus> {
        if (!file.exists()) return emptyList()
        lock.lock()
        try {
            reg()
            val out = clients.map { c ->
                try {
                    val key = c.serverName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                    val tools = runBlocking { c.definitions() }.count { filters[key]?.allows(it.function.name.removePrefix(key + "__")) ?: true }
                    ServerStatus(c.serverName, tools, null)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ServerStatus(c.serverName, 0, e.message ?: e.javaClass.simpleName)
                }
            }
            cached = runCatching { runBlocking { reg().definitions() } }.getOrDefault(emptyList()).filter { allowed(it.function.name) }
            return out
        } finally { lock.unlock() }
    }

    /** The server names in the config file (for @mentions); does not start anything. */
    fun serverNames(): List<String> = runCatching {
        ((Json.parseToJsonElement(file.readText()) as JsonObject)["mcpServers"] as JsonObject).keys.toList()
    }.getOrDefault(emptyList())

    fun reload() { close() }

    override fun close() {
        // Do not wait for a start that is still running: closing kills the subprocesses, which ends it.
        val got = lock.tryLock(500, TimeUnit.MILLISECONDS)
        try { registry?.close(); registry = null; clients = emptyList(); cached = emptyList() } finally { if (got) lock.unlock() }
    }

    fun configText(): String = if (file.exists()) file.readText() else EXAMPLE

    fun saveConfig(text: String): String? {
        runCatching { Json.parseToJsonElement(text) }.onFailure { return "That is not valid JSON: ${it.message?.take(120)}" }
        file.parentFile?.mkdirs(); file.writeText(text); reload(); return null
    }

    companion object {
        const val START_TIMEOUT_MS = 120_000L
        private const val WAIT_MS = 3_000L
        const val EXAMPLE = """{
  "mcpServers": {
    "fetch": { "command": "uvx", "args": ["mcp-server-fetch"] }
  }
}"""

        private fun glob(g: String) = Regex(g.split("*").joinToString(".*") { Regex.escape(it) })

        /** server name (as the model sees it) -> its enabledTools / disabledTools. Missing or malformed means no limits. */
        internal fun readFilters(file: File): Map<String, Filter> = runCatching {
            val servers = (Json.parseToJsonElement(file.readText()) as JsonObject)["mcpServers"] as JsonObject
            servers.mapNotNull { (name, cfg) ->
                val o = cfg as? JsonObject ?: return@mapNotNull null
                fun list(k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }.map(::glob)
                name.replace(Regex("[^a-zA-Z0-9_-]"), "_") to Filter(list("enabledTools"), list("disabledTools"))
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
