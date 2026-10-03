package com.whitedevil.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Rebuilds one chat completion from its server-sent-event lines (`data: {...}` ... `data: [DONE]`).
 * Pure and synchronous so it can be tested without a network: the client feeds it lines as they arrive.
 */
class StreamAssembler(private val json: Json = Json { ignoreUnknownKeys = true }) {
    private class Call {
        var id: String? = null
        val name = StringBuilder()
        val args = StringBuilder()
    }

    private val text = StringBuilder()
    private val calls = java.util.TreeMap<Int, Call>()
    private var finish: String? = null
    private var usage: Usage? = null
    private var id: String? = null
    private var model: String? = null

    var done = false
        private set

    /** Set when the server sent an error object inside the stream. */
    var error: String? = null
        private set

    /** Feeds one raw line. Returns the text accumulated so far if this line added text, else null. */
    fun feed(rawLine: String): String? {
        val line = rawLine.trimEnd('\r', '\n')
        if (!line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty()) return null
        if (data == "[DONE]") { done = true; return null }
        val obj = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
        obj["error"]?.let { if (it !is JsonPrimitive || it.contentOrNull != null) { error = it.toString(); return null } }
        id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: id
        model = (obj["model"] as? JsonPrimitive)?.contentOrNull ?: model
        (obj["usage"] as? JsonObject)?.let { u ->
            usage = Usage(
                promptTokens = (u["prompt_tokens"] as? JsonPrimitive)?.intOrNull,
                completionTokens = (u["completion_tokens"] as? JsonPrimitive)?.intOrNull,
                totalTokens = (u["total_tokens"] as? JsonPrimitive)?.intOrNull,
            )
        }
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null   // usage-only chunk
        (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let { finish = it }
        val delta = choice["delta"] as? JsonObject ?: return null
        var added = false
        (delta["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
            text.append(it)
            added = true
        }
        (delta["tool_calls"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val callId = (o["id"] as? JsonPrimitive)?.contentOrNull
            // Some servers omit `index`: a chunk with an id starts a new call, one without continues the last.
            val idx = (o["index"] as? JsonPrimitive)?.intOrNull ?: (if (callId != null) calls.size else (calls.size - 1).coerceAtLeast(0))
            val c = calls.getOrPut(idx) { Call() }
            if (callId != null && c.id == null) c.id = callId
            val fn = o["function"] as? JsonObject
            (fn?.get("name") as? JsonPrimitive)?.contentOrNull?.let { c.name.append(it) }
            (fn?.get("arguments") as? JsonPrimitive)?.contentOrNull?.let { c.args.append(it) }
        }
        return if (added) text.toString() else null
    }

    fun hasContent(): Boolean = text.isNotEmpty() || calls.isNotEmpty()

    fun result(): ChatCompletionResponse {
        val toolCalls = calls.entries.map { (i, c) ->
            ToolCall(
                id = c.id ?: "call_$i",
                function = ToolCallFunction(name = c.name.toString(), arguments = c.args.toString().ifBlank { "{}" }),
            )
        }
        val message = ChatMessage(
            role = "assistant",
            content = if (text.isNotEmpty()) JsonPrimitive(text.toString()) else null,
            toolCalls = toolCalls.ifEmpty { null },
        )
        return ChatCompletionResponse(
            id = id,
            model = model,
            choices = listOf(ChatChoice(index = 0, message = message, finishReason = finish)),
            usage = usage,
        )
    }
}
