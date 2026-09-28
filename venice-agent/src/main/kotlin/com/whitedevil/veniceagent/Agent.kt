package com.whitedevil.veniceagent

import kotlinx.coroutines.CancellationException

class Agent(
    private val client: VeniceApi,
    private val model: String,
    private val tools: ToolRegistry,
    private val systemPrompt: String,
    private val enableWebSearch: Boolean,
    private val maxToolIterations: Int = 8,
    private val onToolCall: (name: String, arguments: String) -> Unit = { _, _ -> },
    private val onToolResult: (name: String, result: String) -> Unit = { _, _ -> },
) {
    private val history = mutableListOf(ChatMessage(role = "system", content = systemPrompt))

    /** Test-only window into conversation state; production code never needs to inspect it. */
    internal fun historySnapshot(): List<ChatMessage> = history.toList()

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
     * If `client.chatCompletion()` itself throws before any progress was made on this turn
     * (its very first call, before any assistant message was added), the just-added user
     * message is removed so a retried [send] doesn't leave it dangling unanswered alongside
     * the next prompt. If some progress was already made (a prior iteration's tool exchange
     * completed), history is left exactly as it was — nothing already recorded is lost.
     */
    suspend fun send(userMessage: String): String {
        val sizeBeforeUserMessage = history.size
        history.add(ChatMessage(role = "user", content = userMessage))

        try {
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
                    } catch (e: CancellationException) {
                        // The assistant message carrying every call in `toolCalls` is already in
                        // history (added above); a chat-completion API rejects a follow-up
                        // request unless every one of those tool_call_ids has a matching tool
                        // response. Answer this call and every one still unanswered with a
                        // cancellation marker before propagating, so history stays valid for a
                        // caller that catches this and reuses the agent, instead of leaving some
                        // tool_calls dangling unanswered.
                        toolCalls.dropWhile { it.id != call.id }.forEach { cancelled ->
                            history.add(
                                ChatMessage(
                                    role = "tool",
                                    content = "Error: cancelled before completion.",
                                    toolCallId = cancelled.id,
                                    name = cancelled.function.name,
                                ),
                            )
                        }
                        throw e
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
        } catch (e: Exception) {
            if (history.size == sizeBeforeUserMessage + 1) {
                history.removeAt(history.lastIndex)
            }
            throw e
        }
    }
}
