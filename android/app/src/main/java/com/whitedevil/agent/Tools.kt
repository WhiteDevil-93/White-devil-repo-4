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
) {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        workspaceDir.mkdirs()
    }

    val definitions: List<ToolDefinition> = buildList {
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
                    description = "Fetch the newest completed Forge Hub render and its actual preview image so you can visually review it. You MUST use this whenever the user asks to review, inspect, critique, describe, or check the latest/newest/recent render; do not ask the user to attach it.",
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
    }

    fun execute(name: String, argumentsJson: String): String =
        executeDetailed(name, argumentsJson).text

    fun executeDetailed(name: String, argumentsJson: String): ToolExecution {
        return try {
            val result = when (name) {
                "read_file" -> ToolExecution(readFile(argumentsJson))
                "write_file" -> ToolExecution(writeFile(argumentsJson))
                "list_directory" -> ToolExecution(listDirectory(argumentsJson))
                "delete_file" -> ToolExecution(deleteFile(argumentsJson))
                "get_render_status" -> ToolExecution(getRenderStatus())
                "review_latest_render" -> reviewLatestRender()
                "list_prompt_packs" -> ToolExecution(listPromptPacks())
                "run_laptop_command" -> ToolExecution(runLaptopCommand(argumentsJson))
                "download_civitai_lora" -> ToolExecution(downloadCivitaiLora(argumentsJson))
                else -> ToolExecution("Error: unknown tool '$name'.")
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

    private fun relayHttp(path: String, method: String = "GET", postBody: String? = null): String {
        val cleanBase = relayBaseUrl.trimEnd('/')
        if (cleanBase.isBlank()) {
            return "Error: Relay Base URL is not configured. Check settings."
        }
        val url = URI("$cleanBase$path").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
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
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.readText().orEmpty()
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
            val detail = conn.errorStream?.bufferedReader()?.readText().orEmpty()
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
            append("A four-frame contact sheet from the actual render is attached to this tool result (or a single preview frame when contact sheets are unavailable). Review visible composition, consistency, motion progression across frames, and artifacts. Do not claim to have assessed audio.")
        }
        return ToolExecution(text, listOf(dataUrl))
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

    private fun downloadCivitaiLora(argumentsJson: String): String {
        return try {
            val args = json.parseToJsonElement(argumentsJson).jsonObjectOrEmpty()
            val id = args.stringOrNull("model_id") ?: return "Error: 'model_id' argument is required."
            val slug = args.stringOrNull("slug") ?: ""
            val cmd = buildString {
                append("python3 tools/civitai_red_dl.py")
                id.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }.forEach { append(" --id $it") }
                if (slug.isNotBlank()) append(" --slug $slug")
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
