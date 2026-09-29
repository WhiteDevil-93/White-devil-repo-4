package com.whitedevil.veniceagent

import com.whitedevil.agent.ChatCompletionRequest
import com.whitedevil.agent.ChatCompletionResponse
import com.whitedevil.agent.ChatMessage
import com.whitedevil.agent.MessageContent
import com.whitedevil.agent.VeniceClient
import com.whitedevil.agent.VeniceParameters
import com.whitedevil.agent.textContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * CLI agent loop.
 *
 * This is NOT shared with [com.whitedevil.agent.Agent]: that one is typed against
 * the shared [com.whitedevil.agent.ToolBox] concrete class (no interface), and the
 * CLI needs its own ToolBox (`run_shell_command`, no `review_latest_render` /
 * `render_assess_adjust_cycle`). Everything below the loop — client, wire models —
 * now comes from :shared, and the CLI ToolBox delegates every non-shell tool to the
 * shared one. Extracting a ToolBox interface in :shared would let this file go too.
 *
 * The three history/transport protections that :shared's Agent carries are mirrored
 * here on purpose and must stay in step: [repairToolPairs], [trimHistory], and the
 * retry/backoff loop that rethrows CancellationException. This loop additionally
 * guarantees a `tool` result message for every tool_call even when the executor or a
 * callback throws.
 */
class Agent(
    private val client: VeniceClient,
    private val model: String,
    private val toolBox: ToolBox,
    private val systemPrompt: String,
    private val enableWebSearch: Boolean,
    private val maxToolIterations: Int = 8,
    private val onToolCall: (name: String, arguments: String) -> Unit = { _, _ -> },
    private val onToolResult: (name: String, result: String) -> Unit = { _, _ -> },
) {
    companion object {
        /** Oldest turns beyond this are dropped (the system prompt is always kept). */
        const val MAX_HISTORY_MESSAGES = 100

        /** Attempts per chat request, including the first. */
        const val MAX_REQUEST_ATTEMPTS = 3

        /** Backoff before retry n is this shifted left by n: 500ms, 1s, 2s. */
        const val RETRY_BASE_DELAY_MS = 500L
    }

    private val history = mutableListOf(ChatMessage(role = "system", content = MessageContent.text(systemPrompt)))

    /**
     * Restores the tool-call pairing invariant the API enforces: every assistant
     * `tool_calls` entry needs a matching `tool` message, and every `tool` message
     * needs its assistant parent. Trimming can cut between an assistant message and
     * its results; a failure mid-loop can leave a call with no result. Either one
     * makes the API reject every later turn with a 400, so stub the missing results
     * and drop the orphans rather than sending them.
     */
    private fun repairToolPairs(messages: List<ChatMessage>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        messages.forEachIndexed { index, message ->
            if (message.role == "tool") return@forEachIndexed // re-emitted beside its parent
            out.add(message)
            val calls = message.toolCalls.takeIf { message.role == "assistant" } ?: return@forEachIndexed
            for (call in calls) {
                val answered = messages
                    .asSequence()
                    .drop(index + 1)
                    .takeWhile { it.role == "tool" }
                    .firstOrNull { it.toolCallId == call.id }
                out.add(
                    answered ?: ChatMessage(
                        role = "tool",
                        content = MessageContent.text("Interrupted — this tool call did not complete."),
                        toolCallId = call.id,
                        name = call.function.name,
                    ),
                )
            }
        }
        return out
    }

    /**
     * Caps in-run growth. A single goal can burn all [maxToolIterations] turns with
     * large tool outputs and nothing was trimmed, so the request grew until the API
     * refused it. Keeps the system prompt pinned and the tool pairing intact.
     */
    private fun trimHistory() {
        if (history.size <= MAX_HISTORY_MESSAGES) return
        val system = history.firstOrNull()?.takeIf { it.role == "system" }
        val rest = if (system == null) history.toList() else history.drop(1)
        val kept = repairToolPairs(rest.takeLast(MAX_HISTORY_MESSAGES))
        history.clear()
        system?.let { history.add(it) }
        history.addAll(kept)
    }

    /** Sends [userMessage] plus prior history, resolving any tool calls, and returns the final assistant reply. */
    suspend fun send(userMessage: String): String {
        history.add(ChatMessage(role = "user", content = MessageContent.text(userMessage)))

        repeat(maxToolIterations) {
            trimHistory()
            val request = ChatCompletionRequest(
                model = model,
                messages = history,
                tools = toolBox.definitions.ifEmpty { null },
                veniceParameters = if (enableWebSearch) {
                    VeniceParameters(enableWebSearch = "on")
                } else {
                    null
                },
            )

            // A single dropped connection used to end the whole run. Retry the
            // transient ones with backoff; cancellation must still propagate.
            var response: ChatCompletionResponse? = null
            var lastError: Exception? = null
            for (attempt in 0 until MAX_REQUEST_ATTEMPTS) {
                try {
                    response = client.chatCompletion(request)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    if (attempt == MAX_REQUEST_ATTEMPTS - 1) break
                    delay(RETRY_BASE_DELAY_MS shl attempt)
                }
            }
            if (response == null) {
                return "Error communicating with Venice: ${lastError?.message}"
            }

            val choice = response.choices.firstOrNull()
                ?: return "Error: Venice API returned no choices."
            val message = choice.message
            history.add(message)

            val toolCalls = message.toolCalls
            if (toolCalls.isNullOrEmpty()) {
                return message.textContent()
            }

            for (call in toolCalls) {
                // Every tool_call must get a result message, or the API rejects the
                // rest of the conversation with a 400. Callbacks and the executor
                // are both untrusted here, so nothing may skip the history append.
                val result: String = try {
                    onToolCall(call.function.name, call.function.arguments)
                    toolBox.execute(call.function.name, call.function.arguments)
                } catch (e: CancellationException) {
                    history.add(
                        ChatMessage(
                            role = "tool",
                            content = MessageContent.text("Cancelled before this tool call completed."),
                            toolCallId = call.id,
                            name = call.function.name,
                        ),
                    )
                    throw e
                } catch (e: Throwable) {
                    "Error: tool '${call.function.name}' failed: ${e.message}"
                }
                runCatching { onToolResult(call.function.name, result) }
                history.add(
                    ChatMessage(
                        role = "tool",
                        content = MessageContent.text(result),
                        toolCallId = call.id,
                        name = call.function.name,
                    ),
                )
            }
        }

        return "Error: reached the maximum number of tool iterations ($maxToolIterations) without a final answer."
    }
}
