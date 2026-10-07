package com.whitedevil.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

data class ToolExecution(
    val text: String,
    val imageDataUrls: List<String> = emptyList(),
)

/**
 * Android Device & Remote Relay/Forge Hub ToolBox:
 * 1. Sandboxed local workspace tools (`read_file`, `write_file`, `list_directory`, `delete_file`)
 * 2. Remote Forge Hub & Wan2.2 Pipeline tools:
 *    - `get_render_status`: Query active Wan2.2 5B/14B GPU rendering jobs and heartbeat status
 *    - `list_prompt_packs`: List available prompt chains and render completion counts
 *    - `run_laptop_command`: Execute a command or script on the user's WSL laptop via relay SSH bridge
 *    - `download_civitai_lora`: Invoke the Civitai mirror downloader on the laptop
 */
class ToolBox(
    private val workspaceDir: File,
    private val relayBaseUrl: String,
    private val relayUser: String,
    private val relayPass: String,
    access: AccessConfig = AccessConfig(),
    /** Memory, skills, MCP servers... Offered to the model alongside the built-in tools. */
    private val extensions: List<ToolExtension> = emptyList(),
) {
    /** Optional single extension constructor used by desktop callers. */
    constructor(
        workspaceDir: File,
        relayBaseUrl: String,
        relayUser: String,
        relayPass: String,
        extension: ToolExtension?,
    ) : this(
        workspaceDir = workspaceDir,
        relayBaseUrl = relayBaseUrl,
        relayUser = relayUser,
        relayPass = relayPass,
        access = AccessConfig(),
        extensions = listOfNotNull(extension),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** System-prompt text contributed by the extensions (memories, skill index, connected servers). */
    fun promptAddendum(): String = extensions.map { it.promptBlock() }.filter { it.isNotBlank() }.joinToString("\n\n")

    // Declared before `definitions`: that property reads it during construction.
    private val accessTools = AccessTools(access, ::runBashOnLaptop)

    init {
        workspaceDir.mkdirs()
    }

    val definitions: List<ToolDefinition> get() = buildList {
        addAll(accessTools.definitions)
        extensions.forEach { addAll(it.definitions()) }
        addAll(builtInDefinitions)
    }

    private val builtInDefinitions: List<ToolDefinition> = buildList {
        // Local device workspace filesystem tools
        add(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "read_file",
                    description = "Read the contents of a text file inside the local agent workspace.",
                    parameters = objectSchema("path" to "Path to the file, relative to the workspace root."),
                ),
            ),
        )
        add(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "write_file",
                    description = "Create or overwrite a text file inside the local agent workspace.",
                    parameters = objectSchema(
                        "path" to "Path to the file, relative to the workspace root.",
                        "content" to "Full text content to write to the file.",
                    ),
                ),
            ),
        )
        add(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "list_directory",
                    description = "List files and subdirectories inside a directory in the local agent workspace.",
                    parameters = objectSchema("path" to "Directory path, relative to the workspace root. Use \".\" for the root."),
                ),
            ),
        )
        add(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "delete_file",
                    description = "Delete a file inside the local agent workspace.",
                    parameters = objectSchema("path" to "Path to the file, relative to the workspace root."),
                ),
            ),
        )

        // Remote Forge Hub & Relay tools
        add(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "get_render_status",
                    description = "Query active Wan2.2 rendering jobs, Colab GPU status, credit usage, and laptop connection state from Forge Hub.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                    },
                ),
            ),
        )
        add(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "review_latest_render",
                    description = "Fetch the newest Forge Hub render contact sheet for visual review. Prefer when the user typed /review or explicitly asked to review a render; not ambient default work.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                    },
                ),
            ),
        )
        add(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "render_assess_adjust_cycle",
                    description = "Start, status, or stop the LTX render→assess→adjust QA cycle. Prefer when the user typed /cycle or explicitly asked for a QA cycle; not ambient default work.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("action") {
                                put("type", "string")
                                put("description", "One of: start, status, stop. Defaults to status.")
                            }
                            putJsonObject("src") {
                                put("type", "string")
                                put("description", "Optional LTX job id to continue from.")
                            }
                            putJsonObject("rounds") {
                                put("type", "integer")
                                put("description", "Optional max rounds for start (1-10). Defaults to 5.")
                            }
                        }
                    },
                ),
            ),
        )
        add(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "list_prompt_packs",
                    description = "List prompt packs in the Wan2.2 generation catalog and their render completion status from Forge Hub.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                    },
                ),
            ),
        )
        add(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "run_laptop_command",
                    description = "Execute code directly in the user's connected WSL laptop terminal through the relay SSH bridge and return stdout, stderr, exit code, and working directory. Use this proactively when the user asks you to run, test, build, inspect, or debug code on their laptop; do not merely print commands for them to copy.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("code") {
                                put("type", "string")
                                put("description", "The complete bash command/script or Python source to execute.")
                            }
                            putJsonObject("lang") {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add(JsonPrimitive("bash"))
                                    add(JsonPrimitive("python"))
                                })
                                put("description", "Execution language. Defaults to bash.")
                            }
                            putJsonObject("cwd") {
                                put("type", "string")
                                put("description", "Optional working directory relative to the laptop home, for example 'projects/my-app'. Defaults to 'venice_run'.")
                            }
                            putJsonObject("timeout_seconds") {
                                put("type", "integer")
                                put("minimum", 10)
                                put("maximum", 180)
                                put("description", "Optional execution timeout. Defaults to 90 seconds.")
                            }
                        }
                        put("required", buildJsonArray { add(JsonPrimitive("code")) })
                    },
                ),
            ),
        )
        add(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "download_civitai_lora",
                    description = "Download LoRA files from Civitai onto the laptop ~/civitai_dl folder. Pass one id or several (comma-separated). Pulls every LoRA file on every version (Wan 2.2, LTX-2, LTX-2.5), not a single LTX 2.5 file.",
                    parameters = objectSchema(
                        "model_id" to "Civitai model ID or version ID.",
                        "slug" to "Optional model slug name for file naming.",
                    ),
                ),
            ),
        )
        add(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "hub_overview",
                    description = "Sitrep for the Forge Hub domain (status, laptop, Colab, Thunder, LTX, media, term, agentic). Use when the goal involves Hub/studio ops.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                    },
                ),
            ),
        )
        add(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "remember",
                    description = "Save something durable to the Forge Hub's persistent memory, so it is remembered in every later chat on every device: a user preference (give key and value) and/or a free-form note about a decision or a project (give note). Use sparingly, for things worth keeping; never for passwords or keys.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("note") {
                                put("type", "string")
                                put("description", "A short free-form note to keep, e.g. a decision or the state of a project.")
                            }
                            putJsonObject("key") {
                                put("type", "string")
                                put("description", "Preference name, e.g. preferred_video_length.")
                            }
                            putJsonObject("value") {
                                put("type", "string")
                                put("description", "The preference's value.")
                            }
                        }
                    },
                ),
            ),
        )
        add(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "hub_request",
                    description = "Call ANY Forge Hub API under /api/* (GET/POST/PUT/DELETE). Use when the goal needs Forge Hub (one domain of this app): status, manifest, media, colab, thunder, ltx, gen, setup, term, laptop, agentic, vast, hypno. Pass JSON body as a string for POST/PUT.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("method") {
                                put("type", "string")
                                put("description", "HTTP method: GET, POST, PUT, or DELETE. Defaults to GET.")
                            }
                            putJsonObject("path") {
                                put("type", "string")
                                put("description", "Path beginning with /api/ — e.g. /api/ltx/jobs or /api/thunder/state")
                            }
                            putJsonObject("body") {
                                put("type", "string")
                                put("description", "Optional JSON object string for POST/PUT body.")
                            }
                        }
                        put("required", buildJsonArray { add(JsonPrimitive("path")) })
                    },
                ),
            ),
        )
    
        add(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "queue_gpu_render",
                    description = "Queue a render on a GPU cloud (colab, thunder, ltx, gen, vast). Prefer this over raw hub_request for starting renders.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("cloud") {
                                put("type", "string")
                                put("description", "One of: colab, thunder, ltx, gen, vast.")
                            }
                            putJsonObject("packs") {
                                put("type", "string")
                                put("description", "Colab only: comma-separated pack numbers.")
                            }
                            putJsonObject("prompt") {
                                put("type", "string")
                                put("description", "LTX only: clip prompt.")
                            }
                            putJsonObject("body_json") {
                                put("type", "string")
                                put("description", "JSON object string for thunder/ltx/gen/vast payloads.")
                            }
                        }
                        put("required", buildJsonArray { add(JsonPrimitive("cloud")) })
                    },
                ),
            ),
        )
}

    fun execute(name: String, argumentsJson: String): String =
        executeDetailed(name, argumentsJson).text

    /**
     * Runs [code] in bash on the laptop through the relay (same endpoint as run_laptop_command).
     * Only AccessTools' fixed git commands reach this; the model never supplies the shell text.
     */
    private fun runBashOnLaptop(code: String): String = relayHttp(
        "/api/laptop/run",
        method = "POST",
        postBody = buildJsonObject {
            put("lang", "bash")
            put("code", code)
            put("cwd", "venice_run")
            put("timeout", 90)
        }.toString(),
        readTimeoutMs = 120_000,
    )

    /**
     * Confirm-before-acting for the older tools that delete, run code, change the hub or spend money.
     * Only active when the host supplied a confirmation UI, so desktop/CLI behaviour is unchanged.
     * Returns a refusal message, or null if the call may proceed.
     */
    private fun gateLegacy(name: String, argumentsJson: String): String? {
        val args = try {
            json.parseToJsonElement(argumentsJson).jsonObjectOrEmpty()
        } catch (e: Exception) {
            return null // let the tool report its own bad-arguments error
        }
        val (title, detail) = when (name) {
            "delete_file" -> "Delete file" to (args.stringOrNull("path") ?: "?")
            "run_laptop_command" -> "Run on laptop" to
                "[${args.stringOrNull("lang") ?: "bash"}] ${(args.stringOrNull("code") ?: "").take(900)}"
            "hub_request" -> {
                val method = (args.stringOrNull("method") ?: "GET").trim().uppercase()
                if (method == "GET") return null
                "Change the hub" to "$method ${args.stringOrNull("path")}\n${(args.stringOrNull("body") ?: "").take(600)}"
            }
            "queue_gpu_render" -> "Queue a GPU render (spends money)" to
                "cloud=${args.stringOrNull("cloud")} ${args.stringOrNull("prompt") ?: args.stringOrNull("packs") ?: ""}".trim()
            else -> return null
        }
        return if (accessTools.approve(title, detail, requireUi = false)) null
        else "Denied: the user did not approve '$title'. Do not retry it; ask the user what they want instead."
    }

    fun executeDetailed(name: String, argumentsJson: String): ToolExecution {
        gateLegacy(name, argumentsJson)?.let { return ToolExecution(it) }
        return try {
            val result = when (name) {
                "read_file" -> ToolExecution(readFile(argumentsJson))
                "write_file" -> ToolExecution(writeFile(argumentsJson))
                "list_directory" -> ToolExecution(listDirectory(argumentsJson))
                "delete_file" -> ToolExecution(deleteFile(argumentsJson))
                "get_render_status" -> ToolExecution(getRenderStatus())
                "review_latest_render" -> reviewLatestRender()
                "render_assess_adjust_cycle" -> ToolExecution(renderAssessAdjustCycle(argumentsJson))
                "list_prompt_packs" -> ToolExecution(listPromptPacks())
                "run_laptop_command" -> ToolExecution(runLaptopCommand(argumentsJson))
                "download_civitai_lora" -> ToolExecution(downloadCivitaiLora(argumentsJson))
                "hub_overview" -> ToolExecution(hubOverview())
                "hub_request" -> ToolExecution(hubRequest(argumentsJson))
                "remember" -> ToolExecution(remember(argumentsJson))
                "queue_gpu_render" -> ToolExecution(queueGpuRender(argumentsJson))
                else -> {
                    val accessRes = accessTools.execute(name, argumentsJson)
                    if (accessRes != null) {
                        ToolExecution(accessRes)
                    } else {
                        val ext = extensions.firstOrNull { it.handles(name) }
                        if (ext != null) ext.executeDetailed(name, argumentsJson)
                        else ToolExecution("Error: unknown tool '$name'.")
                    }
                }
            }
            result
        } catch (e: Exception) {
            ToolExecution("Error: ${e.message}")
        }
    }

    private fun resolveWithinWorkspace(relativePath: String): File {
        val target = File(workspaceDir, relativePath).canonicalFile
        val root = workspaceDir.canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "Path '$relativePath' escapes the workspace directory"
        }
        return target
    }

    private fun readFile(argumentsJson: String): String {
        val args = json.parseToJsonElement(argumentsJson).jsonObjectOrEmpty()
        val path = args.stringOrNull("path") ?: return "Error: 'path' argument is required."
        val file = resolveWithinWorkspace(path)
        if (!file.exists() || !file.isFile) return "Error: file not found: $path"
        return file.readText()
    }

    private fun writeFile(argumentsJson: String): String {
        val args = json.parseToJsonElement(argumentsJson).jsonObjectOrEmpty()
        val path = args.stringOrNull("path") ?: return "Error: 'path' argument is required."
        val content = args.stringOrNull("content") ?: ""
        val file = resolveWithinWorkspace(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "Wrote ${content.length} characters to $path"
    }

    private fun deleteFile(argumentsJson: String): String {
        val args = json.parseToJsonElement(argumentsJson).jsonObjectOrEmpty()
        val path = args.stringOrNull("path") ?: return "Error: 'path' argument is required."
        val file = resolveWithinWorkspace(path)
        if (!file.exists()) return "Error: file not found: $path"
        val deleted = file.delete()
        return if (deleted) "Deleted $path" else "Failed to delete $path"
    }

    private fun listDirectory(argumentsJson: String): String {
        val args = json.parseToJsonElement(argumentsJson).jsonObjectOrEmpty()
        val path = args.stringOrNull("path") ?: "."
        val dir = resolveWithinWorkspace(path)
        if (!dir.exists() || !dir.isDirectory) return "Error: directory not found: $path"
        return dir.listFiles()
            ?.sortedBy { it.name }
            ?.joinToString("\n") { if (it.isDirectory) "${it.name}/" else it.name }
            ?.ifBlank { "(empty directory)" }
            ?: "(empty directory)"
    }

    // ---------- Remote Forge Hub & Relay Tool Handlers ----------

    private fun relayHttp(
        path: String,
        method: String = "GET",
        postBody: String? = null,
        readTimeoutMs: Int = 30000,
    ): String {
        val cleanBase = relayBaseUrl.trimEnd('/')
        if (cleanBase.isBlank()) {
            return "Error: Relay Base URL is not configured. Check settings."
        }
        val url = URI("$cleanBase$path").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = readTimeoutMs
        if (relayPass.isNotBlank()) {
            val auth = "Basic " + Base64.getEncoder().encodeToString("$relayUser:$relayPass".toByteArray())
            conn.setRequestProperty("Authorization", auth)
        }
        if (postBody != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(postBody.toByteArray()) }
        }
        val code = conn.responseCode
        // The response stream has to be closed, or every poll leaks a descriptor and
        // the connection never goes back to the keep-alive pool.
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.bufferedReader().readText() }.orEmpty()
        return if (code in 200..299) body else "Error: HTTP $code${if (body.isBlank()) "" else ": $body"}"
    }

    private fun relayBytes(path: String): Pair<String, ByteArray> {
        val cleanBase = relayBaseUrl.trimEnd('/')
        require(cleanBase.isNotBlank()) { "Relay Base URL is not configured. Check settings." }
        val conn = URI("$cleanBase$path").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        if (relayPass.isNotBlank()) {
            val auth = "Basic " + Base64.getEncoder().encodeToString("$relayUser:$relayPass".toByteArray())
            conn.setRequestProperty("Authorization", auth)
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            val detail = conn.errorStream?.use { it.bufferedReader().readText() }.orEmpty()
            error("HTTP $code fetching render preview${if (detail.isBlank()) "" else ": $detail"}")
        }
        val contentType = conn.contentType?.substringBefore(';') ?: "image/jpeg"
        val bytes = conn.inputStream.use { it.readBytes() }
        require(bytes.isNotEmpty()) { "Render preview was empty" }
        require(bytes.size <= 8 * 1024 * 1024) { "Render preview is too large (${bytes.size} bytes)" }
        return contentType to bytes
    }

    private fun getRenderStatus(): String {
        return try {
            val status = relayHttp("/api/status")
            val colab = relayHttp("/api/colab/state")
            "Forge Hub Status:\n$status\n\nColab Pipeline State:\n$colab"
        } catch (e: Exception) {
            "Error querying render status: ${e.message}"
        }
    }

    private fun reviewLatestRender(): ToolExecution {
        val groups = json.parseToJsonElement(relayHttp("/api/media/library")) as? JsonArray
            ?: return ToolExecution("Error: Forge Hub returned an invalid media library.")
        val clips = groups.flatMap { groupElement ->
            val group = groupElement.jsonObject
            val groupTitle = group["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val source = group["source"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val groupClips = group["clips"] as? JsonArray ?: JsonArray(emptyList())
            groupClips.mapNotNull { clipElement ->
                val clip = clipElement.jsonObject
                val name = clip["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val mtime = clip["mtime"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                RenderPreview(name, groupTitle, source, mtime)
            }
        }
        val latest = clips.maxByOrNull { it.mtime }
            ?: return ToolExecution("No completed Forge Hub renders were found.")
        val encodedName = URLEncoder.encode(latest.name, Charsets.UTF_8.name()).replace("+", "%20")
        val (mime, bytes) = runCatching {
            relayBytes("/api/media/contact/$encodedName")
        }.getOrElse {
            relayBytes("/api/media/thumb/$encodedName")
        }
        val dataUrl = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
        val text = buildString {
            appendLine("Latest completed Forge Hub render:")
            appendLine("name: ${latest.name}")
            if (latest.groupTitle.isNotBlank()) appendLine("group: ${latest.groupTitle}")
            if (latest.source.isNotBlank()) appendLine("source: ${latest.source}")
            append("A dense contact sheet (12–24 frames in a grid, sampled across the clip) from the actual render is attached to this tool result (or a single preview frame when contact sheets are unavailable). Walk the frames in order: note motion progression, morphs, flicker, and consistency. Do not claim to have assessed audio.")
        }
        return ToolExecution(text, listOf(dataUrl))
    }


    private fun renderAssessAdjustCycle(argumentsJson: String): String {
        val args = json.parseToJsonElement(argumentsJson).jsonObjectOrEmpty()
        val action = args.stringOrNull("action")?.trim()?.lowercase().orEmpty().ifBlank { "status" }
        return try {
            when (action) {
                "status" -> relayHttp("/api/ltx/cycle/status")
                "stop" -> relayHttp("/api/ltx/cycle/stop", method = "POST", postBody = "{}")
                "start" -> {
                    val payload = buildJsonObject {
                        put("rounds", args["rounds"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 5)
                        args.stringOrNull("src")?.takeIf { it.isNotBlank() }?.let { put("src", it) }
                    }
                    relayHttp("/api/ltx/cycle/start", method = "POST", postBody = payload.toString())
                }
                else -> "Error: action must be start, status, or stop (got '$action')."
            }
        } catch (e: Exception) {
            "Error controlling render cycle: ${e.message}"
        }
    }

    private fun listPromptPacks(): String {
        return try {
            relayHttp("/api/colab/packs")
        } catch (e: Exception) {
            "Error fetching prompt packs: ${e.message}"
        }
    }

    private fun runLaptopCommand(argumentsJson: String): String {
        return try {
            val args = json.parseToJsonElement(argumentsJson).jsonObjectOrEmpty()
            val code = args.stringOrNull("code") ?: return "Error: 'code' argument is required."
            val lang = args.stringOrNull("lang") ?: "bash"
            val cwd = args.stringOrNull("cwd") ?: "venice_run"
            val timeout = (args["timeout_seconds"] as? JsonPrimitive)?.intOrNull?.coerceIn(10, 180) ?: 90
            val payload = buildJsonObject {
                put("lang", lang)
                put("code", code)
                put("cwd", cwd)
                put("timeout", timeout)
            }.toString()
            relayHttp("/api/laptop/run", method = "POST", postBody = payload)
        } catch (e: Exception) {
            "Error running laptop command: ${e.message}"
        }
    }

    /** POSIX single-quoting for a value that is interpolated into a remote shell command. */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun downloadCivitaiLora(argumentsJson: String): String {
        return try {
            val args = json.parseToJsonElement(argumentsJson).jsonObjectOrEmpty()
            val id = args.stringOrNull("model_id") ?: return "Error: 'model_id' argument is required."
            val slug = args.stringOrNull("slug") ?: ""
            // These values come from the model and are pasted into a bash command on
            // the laptop; unquoted, a space or a ';' in either one runs as a command.
            val cmd = buildString {
                append("python3 tools/civitai_red_dl.py")
                id.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }.forEach { append(" --id " + shellQuote(it)) }
                if (slug.isNotBlank()) append(" --slug " + shellQuote(slug))
            }
            val payload = buildJsonObject {
                put("lang", "bash")
                put("code", cmd)
                put("timeout", 180)
            }.toString()
            relayHttp("/api/laptop/run", method = "POST", postBody = payload)
        } catch (e: Exception) {
            "Error invoking Civitai downloader: ${e.message}"
        }
    }


    private fun queueGpuRender(argumentsJson: String): String {
        return try {
            val args = json.parseToJsonElement(argumentsJson).jsonObjectOrEmpty()
            val cloud = args.stringOrNull("cloud")?.trim()?.lowercase().orEmpty()
            if (cloud.isEmpty()) return "Error: 'cloud' is required (colab|thunder|ltx|gen|vast)."
            val bodyJson = args.stringOrNull("body_json")?.trim().orEmpty()
            val packs = args.stringOrNull("packs")?.trim().orEmpty()
            val prompt = args.stringOrNull("prompt")?.trim().orEmpty()
            when (cloud) {
                "colab" -> {
                    val packList = packs.split(Regex("[\\s,;]+")).mapNotNull { it.toIntOrNull() }
                    if (packList.isEmpty()) return "Error: colab needs packs (e.g. 1,2)."
                    val payload = buildJsonObject {
                        putJsonArray("packs") { packList.forEach { add(JsonPrimitive(it)) } }
                    }.toString()
                    relayHttp("/api/colab/queue", method = "POST", postBody = payload)
                }
                "thunder" -> {
                    if (bodyJson.isEmpty()) return "Error: thunder needs body_json with spec."
                    relayHttp("/api/thunder/queue", method = "POST", postBody = bodyJson)
                }
                "ltx" -> {
                    val p = if (prompt.isNotEmpty()) prompt else {
                        runCatching {
                            json.parseToJsonElement(bodyJson).jsonObjectOrEmpty().stringOrNull("prompt")
                        }.getOrNull().orEmpty()
                    }
                    if (p.length < 10) return "Error: ltx needs a prompt (10+ chars)."
                    // Form-urlencoded via hub_request shape — use JSON fields the FastAPI Form accepts poorly;
                    // post as multipart-ish query body through a small JSON wrapper endpoint isn't available,
                    // so use hub_request with path and let relay accept form: build urlencoded.
                    val form = buildString {
                        append("prompt=").append(java.net.URLEncoder.encode(p, "UTF-8"))
                        append("&frames=49&size=landscape")
                    }
                    relayHttp("/api/ltx/render", method = "POST", postBody = form, readTimeoutMs = 120_000)
                }
                "gen" -> {
                    if (bodyJson.isEmpty()) return "Error: gen needs body_json."
                    relayHttp("/api/gen/chain", method = "POST", postBody = bodyJson)
                }
                "vast" -> {
                    val path = runCatching {
                        json.parseToJsonElement(bodyJson).jsonObjectOrEmpty().stringOrNull("path")
                    }.getOrNull() ?: "/api/vast/state"
                    if (bodyJson.isEmpty() || path == "/api/vast/state") {
                        relayHttp("/api/vast/state")
                    } else {
                        relayHttp(path, method = "POST", postBody = bodyJson)
                    }
                }
                else -> "Error: cloud must be colab|thunder|ltx|gen|vast."
            }.take(24000)
        } catch (e: Exception) {
            "Error: queue_gpu_render failed: ${e.message}"
        }
    }

    private fun hubOverview(): String {
        val paths = listOf(
            "/api/status",
            "/api/laptop/ping",
            "/api/colab/state",
            "/api/thunder/state",
            "/api/thunder/queue",
            "/api/vast/state",
            "/api/ltx/status",
            "/api/ltx/jobs",
            "/api/ltx/cycle/status",
            "/api/gen/jobs",
            "/api/media/library",
            "/api/setup",
            "/api/term/status",
            "/api/agentic/status",
            "/api/manifest",
        )
        return buildString {
            for (path in paths) {
                appendLine("=== $path ===")
                appendLine(runCatching { relayHttp(path) }.getOrElse { "Error: ${it.message}" }.take(4000))
                appendLine()
            }
        }.take(24000)
    }

    private fun remember(argumentsJson: String): String {
        val args = json.parseToJsonElement(argumentsJson).jsonObjectOrEmpty()
        val patch = memoryPatch(args.stringOrNull("note"), args.stringOrNull("key"), args.stringOrNull("value"))
            ?: return "Error: give a note, or a key and a value."
        val reply = relayHttp("/api/agentic/memory", method = "PUT", postBody = patch, readTimeoutMs = 30_000)
        return if (reply.startsWith("Error:")) reply else "Saved to the Hub's memory."
    }

    private fun hubRequest(argumentsJson: String): String {
        return try {
            val args = json.parseToJsonElement(argumentsJson).jsonObjectOrEmpty()
            val path = args.stringOrNull("path")?.trim().orEmpty()
            if (!path.startsWith("/api/")) return "Error: path must start with /api/"
            if (".." in path) return "Error: invalid path"
            val method = (args.stringOrNull("method") ?: "GET").trim().uppercase().ifBlank { "GET" }
            if (method !in setOf("GET", "POST", "PUT", "DELETE", "PATCH")) {
                return "Error: unsupported method $method"
            }
            val body = args.stringOrNull("body")?.trim()?.takeIf { it.isNotEmpty() }
            relayHttp(path, method = method, postBody = body, readTimeoutMs = 120_000).take(24000)
        } catch (e: Exception) {
            "Error: hub_request failed: ${e.message}"
        }
    }

    private fun objectSchema(vararg params: Pair<String, String>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            params.forEach { (name, description) ->
                putJsonObject(name) {
                    put("type", "string")
                    put("description", description)
                }
            }
        }
        put("required", buildJsonArray {
            params.forEach { (name, _) -> add(kotlinx.serialization.json.JsonPrimitive(name)) }
        })
    }
}

private data class RenderPreview(
    val name: String,
    val groupTitle: String,
    val source: String,
    val mtime: Double,
)

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrEmpty(): JsonObject =
    this as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content


/**
 * The body for PUT /api/agentic/memory: the hub merges `preferences`, appends `append_note`. Null when there is
 * nothing to save. Public so it can be tested without a hub.
 */
fun memoryPatch(note: String?, key: String?, value: String?): String? {
    val n = note?.trim().orEmpty().take(4000)
    val k = key?.trim().orEmpty().take(80)
    val v = value?.trim().orEmpty().take(1000)
    if (n.isEmpty() && (k.isEmpty() || v.isEmpty())) return null
    return buildJsonObject {
        if (n.isNotEmpty()) put("append_note", n)
        if (k.isNotEmpty() && v.isNotEmpty()) putJsonObject("preferences") { put(k, v) }
    }.toString()
}
