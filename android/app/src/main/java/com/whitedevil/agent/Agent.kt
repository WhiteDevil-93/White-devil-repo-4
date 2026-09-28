package com.whitedevil.agent

import kotlinx.serialization.json.JsonPrimitive

sealed class AgentEvent {
    data class User(val text: String) : AgentEvent()
    data class Venice(val text: String) : AgentEvent()
    data class ToolCall(val name: String, val arguments: String) : AgentEvent()
    data class ToolOutput(val name: String, val output: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}

class Agent(
    private val client: VeniceClient,
    private val model: String,
    private val toolBox: ToolBox,
    private val systemPrompt: String,
    private val enableWebSearch: Boolean = false,
    private val maxToolIterations: Int = 8,
    private val onEvent: (AgentEvent) -> Unit = {},
) {
    companion object {
        const val MAX_HISTORY_MESSAGES = 100
    }

    private val history = mutableListOf<ChatMessage>()

    init {
        reset()
    }

    fun reset() {
        history.clear()
        if (systemPrompt.isNotBlank()) {
            history.add(ChatMessage(role = "system", content = MessageContent.text(systemPrompt)))
        }
    }

    /** Conversation snapshot for persistence (callers should strip blobs first). */
    fun snapshot(): List<ChatMessage> = history.toList()

    /**
     * Restores a persisted conversation. The current system prompt stays authoritative:
     * stale system messages are dropped and the live prompt is re-anchored first.
     */
    fun restore(messages: List<ChatMessage>) {
        history.clear()
        if (systemPrompt.isNotBlank()) {
            history.add(ChatMessage(role = "system", content = MessageContent.text(systemPrompt)))
        }
        val validRoles = setOf("user", "assistant", "tool")
        history.addAll(messages.filter { it.role in validRoles }.takeLast(MAX_HISTORY_MESSAGES))
    }

    /** Sends [userText] plus prior history, resolving any tool calls, emitting UI events. */
    suspend fun send(userText: String, imageDataUrls: List<String> = emptyList()): String {
        val displayText = if (imageDataUrls.isEmpty()) userText
        else if (userText.isBlank()) "[${imageDataUrls.size} image(s) attached]"
        else "$userText [${imageDataUrls.size} image(s) attached]"
        onEvent(AgentEvent.User(displayText))
        history.add(ChatMessage(role = "user", content = MessageContent.multimodal(userText, imageDataUrls)))

        repeat(maxToolIterations) {
            val response = try {
                client.chatCompletion(
                    ChatCompletionRequest(
                        model = model,
                        messages = history,
                        tools = toolBox.definitions.ifEmpty { null },
                        veniceParameters = if (enableWebSearch) {
                            VeniceParameters(enableWebSearch = "on")
                        } else {
                            null
                        },
                    ),
                )
            } catch (e: Exception) {
                val err = "Error communicating with Venice: ${e.message}"
                onEvent(AgentEvent.Error(err))
                return err
            }

            val choice = response.choices.firstOrNull()
            if (choice == null) {
                val err = "Error: Venice API returned no choices."
                onEvent(AgentEvent.Error(err))
                return err
            }

            val message = choice.message
            history.add(message)

            val toolCalls = message.toolCalls
            if (toolCalls.isNullOrEmpty()) {
                val reply = message.textContent()
                onEvent(AgentEvent.Venice(reply))
                return reply
            }

            // Execute each tool call
            for (call in toolCalls) {
                val callName = call.function.name
                val callArgs = call.function.arguments
                onEvent(AgentEvent.ToolCall(callName, callArgs))

                val result = toolBox.execute(callName, callArgs)
                onEvent(AgentEvent.ToolOutput(callName, result))

                history.add(
                    ChatMessage(
                        role = "tool",
                        content = JsonPrimitive(result),
                        toolCallId = call.id,
                        name = callName,
                    ),
                )
            }
        }

        val limitMsg = "Reached maximum tool iterations ($maxToolIterations)."
        onEvent(AgentEvent.Error(limitMsg))
        return limitMsg
    }
}
