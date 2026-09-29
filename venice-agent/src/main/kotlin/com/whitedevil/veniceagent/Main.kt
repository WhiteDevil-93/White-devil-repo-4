package com.whitedevil.veniceagent

import com.whitedevil.agent.VeniceClient
import kotlinx.coroutines.runBlocking
import java.io.File

private const val DEFAULT_MODEL = "zai-org-glm-5-2"
private const val DEFAULT_BASE_URL = "https://api.venice.ai/api/v1"

private val SYSTEM_PROMPT = """
You are WhiteDevil — an agentic engineering assistant. Take a goal, plan briefly, use tools, observe results, recover from failures, and finish or say you are stuck.
Tools: read_file, write_file, list_directory, run_shell_command (if enabled), get_render_status, list_prompt_packs, run_laptop_command, download_civitai_lora.
Prefer acting over listing commands. Ask before destructive actions. Be concise and direct.
""".trim()

fun main(args: Array<String>) = runBlocking {
    val apiKey = System.getenv("VENICE_API_KEY")
    if (apiKey.isNullOrBlank()) {
        System.err.println(
            "Error: VENICE_API_KEY environment variable is not set.\n" +
                "Get a key at https://venice.ai/settings/api and export it:\n" +
                "  export VENICE_API_KEY=your-key-here",
        )
        return@runBlocking
    }

    val model = System.getenv("VENICE_MODEL")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    val baseUrl = System.getenv("VENICE_BASE_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
    val allowShell = System.getenv("AGENT_ALLOW_SHELL")?.equals("true", ignoreCase = true) ?: true
    val enableWebSearch = System.getenv("AGENT_WEB_SEARCH")?.equals("true", ignoreCase = true) ?: false
    val workspaceDir = File(System.getenv("AGENT_WORKSPACE_DIR")?.takeIf { it.isNotBlank() } ?: "./workspace")

    val toolBox = ToolBox(workspaceDir = workspaceDir, allowShell = allowShell)
    val client = VeniceClient(apiKey = apiKey, baseUrl = baseUrl)

    val agent = Agent(
        client = client,
        model = model,
        toolBox = toolBox,
        systemPrompt = SYSTEM_PROMPT,
        enableWebSearch = enableWebSearch,
        onToolCall = { name, arguments -> println("  -> tool call: $name($arguments)") },
        onToolResult = { name, result ->
            val preview = result.lines().take(5).joinToString("\n")
            println("  <- $name result: $preview${if (result.lines().size > 5) "\n     ..." else ""}")
        },
    )

    client.use {
        println("Venice Agent + Forge Hub ready. Model: $model | workspace: ${workspaceDir.absolutePath} | shell: $allowShell")
        if (args.isNotEmpty()) {
            val task = args.joinToString(" ")
            println("> $task")
            // An exception here used to escape main and print a stack trace
            // instead of a usable error; the agent retry is already exhausted.
            println(runCatching { agent.send(task) }.getOrElse { "Error: ${it.message}" })
            return@runBlocking
        }

        println("Type a message and press Enter. Type 'exit' or 'quit' to stop.")
        while (true) {
            print("\n> ")
            val line = readlnOrNull() ?: break
            val trimmed = line.trim()
            if (trimmed.equals("exit", ignoreCase = true) || trimmed.equals("quit", ignoreCase = true)) {
                break
            }
            if (trimmed.isEmpty()) continue
            // One failed turn must not end the session and lose the conversation.
            val reply = runCatching { agent.send(trimmed) }.getOrElse { "Error: ${it.message}" }
            println("\n$reply")
        }
    }
}
