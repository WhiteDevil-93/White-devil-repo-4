package com.whitedevil.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.util.Base64

/** Body of POST /api/ltx/assist: the prompt writer. [imageDataUrl] is a small JPEG data URL, never the original file. */
data class AssistRequest(
    val idea: String,
    val frames: Int,
    val parts: Int,
    val imageDataUrl: String?,
    val fromJob: String?,
    val writer: String?,
)

/**
 * Native client for the LTX endpoints the web builder uses (hub/ltx.py): status, jobs, render, chain,
 * assist and cancel. Same relay basic auth as the rest of the app. A request that fails for any reason
 * is a [MediaResult.Failure] carrying what the hub itself said (its FastAPI `detail`), never a silent
 * success: the hub's own messages ("The plan has 3 clips and this run is set to 20") are the useful ones.
 */
class LtxBuilderClient(
    hubUrl: String,
    relayUser: String,
    relayPass: String,
    engine: HttpClientEngine? = null,
) : AutoCloseable {
    private val hub = HubCaller(hubUrl, relayUser, relayPass, engine)
    private fun <T> bad(message: String): MediaResult<T> = hub.bad(message)
    private suspend fun <T> call(path: String, timeoutMs: Long, post: Any? = null, json: Boolean = false, parse: (String) -> MediaResult<T>): MediaResult<T> =
        hub.call(path, timeoutMs, post, json, parse = parse)

    suspend fun status(): MediaResult<BuilderStatus> = call("/api/ltx/status", 30_000) { text ->
        val json = Json.parseToJsonElement(text)
        parseBuilderStatus(json)?.let { MediaResult.Ok(it) } ?: bad("The hub's LTX status was not what was expected.")
    }

    suspend fun jobs(): MediaResult<List<BuilderJob>> = call("/api/ltx/jobs", 30_000) { text ->
        parseBuilderJobs(Json.parseToJsonElement(text))?.let { MediaResult.Ok(it) } ?: bad("The hub's job list was not what was expected.")
    }

    /** Sends the render and returns the new job's id. */
    suspend fun submit(r: BuildRequest): MediaResult<String> {
        validateRequest(r)?.let { return MediaResult.Failure(MediaError(MediaErrorKind.ClientError, it)) }
        val form = formData {
            r.fields().forEach { (k, v) -> append(k, v) }
            val pic = r.picture
            if (r.usesPicture && pic != null) {
                append("image", pic.bytes, Headers.build {
                    append(HttpHeaders.ContentType, pic.contentType)
                    append(HttpHeaders.ContentDisposition, "filename=\"${safeFileName(pic.name) ?: "start.jpg"}\"")
                })
            }
        }
        // The hub uploads the picture on to ComfyUI before it answers, so this can take a while.
        return call(r.endpoint, 180_000, post = MultiPartFormDataContent(form)) { text ->
            val id = (Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
            if (id.isNullOrBlank()) bad("The hub accepted the render but did not say which job it is.") else MediaResult.Ok(id)
        }
    }

    suspend fun assist(r: AssistRequest): MediaResult<String> {
        val body = buildJsonObject {
            put("idea", r.idea); put("frames", r.frames); put("parts", r.parts)
            if (r.imageDataUrl != null) put("image", r.imageDataUrl) else put("image", JsonNull)
            if (r.fromJob != null) put("from_job", r.fromJob) else put("from_job", JsonNull)
            if (r.writer != null) put("writer", r.writer) else put("writer", JsonNull)
        }
        // The prompt writer captions the picture and plans the clips through OpenRouter: slow on purpose.
        return call("/api/ltx/assist", 240_000, post = body.toString(), json = true) { text ->
            val prompt = (Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject)?.get("prompt")?.let { (it as? JsonPrimitive)?.contentOrNull }
            if (prompt.isNullOrBlank()) bad("The prompt writer sent nothing back. Try different words.") else MediaResult.Ok(prompt)
        }
    }

    /** Downloads a LoRA or video model from a CivitAI / Hugging Face link straight onto Colab. */
    suspend fun install(url: String, kind: String): MediaResult<InstallItem> {
        if (url.isBlank()) return MediaResult.Failure(MediaError(MediaErrorKind.ClientError, "Paste a CivitAI or Hugging Face link first."))
        val body = buildJsonObject { put("url", url.trim()); put("kind", if (kind == "transformer") "transformer" else "lora") }
        // The hub asks CivitAI and checks the file before it answers.
        return call("/api/ltx/install", 90_000, post = body.toString(), json = true) { text ->
            parseInstallItem(Json.parseToJsonElement(text))?.let { MediaResult.Ok(it) } ?: bad("The hub's install answer was not what was expected.")
        }
    }

    suspend fun cancel(id: String): MediaResult<Unit> {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return MediaResult.Failure(MediaError(MediaErrorKind.ClientError, "Not a job id."))
        return call("/api/ltx/jobs/$id/cancel", 120_000, post = "") { MediaResult.Ok(Unit) }
    }

    override fun close() { hub.close() }
}
