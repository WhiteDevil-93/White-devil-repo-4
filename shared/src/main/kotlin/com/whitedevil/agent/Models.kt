package com.whitedevil.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
) {
    /** Plain-text view of this message, concatenating any text parts. */
    fun textContent(): String = content.textContent()
}

/** A single text part inside a multimodal user message. */
@Serializable
data class ContentPartText(
    val type: String = "text",
    val text: String,
)

@Serializable
data class ImageUrlPayload(
    val url: String,
)

/** A single image part inside a multimodal user message. */
@Serializable
data class ContentPartImage(
    val type: String = "image_url",
    @SerialName("image_url") val imageUrl: ImageUrlPayload,
)

/** Helpers for building and reading message content (plain string or OpenAI-style part array). */
object MessageContent {
    fun text(text: String): JsonElement = JsonPrimitive(text)

    /** Builds `content` from text plus base64 image data URLs. Falls back to plain text when empty. */
    fun multimodal(text: String, imageDataUrls: List<String>): JsonElement {
        if (imageDataUrls.isEmpty()) return text(text)
        return buildJsonArray {
            if (text.isNotBlank()) {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
            }
            imageDataUrls.forEach { url ->
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", url) })
                    },
                )
            }
        }
    }
}

fun JsonElement?.textContent(): String = when (this) {
    is JsonPrimitive -> contentOrNull ?: ""
    is JsonArray -> mapNotNull { part ->
        runCatching { part.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    }.joinToString("")
    is JsonObject -> this["text"]?.jsonPrimitive?.contentOrNull ?: ""
    else -> ""
}

/** Removes image data blobs from content so persisted history stays small. */
fun stripBlobs(content: JsonElement?): JsonElement? = when (content) {
    is JsonArray -> {
        val texts = content.mapNotNull { part ->
            runCatching {
                val obj = part.jsonObject
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                val text = obj["text"]?.jsonPrimitive?.contentOrNull
                if (type == "text") text else null
            }.getOrNull()
        }.filterNotNull()
        val joined = texts.joinToString("")
        if (joined.isNotBlank()) JsonPrimitive(joined) else JsonPrimitive("[image attached]")
    }
    else -> content
}

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction,
)

@Serializable
data class ToolCallFunction(
    val name: String,
    val arguments: String,
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: ToolFunctionSpec,
)

@Serializable
data class ToolFunctionSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class VeniceParameters(
    @SerialName("enable_web_search") val enableWebSearch: String? = null,
    @SerialName("include_venice_system_prompt") val includeVeniceSystemPrompt: Boolean? = null,
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null,
    val temperature: Double? = null,
    val stream: Boolean = false,
    @SerialName("venice_parameters") val veniceParameters: VeniceParameters? = null,
)

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChatChoice> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
data class ChatChoice(
    val index: Int? = null,
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

@Serializable
data class ApiErrorBody(
    val error: JsonElement? = null,
)
