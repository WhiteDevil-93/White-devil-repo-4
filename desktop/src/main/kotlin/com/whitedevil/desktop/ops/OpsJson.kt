package com.whitedevil.desktop.ops

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Lenient accessors for the hub's JSON.
 *
 * The hub proxies vendor APIs (Thunder, Vast) and shells out to CLIs, so its
 * replies gain, drop and retype fields without notice. Strict `@Serializable`
 * classes turn one odd field into a whole-panel failure; these accessors turn a
 * missing or mistyped field into `null`, which the UI then renders as "unknown"
 * rather than as a made-up zero.
 *
 * Parsing itself is strict (not `isLenient`): lenient mode accepts a bare word,
 * so an HTML login page from a proxy would "parse" as a JSON string and reach
 * the UI as a successful reply.
 */
internal val opsJson = Json { ignoreUnknownKeys = true }

internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.asArr(): JsonArray? = this as? JsonArray

private fun JsonElement?.asPrim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

/** Scalar as text (numbers and booleans included); objects, arrays and null give null. */
internal fun JsonObject.str(key: String): String? = this[key].asPrim()?.content

internal fun JsonObject.nonBlankStr(key: String): String? = str(key)?.takeIf { it.isNotBlank() }

internal fun JsonObject.num(key: String): Double? = this[key].asPrim()?.doubleOrNull

internal fun JsonObject.int(key: String): Int? = num(key)?.takeIf { it.isFinite() }?.toInt()

internal fun JsonObject.bool(key: String): Boolean? = this[key].asPrim()?.booleanOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** Strings of a JSON array, skipping anything that is not a scalar. */
internal fun JsonArray?.strings(): List<String> =
    this?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content } ?: emptyList()

/** Objects of a JSON array, skipping anything else (the hub filters the same way). */
internal fun JsonArray?.objects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject } ?: emptyList()

/** Compact single-line rendering of any value, for fields whose shape we do not know. */
internal fun JsonElement?.compact(limit: Int = 200): String? {
    val text = when (this) {
        null, JsonNull -> return null
        is JsonPrimitive -> content
        else -> toString()
    }
    return if (text.length > limit) text.take(limit) + "…" else text
}
