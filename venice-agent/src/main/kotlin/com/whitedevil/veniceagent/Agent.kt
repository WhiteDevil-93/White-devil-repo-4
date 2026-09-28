package com.whitedevil.veniceagent

class Agent(
    private val client: VeniceClient,
    private val model: String,
    private val tools: ToolRegistry,
    private val systemPrompt: String,
    private val enableWebSearch: Boolean,
    private val maxToolIterations: Int = 8,
    private val onToolCall: (name: String, arguments: String) -> Unit = { _, _ -> },
    private val onToolResult: (name: String, result: String) -> Unit = { _, _ -> },
) {
    private val history = mutableListOf(ChatMessage(role = "system", content = systemPrompt))

    /** Sends [userMessage] plus prior history, resolving any tool calls, and returns the final assistant reply. */
    suspend fun send(userMessage: String): String {
        history.add(ChatMessage(role = "user", content = userMessage))

        repeat(maxToolIterations) {
            val response = client.chatCompletion(
                ChatCompletionRequest(
                    model = model,
                    messages = history,
                    tools = tools.definitions().ifEmpty { null },
                    veniceParameters = if (enableWebSearch) {
                        VeniceParameters(enableWebSearch = "on")
                    } else {
                        null
                    },
                ),
            )

            val choice = response.choices.firstOrNull()
                ?: return "Error: Venice API returned no choices."
            val message = choice.message
            history.add(message)

            val toolCalls = message.toolCalls
            if (toolCalls.isNullOrEmpty()) {
                return message.content ?: ""
            }

            for (call in toolCalls) {
                onToolCall(call.function.name, call.function.arguments)
                val result = tools.execute(call.function.name, call.function.arguments)
                onToolResult(call.function.name, result)
                history.add(
                    ChatMessage(
                        role = "tool",
                        content = result,
                        toolCallId = call.id,
                        name = call.function.name,
                    ),
                )
            }
        }

        return "Error: reached the maximum number of tool iterations ($maxToolIterations) without a final answer."
    }
}
