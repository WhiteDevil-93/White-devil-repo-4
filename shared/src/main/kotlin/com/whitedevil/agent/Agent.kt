package com.whitedevil.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    private val maxToolIterations: Int = 24,
    private val onEvent: (AgentEvent) -> Unit = {},
    /**
     * When set, replies are streamed and this gets the reply text so far each time it grows (it restarts
     * from empty if a request is retried). A callback rather than an AgentEvent case on purpose: the
     * desktop app shares this class and matches AgentEvent exhaustively.
     */
    private val onPartial: ((String) -> Unit)? = null,
) {
    sealed class SlashAction {
        data object Help : SlashAction()
        data object Review : SlashAction()
        data class Cycle(
            val action: String,
            val src: String? = null,
            val rounds: Int? = null,
        ) : SlashAction()
        data class Unknown(val cmd: String) : SlashAction()
    }

    companion object {
        const val MAX_HISTORY_MESSAGES = 100

        /** Attempts per chat request, including the first. */
        const val MAX_REQUEST_ATTEMPTS = 3

        /** Backoff before retry n is this shifted left by n: 500ms, 1s, 2s. */
        const val RETRY_BASE_DELAY_MS = 500L

        const val SLASH_HELP =
            "Agent takes goals and uses tools until done.\nOptional LTX shortcuts:\n/review — latest render contact sheet (vision model)\n/cycle start|status|stop [src=<jobId>] [rounds=N]\n/help — this list"

        /** Explicit slash tasks only — not fuzzy intent. */
        fun parseSlash(text: String): SlashAction? {
            val raw = text.trim()
            if (!raw.startsWith("/")) return null
            val parts = raw.removePrefix("/").trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val cmd = parts[0].lowercase()
            val rest = parts.drop(1)
            when (cmd) {
                "help", "commands" -> return SlashAction.Help
                "review", "review-render" -> return SlashAction.Review
                "cycle", "ltx-cycle", "qa-cycle" -> {
                    var action = "start"
                    var src: String? = null
                    var rounds: Int? = null
                    for (tok in rest) {
                        val low = tok.lowercase()
                        when {
                            low == "start" || low == "status" || low == "stop" -> action = low
                            low.startsWith("src=") -> src = tok.substring(4)
                            low.startsWith("rounds=") -> rounds = tok.substring(7).toIntOrNull()
                            Regex("""^[a-f0-9]{8,}$""", RegexOption.IGNORE_CASE).matches(tok) -> src = tok
                        }
                    }
                    return SlashAction.Cycle(action, src, rounds)
                }
                else -> return SlashAction.Unknown(cmd)
            }
        }
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
        val kept = messages.filter { it.role in validRoles }.takeLast(MAX_HISTORY_MESSAGES)
        history.addAll(repairToolPairs(kept))
    }

    /**
     * Restores the tool-call pairing invariant the API enforces: every assistant
     * `tool_calls` entry needs a matching `tool` message, and every `tool` message
     * needs its assistant parent.
     *
     * Both are broken in practice — [MAX_HISTORY_MESSAGES] trimming can cut between
     * an assistant message and its results, and cancelling mid-run persists tool
     * calls whose results never arrived. Either one makes the API reject every
     * later turn with a 400, bricking the chat until the user finds Reset. So
     * stub the missing results and drop the orphans rather than sending them.
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
                        content = JsonPrimitive("Interrupted — this tool call did not complete."),
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
     * large tool outputs, and nothing trimmed mid-run, so the request grew until the
     * API refused it. Drops the oldest turns while keeping the system prompt pinned
     * and the tool pairing intact.
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

    /** Sends [userText] plus prior history, resolving any tool calls, emitting UI events. */
    suspend fun send(userText: String, imageDataUrls: List<String> = emptyList()): String {
        val displayText = if (imageDataUrls.isEmpty()) userText
        else if (userText.isBlank()) "[${imageDataUrls.size} image(s) attached]"
        else "$userText [${imageDataUrls.size} image(s) attached]"
        onEvent(AgentEvent.User(displayText))
        history.add(ChatMessage(role = "user", content = MessageContent.multimodal(userText, imageDataUrls)))

        val slash = parseSlash(userText)
        when (slash) {
            is SlashAction.Help -> {
                onEvent(AgentEvent.Venice(SLASH_HELP))
                history.add(ChatMessage(role = "assistant", content = MessageContent.text(SLASH_HELP)))
                return SLASH_HELP
            }
            is SlashAction.Unknown -> {
                val msg = "Unknown slash /${slash.cmd}. Try /help"
                onEvent(AgentEvent.Venice(msg))
                history.add(ChatMessage(role = "assistant", content = MessageContent.text(msg)))
                return msg
            }
            is SlashAction.Cycle -> {
                val payload = buildJsonObject {
                    put("action", slash.action)
                    slash.src?.let { put("src", it) }
                    slash.rounds?.let { put("rounds", it) }
                }.toString()
                onEvent(AgentEvent.ToolCall("render_assess_adjust_cycle", payload))
                val result = toolBox.executeDetailed("render_assess_adjust_cycle", payload)
                onEvent(AgentEvent.ToolOutput("render_assess_adjust_cycle", result.text))
                history.add(ChatMessage(role = "user", content = MessageContent.text(
                    "Slash /cycle ${slash.action} result:\n${result.text}\nSummarize briefly for the user."
                )))
            }
            is SlashAction.Review -> {
                if (imageDataUrls.isEmpty()) {
                    onEvent(AgentEvent.ToolCall("review_latest_render", "{}"))
                    val result = toolBox.executeDetailed("review_latest_render", "{}")
                    onEvent(AgentEvent.ToolOutput("review_latest_render", result.text))
                    if (result.imageDataUrls.isNotEmpty()) {
                        history.add(
                            ChatMessage(
                                role = "user",
                                content = MessageContent.multimodal(
                                    result.text +
                                        "\n\nContact-sheet frames from /review. Analyze left→right, top→bottom. " +
                                        "Do not invent details you cannot see. Still frames only.",
                                    result.imageDataUrls,
                                ),
                            ),
                        )
                    } else {
                        history.add(ChatMessage(role = "user", content = MessageContent.text(result.text)))
                    }
                }
            }
            null -> Unit
        }

        repeat(maxToolIterations) {
            trimHistory()
            val request = ChatCompletionRequest(
                model = model,
                messages = history,
                // E2EE models reject a tools array outright (HTTP 400 "tools is not supported by this model").
                tools = if (model.startsWith("e2ee-")) null else toolBox.definitions.ifEmpty { null },
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
                    val partial = onPartial
                    if (partial != null) partial("")      // a retry starts the text over
                    response = if (partial != null) client.chatCompletionStream(request, partial) else client.chatCompletion(request)
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
                val err = "Error communicating with Venice: ${lastError?.message}"
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
            val toolImages = mutableListOf<String>()
            val alreadyReviewed = slash is SlashAction.Review && imageDataUrls.isEmpty()
            for (call in toolCalls) {
                val callName = call.function.name
                val callArgs = call.function.arguments
                if (alreadyReviewed && callName == "review_latest_render") {
                    onEvent(AgentEvent.ToolCall(callName, callArgs))
                    onEvent(AgentEvent.ToolOutput(callName, "Contact sheet already attached above."))
                    history.add(
                        ChatMessage(
                            role = "tool",
                            content = JsonPrimitive("Contact sheet already attached above."),
                            toolCallId = call.id,
                            name = callName,
                        ),
                    )
                    continue
                }
                onEvent(AgentEvent.ToolCall(callName, callArgs))

                val result = toolBox.executeDetailed(callName, callArgs)
                onEvent(AgentEvent.ToolOutput(callName, result.text))
                toolImages += result.imageDataUrls

                history.add(
                    ChatMessage(
                        role = "tool",
                        content = JsonPrimitive(result.text),
                        toolCallId = call.id,
                        name = callName,
                    ),
                )
            }
            if (toolImages.isNotEmpty()) {
                history.add(
                    ChatMessage(
                        role = "user",
                        content = MessageContent.multimodal(
                            "These image(s) were retrieved by your app integration for the requested task. Analyze them directly and answer the user's original request. Clearly state any limitation of reviewing a still frame rather than full video motion or audio.",
                            toolImages,
                        ),
                    ),
                )
            }
        }

        val limitMsg = "Reached maximum tool iterations ($maxToolIterations)."
        onEvent(AgentEvent.Error(limitMsg))
        return limitMsg
    }
}
