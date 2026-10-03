package com.whitedevil.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class Project(
    val id: String,
    val name: String,
    /** Standing instructions added to the system prompt of every chat in this project. */
    val instructions: String = "",
    val createdAt: Long,
)

/** Named workspaces. A chat can belong to one project (see [ConversationMeta.projectId]). One JSON file. */
class ProjectStore(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = ListSerializer(Project.serializer())
    private val lock = Any()

    fun list(): List<Project> = synchronized(lock) { read().sortedBy { it.name.lowercase() } }

    fun get(id: String?): Project? = if (id == null) null else synchronized(lock) { read().firstOrNull { it.id == id } }

    fun create(name: String, instructions: String = ""): Project = synchronized(lock) {
        val clean = name.trim().take(MAX_NAME)
        require(clean.isNotEmpty()) { "Give the project a name." }
        val all = read()
        require(all.none { it.name.equals(clean, ignoreCase = true) }) { "A project called '$clean' already exists." }
        val p = Project(UUID.randomUUID().toString().replace("-", "").take(8), clean, instructions.trim().take(MAX_INSTRUCTIONS), now())
        write(all + p)
        p
    }

    fun update(id: String, name: String, instructions: String) = synchronized(lock) {
        val clean = name.trim().take(MAX_NAME)
        require(clean.isNotEmpty()) { "Give the project a name." }
        val all = read()
        require(all.none { it.id != id && it.name.equals(clean, ignoreCase = true) }) { "A project called '$clean' already exists." }
        write(all.map { if (it.id == id) it.copy(name = clean, instructions = instructions.trim().take(MAX_INSTRUCTIONS)) else it })
    }

    fun delete(id: String) = synchronized(lock) { write(read().filter { it.id != id }) }

    /** Block for the system prompt, or "" when [projectId] is null or unknown. */
    fun promptBlock(projectId: String?): String {
        val p = get(projectId) ?: return ""
        return "PROJECT \"${p.name}\" — you are working inside this project. Follow its instructions for every reply here:\n" +
            p.instructions.ifBlank { "(no extra instructions)" }
    }

    private fun read(): List<Project> =
        if (!file.isFile) emptyList() else runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList())

    private fun write(items: List<Project>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(ser, items))
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "could not write projects" } }
    }

    companion object {
        const val MAX_NAME = 60
        const val MAX_INSTRUCTIONS = 4000
    }
}
