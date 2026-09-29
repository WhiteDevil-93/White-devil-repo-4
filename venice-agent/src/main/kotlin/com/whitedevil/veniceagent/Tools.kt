package com.whitedevil.veniceagent

import com.whitedevil.agent.ToolDefinition
import com.whitedevil.agent.ToolFunctionSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * CLI ToolBox.
 *
 * Everything except `run_shell_command` is delegated to [com.whitedevil.agent.ToolBox]
 * in :shared — the CLI used to carry its own copy of the relay/filesystem handlers and
 * it had already drifted (no blank-base-url guard, a shorter `hub_overview` path list,
 * no `cwd`/`timeout_seconds` on `run_laptop_command`).
 *
 * `run_shell_command` stays here: it runs a process on the machine hosting the agent,
 * which is only meaningful for the CLI, and :shared (an Android library dependency)
 * deliberately does not offer it.
 *
 * The exposed set is deliberately narrower than :shared's. Tools left out:
 *  - `delete_file` — never offered by the CLI; adding it would hand the model a new
 *    destructive verb it did not previously have.
 *  - `review_latest_render` / `render_assess_adjust_cycle` — image-returning /
 *    UI-driven tools with no CLI surface (this loop returns text only).
 *  - `queue_gpu_render` — not previously offered by the CLI.
 * Widening the set is a behaviour change; do it deliberately, not by accident.
 */
class ToolBox(
    private val workspaceDir: File,
    private val allowShell: Boolean,
    relayBaseUrl: String = System.getenv("RELAY_BASE_URL") ?: "https://84-12-112-249.sslip.io",
    relayUser: String = System.getenv("RELAY_USER") ?: "anon3",
    relayPass: String = System.getenv("RELAY_PASS") ?: "",
) {
    private companion object {
        /** Shared tools the CLI exposes, matching the set it exposed before consolidation. */
        val SHARED_TOOLS = setOf(
            "read_file",
            "write_file",
            "list_directory",
            "get_render_status",
            "list_prompt_packs",
            "run_laptop_command",
            "download_civitai_lora",
            "hub_overview",
            "hub_request",
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val shared = com.whitedevil.agent.ToolBox(
        workspaceDir = workspaceDir,
        relayBaseUrl = relayBaseUrl,
        relayUser = relayUser,
        relayPass = relayPass,
    )

    val definitions: List<ToolDefinition> = buildList {
        addAll(shared.definitions.filter { it.function.name in SHARED_TOOLS })
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
                "run_shell_command" ->
                    if (allowShell) runShellCommand(argumentsJson) else "Error: shell execution is disabled."
                in SHARED_TOOLS -> shared.execute(name, argumentsJson)
                else -> "Error: unknown tool '$name'."
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun runShellCommand(argumentsJson: String): String {
        val args = json.parseToJsonElement(argumentsJson) as? JsonObject ?: JsonObject(emptyMap())
        val command = (args["command"] as? JsonPrimitive)?.content
            ?: return "Error: 'command' argument is required."
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
        put("required", buildJsonArray { params.forEach { (name, _) -> add(JsonPrimitive(name)) } })
    }
}
