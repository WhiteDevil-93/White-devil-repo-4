package com.whitedevil.desktop

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Shapes returned by `GET /api/media/library` (hub/media.py `library()`).
 *
 * Every field here exists in the real response; nothing is invented. Notably the
 * hub sends NO status field: a group has a `kind` (pack / chain / keeper / test),
 * a pipeline `source` (ltx / thunder / vast) and modification times, so that is
 * all the UI shows. Anything the hub may omit is nullable rather than defaulted,
 * because a made-up 0 would render as "56 years old" or "0 MB".
 */
data class MediaClip(
    val name: String,
    val idx: Int?,
    /** Seconds since the epoch on the hub host's clock (Python `st_mtime`). */
    val mtime: Double?,
    val mb: Double?,
    val source: String?,
)

data class MediaGroup(
    val id: String,
    val title: String,
    val kind: String?,
    val source: String?,
    /** Newest clip mtime, as reported by the hub (or derived from the clips if the hub omitted it). */
    val updated: Double?,
    val clips: List<MediaClip>,
) {
    /** Sum of clip sizes, or null when any clip is missing its size (a partial sum would understate). */
    val totalMb: Double?
        get() = if (clips.isEmpty() || clips.any { it.mb == null }) null else clips.sumOf { it.mb ?: 0.0 }
}

/**
 * The readable part of a library response plus a note for every entry that had
 * to be skipped. Skipped entries are surfaced by the UI rather than dropped
 * silently: an unreadable row that just vanishes looks exactly like "no renders".
 */
data class ParsedLibrary(val groups: List<MediaGroup>, val warnings: List<String>) {
    val clipCount: Int get() = groups.sumOf { it.clips.size }
}

/** The body was not a usable library document. Always shown to the user. */
class MediaParseException(message: String) : Exception(message)

object MediaParser {
    private const val MAX_WARNINGS = 10

    private val json = Json

    /**
     * Parses the body of `/api/media/library`.
     *
     * - Body that is empty, not JSON, or not a JSON list  -> [MediaParseException].
     * - `[]`                                             -> a genuinely empty library.
     * - A list with some malformed entries               -> the good entries, plus warnings.
     * - Unknown extra fields are ignored; missing optional fields become null.
     */
    fun parseLibrary(body: String): ParsedLibrary {
        val text = body.removePrefix("﻿").trim()
        if (text.isEmpty()) {
            throw MediaParseException("The hub returned an empty body where a JSON list of groups was expected.")
        }
        val root: JsonElement = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw MediaParseException("The hub's reply is not valid JSON (${firstLine(e.message)}). It starts with: ${snippet(text)}")
        } catch (e: IllegalArgumentException) {
            throw MediaParseException("The hub's reply is not valid JSON (${firstLine(e.message)}). It starts with: ${snippet(text)}")
        }
        if (root !is JsonArray) {
            // kotlinx accepts an unquoted bare word as a top-level "literal", so an HTML
            // login page can parse as a primitive. Only true/false/numbers/null are real JSON.
            if (root is JsonPrimitive && root !is JsonNull && !root.isString &&
                root.content != "true" && root.content != "false" && root.content.toDoubleOrNull() == null
            ) {
                throw MediaParseException("The hub's reply is not valid JSON. It starts with: ${snippet(text)}")
            }
            val hubDetail = ((root as? JsonObject)?.get("detail") as? JsonPrimitive)?.content
            throw MediaParseException(
                "Expected a JSON list of groups but the hub sent ${describe(root)}" +
                    (hubDetail?.let { " (detail: ${snippet(it)})" } ?: "") + ".",
            )
        }

        val warnings = mutableListOf<String>()
        val groups = ArrayList<MediaGroup>(root.size)
        root.forEachIndexed { i, el -> parseGroup(el, i, warnings)?.let(groups::add) }

        val shown = if (warnings.size > MAX_WARNINGS) {
            warnings.take(MAX_WARNINGS) + "...and ${warnings.size - MAX_WARNINGS} more unreadable entries"
        } else {
            warnings
        }
        return ParsedLibrary(groups, shown)
    }

    private fun parseGroup(el: JsonElement, index: Int, warnings: MutableList<String>): MediaGroup? {
        val obj = el as? JsonObject
        if (obj == null) {
            warnings += "Group #${index + 1} is ${describe(el)}, not an object; skipped."
            return null
        }
        val id = obj.string("id")?.takeIf { it.isNotBlank() }
        if (id == null) {
            warnings += "Group #${index + 1} has no id; skipped."
            return null
        }

        val clips = ArrayList<MediaClip>()
        val rawClips = obj["clips"]
        if (rawClips is JsonArray) {
            rawClips.forEachIndexed { ci, c ->
                val clipObj = c as? JsonObject
                val name = clipObj?.string("name")?.takeIf { it.isNotBlank() }
                if (clipObj == null || name == null) {
                    warnings += "Clip #${ci + 1} in group \"$id\" has no readable name; skipped."
                } else {
                    clips += MediaClip(
                        name = name,
                        idx = clipObj.int("idx"),
                        mtime = clipObj.number("mtime"),
                        mb = clipObj.number("mb"),
                        source = clipObj.string("source"),
                    )
                }
            }
        } else {
            warnings += "Group \"$id\" has no clips list (got ${describe(rawClips)})."
        }

        return MediaGroup(
            id = id,
            title = obj.string("title")?.takeIf { it.isNotBlank() } ?: id,
            kind = obj.string("kind"),
            source = obj.string("source"),
            updated = obj.number("updated") ?: clips.mapNotNull { it.mtime }.maxOrNull(),
            clips = clips,
        )
    }

    private fun JsonObject.string(key: String): String? {
        val p = this[key] as? JsonPrimitive ?: return null
        return if (p is JsonNull) null else if (p.isString) p.content else null
    }

    private fun JsonObject.number(key: String): Double? {
        val p = this[key] as? JsonPrimitive ?: return null
        return if (p is JsonNull || p.isString) null else p.doubleOrNull
    }

    private fun JsonObject.int(key: String): Int? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p is JsonNull || p.isString) return null
        return p.intOrNull ?: p.doubleOrNull?.takeIf { it == Math.rint(it) && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()
    }

    private fun describe(el: JsonElement?): String = when (el) {
        null -> "nothing"
        is JsonNull -> "null"
        is JsonObject -> "an object"
        is JsonArray -> "a list"
        is JsonPrimitive -> when {
            el.isString -> "a string"
            el.content == "true" || el.content == "false" -> "a boolean"
            else -> "a number"
        }
    }

    private fun firstLine(s: String?): String = (s ?: "unknown error").lineSequence().first().take(120)

    /** A short single-line preview of untrusted text, for error messages. */
    fun snippet(s: String, max: Int = 80): String {
        val flat = s.replace(Regex("\\s+"), " ").trim()
        return if (flat.length > max) flat.take(max) + "..." else flat
    }
}
