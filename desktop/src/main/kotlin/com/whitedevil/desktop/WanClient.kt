package com.whitedevil.desktop

import io.ktor.client.engine.HttpClientEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The hub's prompt-chain generator (hub/gen.py): the OpenRouter key kept on the hub, a generation job that
 * runs on the hub (so the laptop can sleep mid-chain), and its progress. Sending the finished chain to the
 * 14B runner is the Thunder screen's confirmed action (ThunderActions.submit), not repeated here.
 */
class WanGenClient(
    hubUrl: String,
    relayUser: String,
    relayPass: String,
    engine: HttpClientEngine? = null,
) : AutoCloseable {
    private val hub = HubCaller(hubUrl, relayUser, relayPass, engine)

    /** Whether the hub already holds an OpenRouter key, so the key box can stay out of the way. */
    suspend fun keyConfigured(): MediaResult<Boolean> = hub.call("/api/gen/key", 20_000) { text ->
        val o = Json.parseToJsonElement(text) as? JsonObject
        MediaResult.Ok((o?.get("configured") as? JsonPrimitive)?.booleanOrNull == true)
    }

    suspend fun saveKey(key: String): MediaResult<Unit> {
        if (key.isBlank()) return MediaResult.Failure(MediaError(MediaErrorKind.ClientError, "Paste the OpenRouter key first."))
        val body = buildJsonObject { put("key", key.trim()) }
        return hub.call("/api/gen/key", 20_000, post = body.toString(), json = true) { MediaResult.Ok(Unit) }
    }

    /** Starts the generation on the hub and returns the job id; progress comes from [job]. */
    suspend fun start(request: JsonObject): MediaResult<String> =
        hub.call("/api/gen/chain", 60_000, post = request.toString(), json = true) { text ->
            val id = ((Json.parseToJsonElement(text) as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
            if (id.isNullOrBlank()) hub.bad("The hub started a generation but did not say which job it is.") else MediaResult.Ok(id)
        }

    suspend fun job(id: String): MediaResult<GenJob> {
        if (!id.matches(Regex("[\\w-]{1,64}"))) return MediaResult.Failure(MediaError(MediaErrorKind.ClientError, "Not a generation job id."))
        return hub.call("/api/gen/jobs/$id", 30_000) { text ->
            parseGenJob(Json.parseToJsonElement(text))?.let { MediaResult.Ok(it) } ?: hub.bad("The hub's generation job was not what was expected.")
        }
    }

    suspend fun resume(id: String): MediaResult<Unit> {
        if (!id.matches(Regex("[\\w-]{1,64}"))) return MediaResult.Failure(MediaError(MediaErrorKind.ClientError, "Not a generation job id."))
        return hub.call("/api/gen/jobs/$id/resume", 30_000, post = "") { MediaResult.Ok(Unit) }
    }

    override fun close() { hub.close() }
}
