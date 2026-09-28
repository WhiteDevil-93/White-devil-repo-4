package com.whitedevil.veniceagent

import com.whitedevil.veniceagent.mcp.McpServerLoader
import kotlinx.coroutines.runBlocking
import java.io.File

private const val DEFAULT_MODEL = "llama-3.3-70b"
private const val DEFAULT_BASE_URL = "https://api.venice.ai/api/v1"

private val SYSTEM_PROMPT = """
You are a helpful autonomous coding and task agent running in a Kotlin CLI.
You have tools to read, write, and list files inside a sandboxed workspace directory,
and (if enabled) to run shell commands there. Additional tools from connected MCP
servers may also be available, namespaced as <server>__<tool>. Use tools when they
help you complete the user's request accurately. Be concise in your final answers.
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
    val allowShell = System.getenv("AGENT_ALLOW_SHELL")?.equals("true", ignoreCase = true) ?: false
    val enableWebSearch = System.getenv("AGENT_WEB_SEARCH")?.equals("true", ignoreCase = true) ?: false
    val workspaceDir = File(System.getenv("AGENT_WORKSPACE_DIR")?.takeIf { it.isNotBlank() } ?: "./workspace")
    val mcpConfigFile = File(System.getenv("AGENT_MCP_CONFIG")?.takeIf { it.isNotBlank() } ?: "./mcp-servers.json")

    val toolBox = ToolBox(workspaceDir = workspaceDir, allowShell = allowShell)
    val mcpClients = McpServerLoader.load(mcpConfigFile)
    val toolRegistry = ToolRegistry(listOf(toolBox) + mcpClients)
    val client = VeniceClient(apiKey = apiKey, baseUrl = baseUrl)

    val agent = Agent(
        client = client,
        model = model,
        tools = toolRegistry,
        systemPrompt = SYSTEM_PROMPT,
        enableWebSearch = enableWebSearch,
        onToolCall = { name, arguments -> println("  -> tool call: $name($arguments)") },
        onToolResult = { name, result ->
            val preview = result.lines().take(5).joinToString("\n")
            println("  <- $name result: $preview${if (result.lines().size > 5) "\n     ..." else ""}")
        },
    )

    toolRegistry.use {
        client.use {
            if (mcpClients.isNotEmpty()) {
                println("Connected MCP servers: ${mcpClients.joinToString(", ") { it.serverName }}")
            }
            println("Venice agent ready. Model: $model | workspace: ${workspaceDir.absolutePath} | shell: $allowShell")
            if (args.isNotEmpty()) {
                val task = args.joinToString(" ")
                println("> $task")
                println(agent.send(task))
                return@runBlocking
            }

            println("Type a message and press Enter. Type 'exit' or 'quit' to stop.")
            while (true) {
                print("\n> ")
                val line = readLine() ?: break
                if (line.equals("exit", ignoreCase = true) || line.equals("quit", ignoreCase = true)) break
                if (line.isBlank()) continue

                try {
                    println(agent.send(line))
                } catch (e: VeniceApiException) {
                    System.err.println("Venice API error: ${e.message}")
                } catch (e: Exception) {
                    System.err.println("Unexpected error: ${e.message}")
                }
            }
        }
    }
}
