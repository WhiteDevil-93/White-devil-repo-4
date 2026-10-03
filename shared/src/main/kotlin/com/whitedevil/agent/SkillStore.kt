package com.whitedevil.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

data class Skill(val name: String, val description: String, val body: String, val enabled: Boolean = true)

/**
 * Skills are named instruction packs. Each is `<dir>/<name>.md`:
 *
 *     ---
 *     name: weekly-report
 *     description: When the user asks for a weekly report
 *     ---
 *     ...instructions the agent follows after calling use_skill...
 *
 * Only name + description go into the system prompt; the body is loaded on demand (progressive
 * disclosure), so many skills cost almost nothing until one is used.
 */
class SkillStore(private val dir: File) {
    private val lock = Any()

    init {
        dir.mkdirs()
    }

    fun list(): List<Skill> = synchronized(lock) {
        val off = disabled()
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: emptyArray())
            .mapNotNull { f -> parse(f.readText())?.copy(enabled = f.nameWithoutExtension !in off) }
            .sortedBy { it.name }
    }

    fun get(name: String): Skill? = list().firstOrNull { it.name == name }

    /** Creates or replaces a skill. Throws IllegalArgumentException for an invalid name or empty body. */
    fun save(name: String, description: String, body: String): Skill = synchronized(lock) {
        require(NAME_RE.matches(name)) { "Skill name must be lowercase letters, digits and dashes (max 40)." }
        require(body.isNotBlank()) { "Skill instructions are empty." }
        require(body.length <= MAX_BODY) { "Skill is over $MAX_BODY characters." }
        val skill = Skill(name, description.trim().replace('\n', ' ').take(200), body.trim())
        File(dir, "$name.md").writeText(render(skill))
        skill
    }

    /** Imports a pasted skill file (front matter with a name). Returns null if it cannot be parsed. */
    fun importText(text: String): Skill? {
        val s = parse(text) ?: return null
        return runCatching { save(s.name, s.description, s.body) }.getOrNull()
    }

    fun delete(name: String) = synchronized(lock) {
        if (NAME_RE.matches(name)) File(dir, "$name.md").delete()
        setEnabledLocked(name, true)
    }

    /**
     * First-run defaults. Each name is written at most once: never over a skill the user already has,
     * and never again after the user deleted it (every name offered is remembered in `.seeded`).
     * Returns how many files were written.
     */
    fun seedDefaults(defaults: Map<String, String>): Int = synchronized(lock) {
        val seeded = File(dir, SEEDED).takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet()
            ?: mutableSetOf()
        var written = 0
        for ((name, text) in defaults) {
            if (name in seeded) continue
            val skill = parse(text)
            val target = File(dir, "$name.md")
            if (skill != null && skill.name == name && !target.exists()) {
                target.writeText(render(skill))
                written++
            }
            seeded.add(name)
        }
        File(dir, SEEDED).writeText(seeded.sorted().joinToString("\n"))
        written
    }

    fun setEnabled(name: String, enabled: Boolean) = synchronized(lock) { setEnabledLocked(name, enabled) }

    private fun setEnabledLocked(name: String, enabled: Boolean) {
        val off = disabled().toMutableSet()
        if (enabled) off.remove(name) else off.add(name)
        File(dir, DISABLED).writeText(off.sorted().joinToString("\n"))
    }

    private fun disabled(): Set<String> =
        File(dir, DISABLED).takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    companion object {
        private const val DISABLED = ".disabled"
        private const val SEEDED = ".seeded"
        const val MAX_BODY = 20_000
        val NAME_RE = Regex("^[a-z0-9][a-z0-9-]{0,39}$")

        fun render(s: Skill) = "---\nname: ${s.name}\ndescription: ${s.description}\n---\n${s.body}\n"

        fun parse(text: String): Skill? {
            val t = text.replace("\r\n", "\n").trimStart()
            if (!t.startsWith("---\n")) return null
            val end = t.indexOf("\n---", 4)
            if (end < 0) return null
            val head = t.substring(4, end).lines().mapNotNull { l ->
                val i = l.indexOf(':')
                if (i < 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
            }.toMap()
            val name = head["name"]?.takeIf { NAME_RE.matches(it) } ?: return null
            val body = t.substring(end + 4).trim()
            if (body.isEmpty()) return null
            return Skill(name, head["description"].orEmpty(), body)
        }
    }
}

/**
 * Offers enabled skills to the model, loads one on request, and lets the agent save a new one.
 * save_skill changes what is sent to the model in every later chat, so it always asks the user
 * ([confirm]) and refuses when there is no confirmation UI.
 */
class SkillTools(
    private val store: SkillStore,
    private val confirm: ((title: String, detail: String) -> Boolean)? = null,
) : ToolExtension {
    private val json = Json { ignoreUnknownKeys = true }

    override val definitions: List<ToolDefinition> = buildList {
        if (store.list().any { it.enabled }) {
            add(
                ToolDefinition(
                    function = ToolFunctionSpec(
                        name = "use_skill",
                        description = "Load the full instructions of one of the available skills, then follow them.",
                        parameters = buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") { putJsonObject("name") { put("type", "string"); put("description", "Skill name from the SKILLS list.") } }
                            put("required", buildJsonArray { add(JsonPrimitive("name")) })
                        },
                    ),
                ),
            )
        }
        add(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "save_skill",
                    description = "Save a new reusable skill (a playbook for a repeatable task). Asks the user first. Read the skill-authoring skill before using it.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("name") { put("type", "string"); put("description", "Lowercase letters, digits and dashes, max 40.") }
                            putJsonObject("description") { put("type", "string"); put("description", "One line: what it is for and when to use it.") }
                            putJsonObject("instructions") { put("type", "string"); put("description", "The steps to follow.") }
                        }
                        put("required", buildJsonArray { add(JsonPrimitive("name")); add(JsonPrimitive("description")); add(JsonPrimitive("instructions")) })
                    },
                ),
            ),
        )
    }

    override fun promptBlock(): String {
        val on = store.list().filter { it.enabled }
        if (on.isEmpty()) return ""
        return "SKILLS — when a request matches one, call use_skill with its name and follow the returned instructions:\n" +
            on.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }

    override fun execute(name: String, argumentsJson: String): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        if (name == "save_skill") return saveSkill(args)
        val want = (args["name"] as? JsonPrimitive)?.content.orEmpty().trim()
        val skill = store.list().firstOrNull { it.enabled && it.name == want }
            ?: return "No enabled skill '$want'. Available: ${store.list().filter { it.enabled }.joinToString { it.name }}"
        return "Skill '${skill.name}' instructions:\n\n${skill.body}"
    }

    private fun saveSkill(args: JsonObject): String {
        fun arg(k: String) = (args[k] as? JsonPrimitive)?.content.orEmpty().trim()
        val name = arg("name")
        val description = arg("description")
        val body = arg("instructions")
        if (!SkillStore.NAME_RE.matches(name)) return "Error: name must be lowercase letters, digits and dashes (max 40)."
        if (body.isEmpty()) return "Error: 'instructions' is empty."
        if (body.length > SkillStore.MAX_BODY) return "Error: instructions are over ${SkillStore.MAX_BODY} characters."
        val exists = store.get(name) != null
        val ask = confirm
        val ok = ask?.invoke(
            if (exists) "Replace skill '$name'?" else "Save new skill '$name'?",
            (if (exists) "This REPLACES your existing skill.\n" else "") + "$description\n\n" + body.take(700) + if (body.length > 700) "\n..." else "",
        ) ?: false
        if (!ok) return "Denied: the user did not approve saving the skill (or no confirmation UI is available). Do not retry; ask the user."
        return try {
            store.save(name, description, body)
            "Saved skill '$name'. The user can edit or remove it in You > Skills."
        } catch (e: IllegalArgumentException) {
            "Error: ${e.message}"
        }
    }
}
