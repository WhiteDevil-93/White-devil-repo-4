package com.whitedevil.desktop

import io.ktor.client.engine.HttpClientEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * The Hub's persistent memory (hub/agentic/store.py memory.json): durable preferences and notes that the web
 * Venice folds into every chat. The native agent used to ignore it. Now the same memory goes into the agent's
 * system prompt on every message, so what you tell Venice on the phone is known on the laptop and the other way round.
 */
data class HubNote(val t: Double?, val text: String)

data class HubMemory(val preferences: Map<String, String>, val notes: List<HubNote>) {
    val isEmpty: Boolean get() = preferences.isEmpty() && notes.isEmpty()
}

private fun JsonElement.asText(): String? = (this as? JsonPrimitive)?.contentOrNull

fun parseHubMemory(json: JsonElement): HubMemory? {
    val o = json as? JsonObject ?: return null
    val prefs = (o["preferences"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> v.asText()?.let { k to it } ?: (v.toString().takeIf { it != "null" }?.let { k to it }) }.toMap()
    val notes = (o["notes"] as? JsonArray).orEmpty().mapNotNull { n ->
        when (n) {
            is JsonObject -> (n["text"]?.asText())?.takeIf { it.isNotBlank() }?.let { HubNote((n["t"] as? JsonPrimitive)?.doubleOrNull, it) }
            is JsonPrimitive -> n.contentOrNull?.takeIf { it.isNotBlank() }?.let { HubNote(null, it) }
            else -> null
        }
    }
    return HubMemory(prefs, notes)
}

/** What the hub adds to the system message (hub/venice.py _memory_preamble): preferences, then the last five notes. */
fun memoryPreamble(m: HubMemory): String = buildList {
    if (m.preferences.isNotEmpty()) {
        add("User preferences: " + buildJsonObject { m.preferences.forEach { (k, v) -> put(k, v) } }.toString().take(1500))
    }
    if (m.notes.isNotEmpty()) {
        add("Recent notes: " + m.notes.takeLast(5).joinToString(" | ") { it.text }.take(1500))
    }
}.joinToString("\n")

/** The system prompt with the memory folded in, worded as the hub words it. */
fun systemPromptWithMemory(base: String, m: HubMemory?): String {
    val pre = m?.let(::memoryPreamble).orEmpty()
    val howTo = "\n\nTo keep something durable for later chats (a preference, or a decision about a project) call the remember tool. Use it sparingly."
    return if (pre.isEmpty()) base + howTo else base + "\n\n[Persistent memory — use naturally, never quote raw to the user]\n" + pre + howTo
}

class HubMemoryClient(hubUrl: String, relayUser: String, relayPass: String, engine: HttpClientEngine? = null) : AutoCloseable {
    private val hub = HubCaller(hubUrl, relayUser, relayPass, engine)

    suspend fun get(): MediaResult<HubMemory> = hub.call("/api/agentic/memory", 15_000) { text ->
        parseHubMemory(Json.parseToJsonElement(text))?.let { MediaResult.Ok(it) } ?: hub.bad("The hub's memory was not what was expected.")
    }

    private suspend fun put(body: JsonObject): MediaResult<HubMemory> = hub.call("/api/agentic/memory", 30_000, post = body.toString(), json = true, method = "PUT") { text ->
        parseHubMemory(Json.parseToJsonElement(text))?.let { MediaResult.Ok(it) } ?: hub.bad("The hub saved the memory but its answer was not what was expected.")
    }

    suspend fun addNote(text: String): MediaResult<HubMemory> {
        if (text.isBlank()) return MediaResult.Failure(MediaError(MediaErrorKind.ClientError, "Write the note first."))
        return put(buildJsonObject { put("append_note", text.trim().take(4000)) })
    }

    suspend fun setPreference(key: String, value: String): MediaResult<HubMemory> {
        if (key.isBlank() || value.isBlank()) return MediaResult.Failure(MediaError(MediaErrorKind.ClientError, "Give both a name and a value."))
        return put(buildJsonObject { put("preferences", buildJsonObject { put(key.trim().take(80), value.trim().take(1000)) }) })
    }

    /** Replaces the notes list (the hub keeps the last 200). Used to delete one. */
    suspend fun replaceNotes(notes: List<HubNote>): MediaResult<HubMemory> =
        put(buildJsonObject { put("notes", buildJsonArray { notes.forEach { n -> add(buildJsonObject { n.t?.let { put("t", it) }; put("text", n.text) }) } }) })

    override fun close() { hub.close() }
}
