package com.whitedevil.veniceagent

import com.whitedevil.veniceagent.mcp.McpServerLoader
import com.whitedevil.veniceagent.mcp.McpStdioClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File

private const val DEFAULT_MODEL = "zai-org-glm-5-2"
private const val DEFAULT_BASE_URL = "https://api.venice.ai/api/v1"

/**
 * [allowShell] controls whether `run_shell_command` is mentioned; ToolBox omits it entirely when
 * disabled. [relayConfigured] does the same for the Forge Hub/laptop relay tools, which ToolBox
 * also omits entirely unless RELAY_USER/RELAY_PASS are both set.
 */
private fun buildSystemPrompt(allowShell: Boolean, relayConfigured: Boolean): String {
    val toolLines = buildList {
        add("Read, write, and inspect files in the local workspace directory (`read_file`, `write_file`, `list_directory`).")
        if (allowShell) add("Run sandboxed shell commands (`run_shell_command`).")
        if (relayConfigured) {
            add("Query Forge Hub live render status, GPU compute usage, and job queue (`get_render_status`).")
            add("Inspect prompt chains and pack completion status (`list_prompt_packs`).")
            add("Execute commands on the connected WSL laptop over SSH (`run_laptop_command`).")
            add("Trigger LoRA downloads directly on the laptop via Civitai (`download_civitai_lora`).")
        }
        add("Use additional MCP tools when available; they are namespaced as `<server>__<tool>`.")
    }
    val numberedTools = toolLines.mapIndexed { index, line -> "${index + 1}. $line" }.joinToString("\n")

    return """
You are an autonomous AI engineering agent connected to Forge Hub and Wan2.2 video generation pipelines.
You have tools to:
$numberedTools

Use tools proactively to inspect state, diagnose issues, or execute rendering and pipeline workflows. Be concise and direct in your answers.
""".trim()
}

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
    val allowShell = System.getenv("AGENT_ALLOW_SHELL")?.equals("true", ignoreCase = true) ?: false
    val enableWebSearch = System.getenv("AGENT_WEB_SEARCH")?.equals("true", ignoreCase = true) ?: false
    val workspaceDir = File(System.getenv("AGENT_WORKSPACE_DIR")?.takeIf { it.isNotBlank() } ?: "./workspace")
    val mcpConfigFile = File(System.getenv("AGENT_MCP_CONFIG")?.takeIf { it.isNotBlank() } ?: "./mcp-servers.json")

    val toolBox = ToolBox(workspaceDir = workspaceDir, allowShell = allowShell)
    val mcpClients = McpServerLoader.load(mcpConfigFile)
    val toolRegistry = ToolRegistry(listOf(toolBox) + mcpClients)
    val client = VeniceClient(apiKey = apiKey, baseUrl = baseUrl)

    try {
        runCli(client, toolRegistry, mcpClients, model, enableWebSearch, workspaceDir, allowShell, toolBox.relayConfigured, args)
    } finally {
        client.close()
        toolRegistry.close()
    }
}

private suspend fun runCli(
    client: VeniceClient,
    tools: ToolRegistry,
    mcpClients: List<McpStdioClient>,
    model: String,
    enableWebSearch: Boolean,
    workspaceDir: File,
    allowShell: Boolean,
    relayConfigured: Boolean,
    args: Array<String>,
) {
    val agent = Agent(
        client = client,
        model = model,
        tools = tools,
        systemPrompt = buildSystemPrompt(allowShell, relayConfigured),
        enableWebSearch = enableWebSearch,
        onToolCall = { name, arguments -> println("  -> tool call: $name($arguments)") },
        onToolResult = { name, result ->
            val preview = result.lines().take(5).joinToString("\n")
            println("  <- $name result: $preview${if (result.lines().size > 5) "\n     ..." else ""}")
        },
    )

    if (mcpClients.isNotEmpty()) {
        println("Connected MCP servers: ${mcpClients.joinToString(", ") { it.serverName }}")
    }
    println("Venice Agent + Forge Hub ready. Model: $model | workspace: ${workspaceDir.absolutePath} | shell: $allowShell")

    if (args.isNotEmpty()) {
        val task = args.joinToString(" ")
        println("> $task")
        println(agent.send(task))
        return
    }

    println("Type a message and press Enter. Type 'exit' or 'quit' to stop.")
    while (true) {
        print("\n> ")
        val line = readlnOrNull()?.trim() ?: break
        if (line.equals("exit", ignoreCase = true) || line.equals("quit", ignoreCase = true)) break
        if (line.isEmpty()) continue

        try {
            println("\n${agent.send(line)}")
        } catch (e: CancellationException) {
            throw e // the ambient coroutine is shutting down; don't print an error and loop again
        } catch (e: VeniceApiException) {
            System.err.println("Venice API error: ${e.message}")
        } catch (e: Exception) {
            System.err.println("Unexpected error: ${e.message}")
        }
    }
}
