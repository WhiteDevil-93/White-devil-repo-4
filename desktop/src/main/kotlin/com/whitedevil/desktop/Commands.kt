package com.whitedevil.desktop

import com.whitedevil.desktop.skills.Skill

/**
 * The chat box's command layer.
 *
 * `/` runs a command: app commands (/new, /chats, /memory, /remember, ...), the agent's own (/review, /cycle), and every
 * skill by name (/wan-14b-prompting a prompt to review). `@` points at something: a skill, a connector, a render clip
 * on the hub, or `@file` to attach a file. Typing either opens a menu above the box (arrows, Tab/Enter to pick).
 */
data class CommandDef(val name: String, val usage: String, val detail: String, val local: Boolean)

data class Suggestion(val insert: String, val label: String, val detail: String, val attachFile: Boolean = false)

/** A render on the hub that `@clip:` can point at. */
data class ClipRef(val name: String, val pretty: String)

object Commands {
    val BUILT_IN = listOf(
        CommandDef("help", "", "List every command, skill and mention", true),
        CommandDef("new", "", "Start a new chat (this one is kept in CHATS)", true),
        CommandDef("chats", "[search words]", "Open saved chats, optionally searching them", true),
        CommandDef("skills", "", "Browse and edit skills", true),
        CommandDef("connectors", "", "Open the MCP connectors", true),
        CommandDef("memory", "", "Open Venice's memory", true),
        CommandDef("remember", "<note>", "Save a note to the Hub's memory right now", true),
        CommandDef("attach", "", "Attach pictures or text files", true),
        CommandDef("tools", "", "Show or hide tool calls", true),
        CommandDef("settings", "", "Open settings", true),
        CommandDef("review", "", "Look at the latest render's contact sheet (needs a vision model)", false),
        CommandDef("cycle", "start|status|stop [src=<job>] [rounds=N]", "Run the review-and-fix render cycle", false),
    )

    sealed interface Resolved {
        /** An app command to run here; [args] is the rest of the line. */
        data class Local(val name: String, val args: String) : Resolved
        /** A skill: the agent gets [forAgent], the chat shows [shown]. */
        data class Rewrite(val forAgent: String, val shown: String) : Resolved
        /** Goes to the agent as typed (the agent understands /review and /cycle itself). */
        data object PassThrough : Resolved
        data class Unknown(val typed: String, val close: List<String>) : Resolved
    }

    private val nameChars = Regex("^[a-z0-9][a-z0-9-]*$")

    /** Null when [text] is not a command (an ordinary message, or something like a path "/api/status"). */
    fun resolve(text: String, skills: List<Skill>): Resolved? {
        val t = text.trim()
        if (!t.startsWith("/") || t.length < 2) return null
        val token = t.drop(1).takeWhile { !it.isWhitespace() }
        val name = token.lowercase()
        if (!nameChars.matches(name)) return null
        val args = t.drop(1 + token.length).trim()
        BUILT_IN.firstOrNull { it.name == name }?.let { return if (it.local) Resolved.Local(it.name, args) else Resolved.PassThrough }
        if (name == "skill") {
            val skillName = args.takeWhile { !it.isWhitespace() }.lowercase()
            val s = skills.firstOrNull { it.name == skillName } ?: return Resolved.Unknown("/skill $skillName", close(skillName, skills))
            return rewrite(s, args.drop(skillName.length).trim(), t)
        }
        skills.firstOrNull { it.name == name }?.let { return rewrite(it, args, t) }
        return Resolved.Unknown("/$name", close(name, skills))
    }

    private fun rewrite(s: Skill, args: String, shown: String) = Resolved.Rewrite(
        if (args.isEmpty()) "Use the skill \"${s.name}\": call use_skill first, then follow it for the current situation."
        else "Use the skill \"${s.name}\": call use_skill first, then follow it for this task: $args",
        shown,
    )

    private fun close(name: String, skills: List<Skill>): List<String> =
        (BUILT_IN.map { it.name } + skills.map { it.name }).filter { it.startsWith(name.take(2)) || name in it }.take(3).map { "/$it" }

    /** The text the user sees for /help. */
    fun helpText(skills: List<Skill>): String = buildString {
        appendLine("Commands (type / for a menu):")
        BUILT_IN.forEach { appendLine("/${it.name}${if (it.usage.isEmpty()) "" else " " + it.usage} — ${it.detail}") }
        appendLine()
        appendLine("Mentions (type @ for a menu): @skill-name, @connector-name, @clip:<render> to point at a render, @file to attach a file.")
        if (skills.isNotEmpty()) {
            appendLine()
            appendLine("Skills — run any with /name followed by your task:")
            skills.forEach { appendLine("/${it.name} — ${it.description.take(110)}") }
        }
    }.trim()

    // ---------------------------------------------------------------- the menu

    /**
     * What to offer for the text typed so far. A `/` menu shows while the whole box is a single `/word`; an `@` menu
     * shows while the last word starts with `@`. At most [MAX] rows.
     */
    fun suggestions(text: String, skills: List<Skill>, connectors: List<String>, clips: List<ClipRef>): List<Suggestion> {
        if (text.startsWith("/") && text.none { it.isWhitespace() }) {
            val q = text.drop(1).lowercase()
            val cmds = BUILT_IN.map { Suggestion("/${it.name} ", "/${it.name}" + if (it.usage.isEmpty()) "" else " ${it.usage}", it.detail) }
            val sk = skills.map { Suggestion("/${it.name} ", "/${it.name}", it.description) }
            return rank(cmds + sk, q) { it.label.drop(1).substringBefore(' ') }
        }
        val last = text.takeLastWhile { !it.isWhitespace() }
        if (last.startsWith("@")) {
            val q = last.drop(1).lowercase()
            if (q.startsWith("clip:")) {
                val cq = q.removePrefix("clip:")
                return clips.filter { cq.isEmpty() || cq in it.name.lowercase() || cq in it.pretty.lowercase() }.take(MAX)
                    .map { Suggestion("@clip:${it.name} ", it.pretty, it.name) }
            }
            val all = listOf(Suggestion("@file", "@file", "Attach a picture or text file", attachFile = true), Suggestion("@clip:", "@clip:", "Point at a render on the hub")) +
                skills.map { Suggestion("@${it.name} ", "@${it.name}", "Skill: " + it.description) } +
                connectors.map { Suggestion("@$it ", "@$it", "Connector: use its tools") }
            return rank(all, q) { it.label.drop(1) }
        }
        return emptyList()
    }

    private fun rank(all: List<Suggestion>, q: String, key: (Suggestion) -> String): List<Suggestion> {
        if (q.isEmpty()) return all.take(MAX)
        val starts = all.filter { key(it).lowercase().startsWith(q) }
        val contains = all.filter { it !in starts && (q in key(it).lowercase() || (q.length >= 3 && q in it.detail.lowercase())) }
        return (starts + contains).take(MAX)
    }

    /** Puts the chosen suggestion in: a `/word` replaces the whole box, an `@word` replaces the last word. */
    fun complete(text: String, s: Suggestion): String =
        if (text.startsWith("/") && text.none { it.isWhitespace() }) s.insert
        else text.dropLast(text.takeLastWhile { !it.isWhitespace() }.length) + s.insert

    /**
     * The message the agent receives: your text, plus a short hint for each @mention so it acts on it
     * ("[Use the skill ...]", "[Use the google-drive connector's tools]", "[Hub clip file: /clips/NAME]").
     */
    fun expandMentions(text: String, skills: List<Skill>, connectors: List<String>): String {
        val hints = linkedSetOf<String>()
        Regex("@([A-Za-z0-9_.:-]+)").findAll(text).forEach { m ->
            val w = m.groupValues[1].trimEnd('.', ',', ':')
            when {
                w.startsWith("clip:", ignoreCase = true) -> w.substringAfter(':').takeIf { it.isNotBlank() }?.let { hints += "[Hub clip file: /clips/$it]" }
                skills.any { it.name == w.lowercase() } -> hints += "[Use the skill \"${w.lowercase()}\": call use_skill first.]"
                connectors.any { it.equals(w, true) } -> hints += "[Use the ${w.lowercase()} connector's tools (named ${w.lowercase()}__...) for this.]"
            }
        }
        return if (hints.isEmpty()) text else text.trimEnd() + "\n\n" + hints.joinToString("\n")
    }

    /** Removes the "[Use the skill ...]" / "[Hub clip file: ...]" hint lines that [expandMentions] appended. */
    fun stripHints(text: String): String =
        text.lines().filterNot { it.startsWith("[Use the ") || it.startsWith("[Hub clip file:") }.joinToString("\n").trimEnd()

    const val MAX = 8
}
