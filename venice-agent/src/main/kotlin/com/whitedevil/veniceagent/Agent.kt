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

    /**
     * Sends [userMessage] plus prior history, resolving any tool calls, and returns the final
     * assistant reply.
     *
     * A tool call that throws is caught here and turned into an error-content tool result
     * (like any other tool failure) rather than propagating: some tools have already-completed,
     * non-undoable side effects, and losing the record that they ran (e.g. on a rollback) risks
     * the caller retrying and repeating a write/deployment/etc. This also keeps every tool_call
     * in an assistant message answered, which chat-completion APIs require.
     *
     * If `client.chatCompletion()` itself throws, no message has been added for that iteration
     * yet, so history is left exactly as it was through the last complete exchange — nothing
     * is rolled back, and nothing already recorded is lost.
     */
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
                val result = try {
                    tools.execute(call.function.name, call.function.arguments)
                } catch (e: Exception) {
                    "Error: tool '${call.function.name}' threw an unexpected exception: ${e.message}"
                }
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
