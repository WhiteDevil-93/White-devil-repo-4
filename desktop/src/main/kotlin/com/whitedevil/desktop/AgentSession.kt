package com.whitedevil.desktop

import androidx.compose.runtime.mutableStateListOf
import com.whitedevil.agent.Agent
import com.whitedevil.agent.ChatMessage
import com.whitedevil.agent.stripBlobs
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The Venice conversations, kept for good. A conversation used to live inside the Venice screen (leaving it threw it
 * away) and there was only ever one. Now the app keeps a library: every chat is a file in `<dir>/chats`, the one you
 * are in is [currentId], and NEW CHAT starts a fresh one while the old stays in the library to be searched and reopened.
 *
 * - [history] is what the agent remembers (its messages), saved after every run, including a stopped one. Image data is
 *   stripped so files stay small.
 * - [lines] is what the chat shows, rebuilt from the history when a chat is opened.
 * - With `dir == null` nothing is written (tests, previews).
 */
@Serializable
data class StoredChat(val id: String, val title: String, val updated: Long, val messages: List<ChatMessage>)

data class ChatMeta(val id: String, val title: String, val updated: Long, val messageCount: Int, val snippet: String = "")

/** All runs of whitespace (spaces, tabs, line breaks) become single spaces. */
internal fun String.oneLine(): String = split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

class AgentSession(private val dir: File?) {
    val lines = mutableStateListOf<ChatLine>()

    var history: List<ChatMessage> = emptyList()
        private set

    var currentId: String = newId()
        private set

    private val chatsDir get() = dir?.let { File(it, "chats") }

    init {
        migrateOldSingleHistory()
        val saved = dir?.let { File(it, CURRENT).takeIf { f -> f.isFile }?.readText()?.trim() }.orEmpty()
        // A chat id with no file is a fresh chat you started and have not typed in yet: stay in it.
        // With no marker at all (first run, or a version before the library), open the newest chat.
        if (saved.isNotEmpty() && read(saved) == null) currentId = AgentSession.clean(saved)
        val chat = if (saved.isNotEmpty()) read(saved) else list().firstOrNull()?.let { read(it.id) }
        if (chat != null) { currentId = chat.id; history = lean(chat.messages); lines.addAll(linesFrom(history)) }
    }

    /** The agent finished (or was stopped): remember its conversation under the current chat. */
    fun adopt(messages: List<ChatMessage>) {
        history = lean(messages)
        save()
    }

    /** Starts a fresh chat. The one you were in stays in the library. The Hub's memory is separate and stays too. */
    fun newChat() {
        lines.clear(); history = emptyList(); currentId = newId(); rememberCurrent()
    }

    /** Opens a saved chat. Returns false if it can't be read. */
    fun open(id: String): Boolean {
        val c = read(id) ?: return false
        lines.clear(); currentId = c.id; history = lean(c.messages); lines.addAll(linesFrom(history)); rememberCurrent()
        return true
    }

    /** Deletes a saved chat; if it was the open one, a fresh chat starts. */
    fun delete(id: String) {
        runCatching { chatFile(id)?.delete() }
        if (id == currentId) newChat()
    }

    /** Saved chats, newest first. */
    fun list(): List<ChatMeta> = chatsDir?.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { f -> readFile(f)?.let { ChatMeta(it.id, it.title, it.updated, it.messages.size) } }
        .sortedByDescending { it.updated }

    /** Chats whose title or any message contains [query] (case-insensitive), with a snippet around the first hit. */
    fun search(query: String): List<ChatMeta> {
        val q = query.trim()
        if (q.isEmpty()) return list()
        return chatsDir?.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
            val c = readFile(f) ?: return@mapNotNull null
            val hit = c.messages.firstNotNullOfOrNull { m -> m.textContent().takeIf { it.contains(q, ignoreCase = true) } }
            if (!c.title.contains(q, ignoreCase = true) && hit == null) return@mapNotNull null
            ChatMeta(c.id, c.title, c.updated, c.messages.size, hit?.let { snippet(it, q) }.orEmpty())
        }.sortedByDescending { it.updated }
    }

    private fun snippet(text: String, q: String): String {
        val i = text.indexOf(q, ignoreCase = true).coerceAtLeast(0)
        return text.substring((i - 40).coerceAtLeast(0), (i + q.length + 60).coerceAtMost(text.length)).oneLine()
    }

    private fun chatFile(id: String) = chatsDir?.let { File(it, clean(id) + ".json") }

    private fun save() {
        val f = chatFile(currentId) ?: return
        if (history.isEmpty()) return
        val chat = StoredChat(currentId, titleOf(history), System.currentTimeMillis(), history)
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(StoredChat.serializer(), chat))
            // renameTo will not replace an existing file on Windows.
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        rememberCurrent()
    }

    private fun rememberCurrent() { runCatching { dir?.let { it.mkdirs(); File(it, CURRENT).writeText(currentId) } } }

    private fun read(id: String): StoredChat? = chatFile(id)?.let(::readFile)

    // A damaged file must never stop the screen opening; that chat is just skipped.
    private fun readFile(f: File): StoredChat? = runCatching { json.decodeFromString(StoredChat.serializer(), f.readText()) }.getOrNull()

    /** The single agent_history.json of earlier versions becomes the first chat in the library. */
    private fun migrateOldSingleHistory() {
        val d = dir ?: return
        val old = File(d, "agent_history.json")
        if (!old.isFile) return
        runCatching {
            val msgs = lean(json.decodeFromString(ListSerializer(ChatMessage.serializer()), old.readText()))
            if (msgs.isNotEmpty() && list().isEmpty()) {
                val id = newId()
                val f = File(chatsDir!!, "$id.json"); f.parentFile.mkdirs()
                f.writeText(json.encodeToString(StoredChat.serializer(), StoredChat(id, titleOf(msgs), old.lastModified(), msgs)))
                File(d, CURRENT).writeText(id)
            }
            old.delete()
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private const val CURRENT = "current_chat.txt"

        fun load(dir: File = Settings.dir) = AgentSession(dir)

        fun newId(): String = "c" + System.currentTimeMillis().toString(36) + (0..999).random().toString(36)
        fun clean(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40)

        /** A chat's name: its first thing you said, trimmed. */
        fun titleOf(messages: List<ChatMessage>): String =
            messages.firstOrNull { it.role == "user" }?.textContent()?.oneLine()?.trim()?.take(60)?.ifEmpty { null } ?: "New chat"

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
                    "user" -> m.textContent().takeIf { it.isNotBlank() }?.let { out += ChatLine(ROLE_USER, "You", Attachments.forDisplay(it)) }
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
