package com.whitedevil.desktop.mcp

import com.whitedevil.agent.ToolDefinition
import com.whitedevil.agent.ToolExecution
import com.whitedevil.agent.ToolExtension
import kotlin.concurrent.withLock
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
class McpHost(private val file: File, val gate: ApprovalGate = ApprovalGate(File(file.parentFile, "venice-actions.log"))) : ToolExtension, AutoCloseable {
    /** Whether the chosen Venice model can see images; when false, screenshots are replaced by a note. */
    @Volatile var visionEnabled: () -> Boolean = { false }
    private val lock = ReentrantLock()
    private var registry: ToolRegistry? = null
    private var clients: List<McpStdioClient> = emptyList()
    private var filters: Map<String, Filter> = emptyMap()
    private var askBefore: Map<String, List<Regex>> = emptyMap()
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
            askBefore = readAskBefore(file)
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

    override fun execute(name: String, argumentsJson: String): String = executeDetailed(name, argumentsJson).text

    /**
     * Runs one tool. A tool listed in its server's `askBefore` waits for your yes first (see [ApprovalGate]); the lock
     * is not held while waiting, so the Connectors panel stays usable. Images in the result go to a vision model,
     * downscaled; a model that cannot see gets a note instead.
     */
    override fun executeDetailed(name: String, argumentsJson: String): ToolExecution {
        if (!handles(name)) return ToolExecution("Error: unknown tool '$name'.")
        val sep = name.indexOf("__")
        if (sep > 0) {
            val server = name.substring(0, sep); val tool = name.substring(sep + 2)
            if (askBefore[server].orEmpty().any { it.matches(tool) } && !gate.ask(server, tool, argumentsJson)) {
                return ToolExecution("Error: the user did not allow $tool. Do not retry it; say what you wanted to do and ask them.")
            }
        }
        val raw = lock.withLock { runBlocking { reg().execute(name, argumentsJson) } }
        val (text, images) = split(raw)
        if (images.isEmpty()) return ToolExecution(text)
        if (!visionEnabled()) {
            return ToolExecution("$text\n[${images.size} screenshot(s) not shown: the chosen Venice model cannot see images. " +
                "Use text snapshots instead (Kimi CU get_app_state mode \"text\" or \"ax\"; Playwright browser_snapshot), or pick a vision model.]")
        }
        return ToolExecution(text, images.mapNotNull(::shrink).take(MAX_IMAGES))
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
        const val MAX_IMAGES = 2
        private const val MAX_SIDE = 1280

        /** Pulls the image markers McpStdioClient put in the text out into data URLs. */
        internal fun split(raw: String): Pair<String, List<String>> {
            val images = mutableListOf<String>()
            val text = StringBuilder()
            var at = 0
            while (true) {
                val start = raw.indexOf(IMAGE_START, at)
                if (start < 0) { text.append(raw, at, raw.length); break }
                val end = raw.indexOf(IMAGE_END, start + IMAGE_START.length)
                if (end < 0) { text.append(raw, at, raw.length); break }
                text.append(raw, at, start).append("[screenshot ${images.size + 1}]")
                images += raw.substring(start + IMAGE_START.length, end)
                at = end + IMAGE_END.length
            }
            return text.toString() to images
        }

        /** A screenshot at most 1280 px on its long side, as JPEG, so a few of them fit in the context. */
        internal fun shrink(dataUrl: String): String? = runCatching {
            val bytes = java.util.Base64.getDecoder().decode(dataUrl.substringAfter("base64,"))
            val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: return dataUrl
            val scale = minOf(1.0, MAX_SIDE.toDouble() / maxOf(img.width, img.height))
            val w = maxOf(1, (img.width * scale).toInt()); val h = maxOf(1, (img.height * scale).toInt())
            val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            out.createGraphics().apply {
                setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(img, 0, 0, w, h, null); dispose()
            }
            val buf = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(out, "jpg", buf)
            "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(buf.toByteArray())
        }.getOrNull()

        /** server name (as the model sees it) -> its askBefore globs. */
        internal fun readAskBefore(file: File): Map<String, List<Regex>> = runCatching {
            val servers = (Json.parseToJsonElement(file.readText()) as JsonObject)["mcpServers"] as JsonObject
            servers.mapNotNull { (name, cfg) ->
                val list = ((cfg as? JsonObject)?.get("askBefore") as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }.map(::glob)
                if (list.isEmpty()) null else name.replace(Regex("[^a-zA-Z0-9_-]"), "_") to list
            }.toMap()
        }.getOrDefault(emptyMap())
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
