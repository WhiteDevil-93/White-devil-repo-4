package com.whitedevil.desktop.skills

import com.whitedevil.agent.ToolDefinition
import com.whitedevil.agent.ToolExtension
import com.whitedevil.agent.ToolFunctionSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Skills are short playbooks the agent loads when a task matches: a name, a one-line description (what it is for and
 * when to use it), and a body of instructions. They live as `<dir>/<name>/SKILL.md`, so you can read and edit them in
 * any editor; the agent sees only the index until it calls use_skill.
 *
 * Defaults ship inside the app (resources/skills) and are copied into the folder once. After that the folder is yours:
 * edits stay, and a default you delete is not brought back (see [seedDefaults]).
 */
data class Skill(val name: String, val description: String, val body: String)

class SkillStore(val dir: File) {
    fun list(): List<Skill> = dir.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        .mapNotNull { d -> File(d, FILE).takeIf { it.isFile }?.let { parse(d.name, runCatching { it.readText() }.getOrDefault("")) } }

    fun get(name: String): Skill? = list().firstOrNull { it.name == clean(name) }

    /** Creates or replaces a skill. Returns an error message, or null when saved. */
    fun save(name: String, description: String, body: String): String? {
        val n = clean(name)
        if (n.isEmpty()) return "Give the skill a name using letters, digits and dashes."
        if (description.isBlank()) return "A skill needs a one-line description saying when to use it."
        if (body.isBlank()) return "A skill needs instructions."
        val d = File(dir, n)
        d.mkdirs()
        File(d, FILE).writeText(render(n, description.trim().replace(Regex("\\s+"), " "), body.trim()))
        return null
    }

    fun delete(name: String): Boolean = File(dir, clean(name)).takeIf { it.isDirectory && clean(name).isNotEmpty() }?.deleteRecursively() ?: false

    /** Copies the bundled defaults that this folder has never been given. Returns how many were added. */
    fun seedDefaults(source: (String) -> String? = ::bundled): Int {
        dir.mkdirs()
        val marker = File(dir, SEEDED)
        val seen = if (marker.isFile) marker.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet() else mutableSetOf()
        var added = 0
        val names = source("_index")?.lines().orEmpty().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        for (n in names) {
            if (n in seen) continue
            val text = source(n) ?: continue
            val f = File(dir, "$n/$FILE")
            if (!f.exists()) { f.parentFile.mkdirs(); f.writeText(text); added++ }
            seen += n
        }
        runCatching { marker.writeText(seen.sorted().joinToString("\n")) }
        return added
    }

    /** Puts every bundled default back, overwriting edits to them. Your own skills are untouched. */
    fun resetDefaults(source: (String) -> String? = ::bundled): Int {
        File(dir, SEEDED).delete()
        val names = source("_index")?.lines().orEmpty().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        names.forEach { File(dir, "$it/$FILE").delete() }
        return seedDefaults(source)
    }

    companion object {
        const val FILE = "SKILL.md"
        private const val SEEDED = ".seeded"

        fun clean(name: String): String = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)

        fun bundled(name: String): String? = SkillStore::class.java.classLoader.getResourceAsStream("skills/$name.md")?.use { it.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n") }
            ?: if (name == "_index") SkillStore::class.java.classLoader.getResourceAsStream("skills/_index.txt")?.use { it.readBytes().toString(Charsets.UTF_8) } else null

        fun render(name: String, description: String, body: String) = "---\nname: $name\ndescription: $description\n---\n\n$body\n"

        /** `---` front matter with name and description, then the body. A file without front matter still loads. */
        fun parse(folder: String, text: String): Skill {
            val t = text.replace("\r\n", "\n")
            if (!t.startsWith("---\n")) return Skill(folder, "", t.trim())
            val end = t.indexOf("\n---", 4)
            if (end < 0) return Skill(folder, "", t.trim())
            val head = t.substring(4, end).lines().mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
            return Skill(clean(head["name"] ?: folder).ifEmpty { folder }, head["description"].orEmpty(), t.substring(end + 4).trim())
        }
    }
}

/** The index the agent always sees: one line per skill, plus how to use them. */
fun systemPromptWithSkills(base: String, skills: List<Skill>): String {
    if (skills.isEmpty()) return base
    val lines = skills.joinToString("\n") { "- ${it.name}: ${it.description.take(220)}" }
    return base + "\n\n[Skills — short playbooks you wrote for yourself or the user wrote. When the task matches one, call use_skill(name) FIRST and follow it. " +
        "If you work out a procedure worth repeating, save it with save_skill.]\n" + lines
}

/** list_skills / use_skill / save_skill for the agent. */
class SkillsExtension(private val store: SkillStore) : ToolExtension {
    private fun spec(name: String, description: String, props: Map<String, String> = emptyMap(), required: List<String> = emptyList()) = ToolDefinition(
        function = ToolFunctionSpec(
            name = name, description = description,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { props.forEach { (k, v) -> putJsonObject(k) { put("type", "string"); put("description", v) } } }
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
            },
        ),
    )

    override fun definitions() = listOf(
        spec("list_skills", "List the available skills with their descriptions."),
        spec("use_skill", "Load a skill's full instructions by name and follow them. Use it before starting a task a skill covers.", mapOf("name" to "The skill's name from the list."), listOf("name")),
        spec(
            "save_skill", "Create or replace a skill: a reusable playbook for a kind of task. Only save procedures that were verified to work or that the user stated.",
            mapOf("name" to "Short dash-separated name.", "description" to "One line: what it is for and when to use it.", "body" to "The instructions, in markdown."),
            listOf("name", "description", "body"),
        ),
    )

    override fun handles(name: String) = name == "list_skills" || name == "use_skill" || name == "save_skill"

    override fun execute(name: String, argumentsJson: String): String {
        val args = runCatching { Json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        fun arg(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (name) {
            "list_skills" -> store.list().joinToString("\n") { "${it.name}: ${it.description}" }.ifEmpty { "No skills yet." }
            "use_skill" -> store.get(arg("name"))?.let { "# ${it.name}\n\n${it.body}" }
                ?: "Error: no skill named '${arg("name")}'. Available: " + store.list().joinToString(", ") { it.name }
            "save_skill" -> store.save(arg("name"), arg("description"), arg("body"))?.let { "Error: $it" } ?: "Saved skill '${SkillStore.clean(arg("name"))}'."
            else -> "Error: unknown tool '$name'."
        }
    }
}

/** Several tool sources behind the single hook ToolBox has; the first one that handles a name runs it. */
class CompositeExtension(private val parts: List<ToolExtension>) : ToolExtension {
    override fun definitions() = parts.flatMap { it.definitions() }
    override fun handles(name: String) = parts.any { it.handles(name) }
    override fun execute(name: String, argumentsJson: String) = parts.first { it.handles(name) }.execute(name, argumentsJson)
}
