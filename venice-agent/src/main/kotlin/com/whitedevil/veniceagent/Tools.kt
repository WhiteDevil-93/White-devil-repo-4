package com.whitedevil.veniceagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tools are sandboxed to [workspaceDir]: file paths are resolved relative to it and any path
 * that escapes it (via ".." or an absolute path elsewhere) is rejected before touching disk.
 */
class ToolBox(
    private val workspaceDir: File,
    private val allowShell: Boolean,
) {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        workspaceDir.mkdirs()
    }

    val definitions: List<ToolDefinition> = buildList {
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
    }

    fun execute(name: String, argumentsJson: String): String {
        return try {
            when (name) {
                "read_file" -> readFile(argumentsJson)
                "write_file" -> writeFile(argumentsJson)
                "list_directory" -> listDirectory(argumentsJson)
                "run_shell_command" -> if (allowShell) runShellCommand(argumentsJson) else "Error: shell execution is disabled."
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
        put("required", kotlinx.serialization.json.buildJsonArray {
            params.forEach { (name, _) -> add(kotlinx.serialization.json.JsonPrimitive(name)) }
        })
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrEmpty(): JsonObject =
    this as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
