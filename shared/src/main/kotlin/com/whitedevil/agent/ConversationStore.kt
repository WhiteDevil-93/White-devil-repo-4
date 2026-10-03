package com.whitedevil.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class ConversationMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int = 0,
    val pinned: Boolean = false,
    /** Optional [Project] this chat belongs to. Defaulted so chats saved before projects existed still load. */
    val projectId: String? = null,
)

data class SearchHit(val meta: ConversationMeta, val snippet: String)

@Serializable
private data class ConversationIndex(
    val currentId: String? = null,
    val items: List<ConversationMeta> = emptyList(),
)

/**
 * Many chats instead of one. Layout under [dir]: `index.json` plus one `<id>.json` per conversation.
 * Files are written atomically (temp + rename). Ids are validated before they touch a path.
 */
class ConversationStore(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val listSer = ListSerializer(ChatMessage.serializer())

    init {
        dir.mkdirs()
    }

    fun list(): List<ConversationMeta> = synchronized(lock) {
        readIndex().items.sortedWith(compareByDescending<ConversationMeta> { it.pinned }.thenByDescending { it.updatedAt })
    }

    fun currentId(): String? = synchronized(lock) {
        val idx = readIndex()
        idx.currentId?.takeIf { id -> idx.items.any { it.id == id } }
    }

    fun setCurrent(id: String) = synchronized(lock) {
        val idx = readIndex()
        if (idx.items.any { it.id == id }) writeIndex(idx.copy(currentId = id))
    }

    fun create(title: String = DEFAULT_TITLE, projectId: String? = null): ConversationMeta = synchronized(lock) {
        val t = now()
        val meta = ConversationMeta(id = newId(), title = title.trim().ifBlank { DEFAULT_TITLE }.take(MAX_TITLE), createdAt = t, updatedAt = t, projectId = projectId)
        val idx = readIndex()
        writeAtomic(fileFor(meta.id), json.encodeToString(listSer, emptyList()))
        writeIndex(idx.copy(currentId = meta.id, items = idx.items + meta))
        meta
    }

    fun load(id: String): List<ChatMessage> = synchronized(lock) {
        val f = fileFor(id)
        if (!f.isFile) return emptyList()
        runCatching { json.decodeFromString(listSer, f.readText()) }.getOrDefault(emptyList())
    }

    /** Saves [messages] (system prompt and image blobs dropped) and auto-titles a still-untitled chat. */
    fun save(id: String, messages: List<ChatMessage>): ConversationMeta? = synchronized(lock) {
        val idx = readIndex()
        val old = idx.items.firstOrNull { it.id == id } ?: return null
        val lean = messages.filter { it.role != "system" }.map { it.copy(content = stripBlobs(it.content)) }
        writeAtomic(fileFor(id), json.encodeToString(listSer, lean))
        val title = if (old.title == DEFAULT_TITLE) autoTitle(lean) ?: old.title else old.title
        val updated = old.copy(title = title, updatedAt = now(), messageCount = lean.count { it.role == "user" || it.role == "assistant" })
        writeIndex(idx.copy(items = idx.items.map { if (it.id == id) updated else it }))
        updated
    }

    fun rename(id: String, title: String) = synchronized(lock) {
        val idx = readIndex()
        val clean = title.trim().take(MAX_TITLE)
        if (clean.isNotEmpty()) writeIndex(idx.copy(items = idx.items.map { if (it.id == id) it.copy(title = clean) else it }))
    }

    fun pin(id: String, pinned: Boolean) = synchronized(lock) {
        val idx = readIndex()
        writeIndex(idx.copy(items = idx.items.map { if (it.id == id) it.copy(pinned = pinned) else it }))
    }

    /** Moves a chat into [projectId] (null = out of any project). */
    fun setProject(id: String, projectId: String?) = synchronized(lock) {
        val idx = readIndex()
        writeIndex(idx.copy(items = idx.items.map { if (it.id == id) it.copy(projectId = projectId) else it }))
    }

    /** When a project is deleted its chats stay, just outside any project. */
    fun detachProject(projectId: String) = synchronized(lock) {
        val idx = readIndex()
        writeIndex(idx.copy(items = idx.items.map { if (it.projectId == projectId) it.copy(projectId = null) else it }))
    }

    fun meta(id: String): ConversationMeta? = synchronized(lock) { readIndex().items.firstOrNull { it.id == id } }

    fun delete(id: String) = synchronized(lock) {
        val idx = readIndex()
        if (idx.items.none { it.id == id }) return@synchronized
        fileFor(id).delete()
        val rest = idx.items.filter { it.id != id }
        val current = if (idx.currentId == id) rest.maxByOrNull { it.updatedAt }?.id else idx.currentId
        writeIndex(idx.copy(currentId = current, items = rest))
    }

    /** Case-insensitive search over titles and message text, newest first. */
    fun search(query: String, limit: Int = 30): List<SearchHit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return list().sortedByDescending { it.updatedAt }.mapNotNull { meta ->
            if (meta.title.lowercase().contains(q)) return@mapNotNull SearchHit(meta, meta.title)
            val hit = load(meta.id)
                .filter { it.role == "user" || it.role == "assistant" }
                .firstNotNullOfOrNull { m ->
                    val text = m.textContent()
                    val at = text.lowercase().indexOf(q)
                    if (at < 0) null else text.substring(maxOf(0, at - 30), minOf(text.length, at + q.length + 50)).replace('\n', ' ')
                }
            hit?.let { SearchHit(meta, it) }
        }.take(limit)
    }

    fun exportMarkdown(id: String): String {
        val meta = list().firstOrNull { it.id == id } ?: return ""
        return buildString {
            append("# ").append(meta.title).append("\n\n")
            load(id).forEach { m ->
                val who = when (m.role) { "user" -> "You"; "assistant" -> "Assistant"; else -> return@forEach }
                val text = m.textContent().trim()
                if (text.isNotEmpty()) append("**").append(who).append(":**\n").append(text).append("\n\n")
            }
        }.trimEnd() + "\n"
    }

    /** One-time migration of the old single-chat file. Returns the new conversation, or null if nothing to do. */
    fun importLegacy(file: File): ConversationMeta? {
        if (!file.isFile || list().isNotEmpty()) return null
        val msgs = runCatching { json.decodeFromString(listSer, file.readText()) }.getOrNull().orEmpty()
        if (msgs.none { it.role == "user" || it.role == "assistant" }) return null
        val meta = create()
        val saved = save(meta.id, msgs)
        file.renameTo(File(file.path + ".migrated"))
        return saved ?: meta
    }

    // ---- internals ----

    private fun autoTitle(msgs: List<ChatMessage>): String? =
        msgs.firstOrNull { it.role == "user" }?.textContent()?.trim()?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotEmpty() }?.take(48)

    private fun fileFor(id: String): File {
        require(ID_RE.matches(id)) { "bad conversation id" }
        return File(dir, "$id.json")
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(12)

    private fun readIndex(): ConversationIndex {
        val f = File(dir, "index.json")
        if (!f.isFile) return ConversationIndex()
        return runCatching { json.decodeFromString(ConversationIndex.serializer(), f.readText()) }.getOrDefault(ConversationIndex())
    }

    private fun writeIndex(idx: ConversationIndex) = writeAtomic(File(dir, "index.json"), json.encodeToString(ConversationIndex.serializer(), idx))

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "could not write ${target.name}" }
        }
    }

    companion object {
        const val DEFAULT_TITLE = "New chat"
        private const val MAX_TITLE = 80
        private val ID_RE = Regex("^[a-f0-9]{12}$")
    }
}
