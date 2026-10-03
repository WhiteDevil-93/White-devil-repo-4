package com.whitedevil.desktop

import androidx.compose.runtime.mutableStateListOf
import com.whitedevil.agent.Agent
import com.whitedevil.agent.ChatMessage
import com.whitedevil.agent.stripBlobs
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The Venice conversation, kept for good. It used to live inside the Venice screen: leaving the screen threw it away,
 * and every message started a brand-new agent that had never seen the one before. Now there is one session for the app.
 *
 * - [history] is what the agent remembers (its messages), saved to agent_history.json next to the settings after every
 *   run, including a stopped one, and loaded again at start. Image data is stripped so the file stays small.
 * - [lines] is what the chat shows, rebuilt from the history when the app starts.
 */
class AgentSession(private val file: File?) {
    val lines = mutableStateListOf<ChatLine>()

    var history: List<ChatMessage> = emptyList()
        private set

    init {
        history = load()
        lines.addAll(linesFrom(history))
    }

    /** The agent finished (or was stopped): remember its conversation. */
    fun adopt(messages: List<ChatMessage>) {
        history = lean(messages)
        save()
    }

    /** "New chat": forget the conversation. The Hub's persistent memory (preferences, notes) is separate and stays. */
    fun clear() {
        lines.clear()
        history = emptyList()
        runCatching { file?.delete() }
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(ChatMessage.serializer()), history))
            // renameTo will not replace an existing file on Windows.
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    private fun load(): List<ChatMessage> = runCatching {
        val f = file ?: return emptyList()
        if (!f.isFile) emptyList()
        // A damaged file must never stop the screen opening; the conversation just starts empty.
        else lean(json.decodeFromString(ListSerializer(ChatMessage.serializer()), f.readText()))
    }.getOrDefault(emptyList())

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(dir: File = Settings.dir) = AgentSession(File(dir, "agent_history.json"))

        /** What is worth keeping: the conversation roles, the last [Agent.MAX_HISTORY_MESSAGES], without picture data. */
        fun lean(messages: List<ChatMessage>): List<ChatMessage> = messages
            .filter { it.role in setOf("user", "assistant", "tool") }
            .takeLast(Agent.MAX_HISTORY_MESSAGES)
            .map { it.copy(content = stripBlobs(it.content)) }

        /** The chat lines for a restored conversation (the same shapes the live events produce). */
        fun linesFrom(history: List<ChatMessage>): List<ChatLine> {
            val callNames = HashMap<String, String>()
            val out = mutableListOf<ChatLine>()
            for (m in history) {
                when (m.role) {
                    "user" -> m.textContent().takeIf { it.isNotBlank() }?.let { out += ChatLine(ROLE_USER, "You", it) }
                    "assistant" -> {
                        m.textContent().takeIf { it.isNotBlank() }?.let { out += ChatLine(ROLE_VENICE, "Venice", it) }
                        m.toolCalls.orEmpty().forEach { c ->
                            callNames[c.id] = c.function.name
                            out += ChatLine(ROLE_TOOL_CALL, "Tool · ${c.function.name}", c.function.arguments)
                        }
                    }
                    "tool" -> {
                        val name = m.name ?: m.toolCallId?.let { callNames[it] } ?: "tool"
                        out += ChatLine(ROLE_TOOL_OUT, "Output · $name", m.textContent())
                    }
                }
            }
            return out
        }
    }
}

// ---------------------------------------------------------------- showing the conversation

sealed interface ChatItem {
    data class Message(val line: ChatLine) : ChatItem
    /** A run of tool calls and their outputs between two real messages. Collapsed to one line unless asked for. */
    data class Tools(val lines: List<ChatLine>, val id: Int) : ChatItem {
        val names: List<String> get() = lines.filter { it.role == ROLE_TOOL_CALL }.map { it.title.removePrefix("Tool · ") }
        val summary: String get() {
            val n = names
            val shown = n.groupingBy { it }.eachCount().entries.joinToString(", ") { (name, c) -> if (c > 1) "$name ×$c" else name }
            return if (n.isEmpty()) "Tool output" else "Used ${if (n.size == 1) "a tool" else "${n.size} tools"}: $shown"
        }
    }
}

/** Consecutive tool calls and outputs become one [ChatItem.Tools]; everything else stays a message. */
fun groupChat(lines: List<ChatLine>): List<ChatItem> {
    val out = mutableListOf<ChatItem>()
    var run = mutableListOf<ChatLine>()
    fun flush() { if (run.isNotEmpty()) { out += ChatItem.Tools(run.toList(), out.size); run = mutableListOf() } }
    for (l in lines) {
        if (l.role == ROLE_TOOL_CALL || l.role == ROLE_TOOL_OUT) run += l else { flush(); out += ChatItem.Message(l) }
    }
    flush()
    return out
}
