package com.whitedevil.desktop

import io.ktor.client.engine.HttpClientEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** What the hub says about a pasted token: saved, and whether the account can actually use it. */
data class SecretResult(val ok: Boolean, val why: String?)

/**
 * The Setup bot's endpoints (hub/setupbot.py). Planning and previewing only read; starting a run is the one
 * call that creates machines or installs onto them, and the screen only makes it after a confirmation.
 */
class SetupBotClient(
    hubUrl: String,
    relayUser: String,
    relayPass: String,
    engine: HttpClientEngine? = null,
) : AutoCloseable {
    private val hub = HubCaller(hubUrl, relayUser, relayPass, engine)

    // The hub waits up to 12 s for Vast and asks Thunder for stock before it answers.
    suspend fun catalog(): MediaResult<SetupCatalog> = hub.call("/api/setup/catalog", 60_000) { text ->
        parseSetupCatalog(Json.parseToJsonElement(text))?.let { MediaResult.Ok(it) } ?: hub.bad("The hub's setup catalog was not what was expected.")
    }

    // A preview SSHes into the target to measure free disk, so it can take a while.
    suspend fun preview(plan: JsonObject): MediaResult<PlanPreview> = hub.call("/api/setup/preview", 120_000, post = plan.toString(), json = true) { text ->
        parsePlanPreview(Json.parseToJsonElement(text))?.let { MediaResult.Ok(it) } ?: hub.bad("The hub's plan was not what was expected.")
    }

    /** Turns a sentence into a previewed plan with OpenRouter (the key lives on the hub). */
    suspend fun chat(text: String, history: List<Pair<String, String>>): MediaResult<ChatReply> {
        if (text.isBlank()) return MediaResult.Failure(MediaError(MediaErrorKind.ClientError, "Say what to set up, and where."))
        val body = buildJsonObject {
            put("text", text.trim())
            put("history", buildJsonArray { history.takeLast(6).forEach { (role, t) -> add(buildJsonObject { put("role", role); put("text", t) }) } })
        }
        return hub.call("/api/setup/chat", 180_000, post = body.toString(), json = true) { reply ->
            parseChatReply(Json.parseToJsonElement(reply))?.let { MediaResult.Ok(it) } ?: hub.bad("The planner's answer was not what was expected.")
        }
    }

    /** Starts the run for a plan the hub itself previewed. Returns the run's id. */
    suspend fun startRun(plan: JsonObject): MediaResult<String> = hub.call("/api/setup/runs", 120_000, post = plan.toString(), json = true) { text ->
        val id = ((Json.parseToJsonElement(text) as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
        if (id.isNullOrBlank()) hub.bad("The hub accepted the setup but did not say which run it is.") else MediaResult.Ok(id)
    }

    suspend fun runs(): MediaResult<List<SetupRun>> = hub.call("/api/setup/runs", 30_000) { text ->
        parseSetupRuns(Json.parseToJsonElement(text))?.let { MediaResult.Ok(it) } ?: hub.bad("The hub's run list was not what was expected.")
    }

    suspend fun cancelRun(id: String): MediaResult<Unit> {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return MediaResult.Failure(MediaError(MediaErrorKind.ClientError, "Not a run id."))
        return hub.call("/api/setup/runs/$id/cancel", 60_000, post = "") { MediaResult.Ok(Unit) }
    }

    /** Saves a Hugging Face or CivitAI token on the hub (never in this app). */
    suspend fun saveSecret(name: String, value: String): MediaResult<SecretResult> {
        if (name != "HF_TOKEN" && name != "CIVITAI_TOKEN") return MediaResult.Failure(MediaError(MediaErrorKind.ClientError, "Unknown token name."))
        if (value.isBlank()) return MediaResult.Failure(MediaError(MediaErrorKind.ClientError, "Paste the token first."))
        val body = buildJsonObject { put("name", name); put("value", value.trim()) }
        return hub.call("/api/setup/secret", 60_000, post = body.toString(), json = true) { text ->
            val o = Json.parseToJsonElement(text) as? JsonObject
            MediaResult.Ok(SecretResult((o?.get("ok") as? JsonPrimitive)?.booleanOrNull == true, (o?.get("why") as? JsonPrimitive)?.contentOrNull))
        }
    }

    override fun close() { hub.close() }
}

/** The line the web page shows after saving a token. */
fun secretOutcomeMessage(r: SecretResult): String = when {
    r.ok -> "Saved on the hub."
    r.why == "gated" -> "Saved, but it still can't read LTX-2.5: accept the licence with that account on huggingface.co/Lightricks/LTX-2.5."
    else -> "Saved, but Hugging Face rejected it."
}

