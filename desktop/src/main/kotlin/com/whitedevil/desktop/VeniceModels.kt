package com.whitedevil.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Locale

/** One chat model Venice offers. The agent calls tools, so [toolCalling] is what decides whether it can drive it. */
data class VeniceModel(val id: String, val name: String, val contextTokens: Int?, val toolCalling: Boolean, val offline: Boolean, val reasoning: Boolean) {
    /** "1M", "262K", "128K": how much the model can read at once. */
    val contextLabel: String? get() = contextTokens?.let { n ->
        when {
            n >= 1_000_000 -> String.format(Locale.US, "%.0fM", n / 1_000_000.0)
            n >= 1_000 -> "${n / 1_000}K"
            else -> "$n"
        }
    }
}

/** GET /api/v1/models?type=text: `{"data":[{"id","model_spec":{"name","availableContextTokens","offline","capabilities":{...}}}]}`. */
fun parseVeniceModels(json: JsonElement): List<VeniceModel>? {
    val data = (json as? JsonObject)?.get("data") as? JsonArray ?: return null
    return data.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val spec = o["model_spec"] as? JsonObject
        val caps = spec?.get("capabilities") as? JsonObject
        fun flag(k: String) = (caps?.get(k) as? JsonPrimitive)?.booleanOrNull == true
        VeniceModel(
            id = id,
            name = (spec?.get("name") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: id,
            contextTokens = (spec?.get("availableContextTokens") as? JsonPrimitive)?.intOrNull ?: (o["context_length"] as? JsonPrimitive)?.intOrNull,
            toolCalling = flag("supportsFunctionCalling"),
            offline = (spec?.get("offline") as? JsonPrimitive)?.booleanOrNull == true,
            reasoning = flag("supportsReasoning"),
        )
    }
}

/** Models the agent can use right now: online and able to call tools, by name. */
fun usableVeniceModels(all: List<VeniceModel>): List<VeniceModel> =
    all.filter { it.toolCalling && !it.offline }.sortedBy { it.name.lowercase(Locale.ROOT) }

/** Search over name and id; every word must match. */
fun filterVeniceModels(models: List<VeniceModel>, query: String): List<VeniceModel> {
    val words = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return models
    return models.filter { m -> val hay = (m.name + " " + m.id).lowercase(Locale.ROOT); words.all { it in hay } }
}

suspend fun fetchVeniceModels(apiKey: String, engine: HttpClientEngine? = null): MediaResult<List<VeniceModel>> {
    if (apiKey.isBlank()) return MediaResult.Failure(MediaError(MediaErrorKind.Config, "No Venice API key is set. Add it in Settings to list the models."))
    val http = HttpClient(engine ?: CIO.create()) {
        expectSuccess = false
        install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 30_000; socketTimeoutMillis = 30_000 }
    }
    return try {
        val r = http.get("https://api.venice.ai/api/v1/models?type=text") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.UserAgent, "forge-hub-desktop")
        }
        val text = r.bodyAsText()
        if (r.status.value !in 200..299) {
            MediaResult.Failure(MediaErrors.fromStatus(MediaOp.Library, r.status.value, text.take(2000), null, true).let { e ->
                if (r.status.value == 401) e.copy(message = "Venice rejected the API key. Check it in Settings.") else e
            })
        } else {
            val models = runCatching { parseVeniceModels(Json.parseToJsonElement(text)) }.getOrNull()
            if (models == null) MediaResult.Failure(MediaError(MediaErrorKind.BadResponse, "Venice's model list was not what was expected."))
            else MediaResult.Ok(models)
        }
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        MediaResult.Failure(MediaErrors.fromException(MediaOp.Library, e, "api.venice.ai", MediaTimeouts(connectMs = 10_000, libraryMs = 30_000)))
    } finally {
        http.close()
    }
}
