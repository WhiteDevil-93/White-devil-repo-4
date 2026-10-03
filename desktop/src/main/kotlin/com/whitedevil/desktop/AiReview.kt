package com.whitedevil.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * AI review of a render: the clip's contact sheet (a grid of frames) goes to a Venice model that can see images, with
 * the same instruction the web agent gives it for /review. The model never sees motion or hears audio, and the
 * prompt says so, so it reports what the frames show instead of inventing the rest.
 */

/** A recent finished clip that can be reviewed. */
data class ReviewClip(val name: String, val title: String, val source: String, val mtime: Double?, val mb: Double?)

private val CHAIN_PART = Regex("_c\\d+\\.mp4$", RegexOption.IGNORE_CASE)

/**
 * The strip of recent clips, as the web "Video review" builds it: clips of one source, newest first, the finished
 * joins preferred over the per-part files of a chain, at most [limit].
 */
fun reviewClips(groups: List<MediaGroup>, source: String, limit: Int = 8): List<ReviewClip> {
    val all = splitLtx(groups).flatMap { g ->
        g.clips.filter { (it.source ?: g.source) == source }
            .map { ReviewClip(it.name, g.title, source, it.mtime, it.mb) }
    }.sortedWith(compareByDescending<ReviewClip> { it.mtime != null }.thenByDescending { it.mtime ?: 0.0 })
    val finals = all.filter { !CHAIN_PART.containsMatchIn(it.name) }
    return (finals.ifEmpty { all }).take(limit)
}

private val VISION_PREFERENCE = listOf("qwen3-vl-235b-a22b", "venice-uncensored-1-2", "gemma-4-uncensored", "mistral-small-3-2-24b-instruct")

/** Your chosen model if it can see images, else the first preferred vision model that is online, else any online one. */
fun pickVisionModel(selected: String, models: List<VeniceModel>): VeniceModel? {
    val usable = models.filter { it.vision && !it.offline }
    usable.firstOrNull { it.id == selected }?.let { return it }
    VISION_PREFERENCE.forEach { id -> usable.firstOrNull { it.id == id }?.let { return it } }
    return usable.sortedBy { it.name.lowercase() }.firstOrNull()
}

/** The instruction sent with the picture. [prompt] is the text the clip was made from, when the hub has it. */
fun reviewPrompt(clip: ReviewClip, prompt: String?): String = buildString {
    append("Review this AI-generated video. Clip: ${prettyClipName(clip.name)} (file ${clip.name}); project: ${clip.title}.\n")
    if (!prompt.isNullOrBlank()) append("It was made from this prompt:\n${prompt.trim().take(1500)}\n")
    append("\nThe image is a contact sheet of frames from the clip. Analyze left→right, top→bottom. ")
    append("Do not invent details you cannot see. Still frames only: say so if motion or audio cannot be judged.\n\n")
    append("Cover: (1) whether the same people, clothing and setting hold across frames; (2) visible artifacts such as warped hands, ")
    append("faces or anatomy, flicker, smearing or melting; (3) how well it matches the prompt, if one was given; ")
    append("(4) what to change next time (prompt wording, LoRA strength, length, seed). Be specific and brief.")
}

fun reviewRequest(model: String, text: String, imageDataUrl: String): JsonObject = buildJsonObject {
    put("model", model)
    put("temperature", 0.3)
    put("max_tokens", 1400)
    put("messages", buildJsonArray {
        add(buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", text) })
                add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", imageDataUrl) }) })
            })
        })
    })
}

/** The model's reply text. Venice returns a string; some models return a list of parts. Null when there is none. */
fun reviewReply(json: JsonElement): String? {
    val msg = (((json as? JsonObject)?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject ?: return null
    val content = msg["content"]
    val text = when (content) {
        is JsonPrimitive -> content.contentOrNull
        is JsonArray -> content.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }.joinToString("\n")
        else -> null
    }
    return text?.trim()?.takeIf { it.isNotEmpty() }
}

suspend fun reviewWithVision(
    apiKey: String,
    model: String,
    text: String,
    imageDataUrl: String,
    engine: HttpClientEngine? = null,
): MediaResult<String> {
    if (apiKey.isBlank()) return MediaResult.Failure(MediaError(MediaErrorKind.Config, "No Venice API key is set. Add it in Settings to use AI review."))
    val http = HttpClient(engine ?: CIO.create()) {
        expectSuccess = false
        install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 240_000; socketTimeoutMillis = 240_000 }
    }
    return try {
        val r = http.post("https://api.venice.ai/api/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(reviewRequest(model, text, imageDataUrl).toString())
        }
        val body = r.bodyAsText()
        val status = r.status.value
        if (status !in 200..299) {
            val detail = runCatching { ((Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject)?.get("message") }.getOrNull()
                ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: hubDetail(body)
            return MediaResult.Failure(
                if (status == 401) MediaError(MediaErrorKind.Unauthorized, "Venice rejected the API key. Check it in Settings.", status)
                else MediaError(MediaErrorKind.ServerError, detail ?: "Venice answered HTTP $status.", status),
            )
        }
        val reply = runCatching { reviewReply(Json.parseToJsonElement(body)) }.getOrNull()
        if (reply == null) MediaResult.Failure(MediaError(MediaErrorKind.BadResponse, "$model sent no review text. Try another model."))
        else MediaResult.Ok(reply)
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        MediaResult.Failure(MediaErrors.fromException(MediaOp.Library, e, "api.venice.ai", MediaTimeouts(connectMs = 10_000, libraryMs = 240_000)))
    } finally {
        http.close()
    }
}
