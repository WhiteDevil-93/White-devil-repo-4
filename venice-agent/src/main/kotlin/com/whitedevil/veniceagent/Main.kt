package com.whitedevil.veniceagent

import kotlinx.coroutines.runBlocking
import java.io.File

private const val DEFAULT_MODEL = "zai-org-glm-5-2"
private const val DEFAULT_BASE_URL = "https://api.venice.ai/api/v1"

private val SYSTEM_PROMPT = """
You are an autonomous AI engineering agent connected to Forge Hub and Wan2.2 video generation pipelines.
You have tools to:
1. Read, write, and inspect files in the local workspace directory (`read_file`, `write_file`, `list_directory`).
2. Run sandboxed shell commands (`run_shell_command`).
3. Query Forge Hub live render status, GPU compute usage, and job queue (`get_render_status`).
4. Inspect prompt chains and pack completion status (`list_prompt_packs`).
5. Execute commands on the connected WSL laptop over SSH (`run_laptop_command`).
6. Trigger LoRA downloads directly on the laptop via Civitai (`download_civitai_lora`).

Use tools proactively to inspect state, diagnose issues, or execute rendering and pipeline workflows. Be concise and direct in your answers.
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
            println(agent.send(task))
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
            val reply = agent.send(trimmed)
            println("\n$reply")
        }
    }
}
