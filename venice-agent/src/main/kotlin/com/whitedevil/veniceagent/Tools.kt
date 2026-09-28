package com.whitedevil.veniceagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Unified ToolBox for the Venice Agent:
 * 1. Sandboxed filesystem tools (`read_file`, `write_file`, `list_directory`)
 * 2. Shell execution (`run_shell_command`)
 * 3. Forge Hub & Wan2.2 pipeline tools
 */
class ToolBox(
    private val workspaceDir: File,
    private val allowShell: Boolean,
    private val relayBaseUrl: String = System.getenv("RELAY_BASE_URL") ?: "https://84-12-112-249.sslip.io",
    private val relayUser: String = System.getenv("RELAY_USER") ?: "anon3",
    private val relayPass: String = System.getenv("RELAY_PASS") ?: "",
) : ToolProvider {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        workspaceDir.mkdirs()
    }

    private val toolDefinitions: List<ToolDefinition> = buildList {
        add(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "read_file",
                    description = "Read the contents of a text file inside the agent workspace.",
                    parameters = objectSchema("path" to "Path to the file, relative to the workspace root."),
                ),
            ),
        )
        add(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "write_file",
                    description = "Create or overwrite a text file inside the agent workspace.",
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
                    description = "List files and subdirectories inside a directory in the agent workspace.",
                    parameters = objectSchema("path" to "Directory path, relative to the workspace root. Use \".\" for the root."),
                ),
            ),
        )
        if (allowShell) {
            add(
                ToolDefinition(
                    function = ToolFunctionSpec(
                        name = "run_shell_command",
                        description = "Run a shell command inside the agent workspace directory and return its stdout/stderr.",
                        parameters = objectSchema("command" to "The shell command to execute."),
                    ),
                ),
            )
        }

        // Forge Hub & Pipeline tools
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
                    description = "List prompt packs in the Wan2.2 generation catalog and their render completion status.",
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
                    description = "Trigger a download of a LoRA or model from Civitai to the laptop's ~/civitai_dl folder.",
                    parameters = objectSchema(
                        "model_id" to "Civitai model ID or version ID.",
                        "slug" to "Optional model slug name for file naming.",
                    ),
                ),
            ),
        )
    }

    override suspend fun definitions(): List<ToolDefinition> = toolDefinitions

    override suspend fun execute(name: String, argumentsJson: String): String = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "read_file" -> readFile(argumentsJson)
                "write_file" -> writeFile(argumentsJson)
                "list_directory" -> listDirectory(argumentsJson)
                "run_shell_command" -> if (allowShell) runShellCommand(argumentsJson) else "Error: shell execution is disabled."
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
            "path '$relativePath' escapes the workspace directory"
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

    private fun runShellCommand(argumentsJson: String): String {
        val args = json.parseToJsonElement(argumentsJson).jsonObjectOrEmpty()
        val command = args.stringOrNull("command") ?: return "Error: 'command' argument is required."
        val process = ProcessBuilder("sh", "-c", command)
            .directory(workspaceDir)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return "Error: command timed out after 60s."
        }
        val output = process.inputStream.bufferedReader().readText()
        return "exit code: ${process.exitValue()}\n$output".take(8000)
    }

    // ---------- Forge Hub & Relay Tool Handlers ----------

    private fun relayHttp(path: String, method: String = "GET", postBody: String? = null): String {
        val url = java.net.URI("${relayBaseUrl.trimEnd('/')}$path").toURL()
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
                append("python3 tools/civitai_red_dl.py --id $id")
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
