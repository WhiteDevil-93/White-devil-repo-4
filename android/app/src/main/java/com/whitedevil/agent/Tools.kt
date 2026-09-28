package com.whitedevil.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import android.util.Base64

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
                    description = "Execute a bash or python command on the user's WSL laptop via the relay SSH bridge in ~/venice_run.",
                    parameters = objectSchema(
                        "code" to "The shell command or python script code to execute.",
                        "lang" to "Execution language: 'bash' or 'python'. Defaults to 'bash'.",
                    ),
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

    fun execute(name: String, argumentsJson: String): String {
        return try {
            when (name) {
                "read_file" -> readFile(argumentsJson)
                "write_file" -> writeFile(argumentsJson)
                "list_directory" -> listDirectory(argumentsJson)
                "delete_file" -> deleteFile(argumentsJson)
                "get_render_status" -> getRenderStatus()
                "list_prompt_packs" -> listPromptPacks()
                "run_laptop_command" -> runLaptopCommand(argumentsJson)
                "download_civitai_lora" -> downloadCivitaiLora(argumentsJson)
                else -> "Error: unknown tool '$name'."
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
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
            val auth = "Basic " + Base64.encodeToString("$relayUser:$relayPass".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", auth)
        }
        if (postBody != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(postBody.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.readText() ?: "HTTP $code"
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
            val payload = buildJsonObject {
                put("lang", lang)
                put("code", code)
                put("timeout", 90)
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

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrEmpty(): JsonObject =
    this as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
