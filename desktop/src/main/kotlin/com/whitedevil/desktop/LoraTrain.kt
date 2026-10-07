package com.whitedevil.desktop

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.streams.asInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/*
 * Train LoRA: the hub's /api/loratrain (hub/lora_train.py). The hub keeps the dataset, captions it, runs Lightricks'
 * ltx-trainer on the Colab G4 and pulls the LoRA back; this screen only drives it.
 */

data class LoraItem(val file: String, val type: String, val size: Long, val caption: String, val captionBy: String?, val error: String?)

data class LoraEstimate(val minutes: Int, val steps: Int, val secondsPerStep: Double, val costUnits: Double?, val unitsPerHr: Double?)

data class LoraDataset(
    val id: String,
    val name: String,
    val lora: String,
    val kind: String,
    val trigger: String?,
    val items: List<LoraItem>,
    val ready: Boolean,
    val problems: List<String>,
    val warnings: List<String>,
    val estimate: LoraEstimate?,
    val captioning: String?,
    val captionDone: Int,
    val captionTotal: Int,
    val steps: Int?,
    val rank: Int?,
    val recommended: String?,
) {
    val images get() = items.count { it.type == "image" }
    val videos get() = items.count { it.type == "video" }
    val captioned get() = items.count { it.caption.isNotBlank() }
}

data class LoraDatasetRow(val id: String, val name: String, val kind: String, val items: Int, val ready: Boolean)

data class LoraPull(val local: String, val size: Long, val verified: Boolean)

data class LoraRun(
    val id: String,
    val dataset: String,
    val name: String,
    val status: String,
    val step: String,
    val stage: String?,
    val stepNow: Int?,
    val stepTotal: Int?,
    val pulled: List<LoraPull>,
    val final: LoraPull?,
    val log: String?,
) {
    val active get() = status in setOf("queued", "uploading", "training")
}

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.o(k: String) = this[k] as? JsonObject
private fun JsonObject.strings(k: String) = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

fun parseLoraDataset(e: JsonElement): LoraDataset? {
    val o = e as? JsonObject ?: return null
    val id = o.s("id") ?: return null
    val items = (o["items"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.mapNotNull { i ->
        LoraItem(i.s("file") ?: return@mapNotNull null, i.s("type") ?: "image", i.l("size") ?: 0, i.s("caption") ?: "", i.s("caption_by"), i.s("caption_error"))
    } ?: emptyList()
    val est = o.o("estimate")?.let { LoraEstimate(it.i("minutes") ?: 0, it.i("steps") ?: 0, it.d("seconds_per_step") ?: 0.0, it.d("cost_units"), it.d("units_per_hr")) }
    val cap = o.o("captioning")
    val settings = o.o("settings")
    return LoraDataset(
        id, o.s("name") ?: id, o.s("lora") ?: "", o.s("kind") ?: "character", o.s("trigger"), items,
        o.b("ready") == true, o.strings("problems"), o.strings("warnings"), est,
        cap?.s("status"), cap?.i("done") ?: 0, cap?.i("total") ?: 0, settings?.i("steps"), settings?.i("rank"),
        o.o("kind_info")?.s("recommended"),
    )
}

fun parseLoraDatasetRows(e: JsonElement): List<LoraDatasetRow>? = (e as? JsonArray)?.mapNotNull { it as? JsonObject }?.mapNotNull { o ->
    LoraDatasetRow(o.s("id") ?: return@mapNotNull null, o.s("name") ?: "", o.s("kind") ?: "", o.i("items") ?: 0, o.b("ready") == true)
}

private fun parsePull(o: JsonObject?) = o?.let { LoraPull(it.s("local") ?: "", it.l("size") ?: 0, it.b("verified") == true) }

fun parseLoraRun(e: JsonElement): LoraRun? {
    val o = e as? JsonObject ?: return null
    return LoraRun(
        o.s("id") ?: return null, o.s("dataset") ?: "", o.s("name") ?: "", o.s("status") ?: "?", o.s("step") ?: "",
        o.s("stage"), o.i("step_now"), o.i("step_total"),
        (o["pulled"] as? JsonArray)?.mapNotNull { parsePull(it as? JsonObject) } ?: emptyList(), parsePull(o.o("final")), o.s("log"),
    )
}

fun parseLoraRuns(e: JsonElement): List<LoraRun>? = (e as? JsonArray)?.mapNotNull { parseLoraRun(it) }

/** What the confirmation dialog says before a run starts: the time, the cost and what pauses meanwhile. */
fun trainConsequences(ds: LoraDataset): List<String> {
    val est = ds.estimate
    val time = est?.let { "About ${it.minutes / 60} h ${it.minutes % 60} min (an ESTIMATE: ${it.steps} steps at ~${it.secondsPerStep} s/step plus setup; not yet measured on the G4)." }
        ?: "Time unknown."
    val cost = est?.costUnits?.let { "About $it Colab compute units at ${est.unitsPerHr} units/h." }
        ?: "Cost: Colab bills compute units while it runs (the rate shows once Colab has been on)."
    return listOf(
        "Uploads ${ds.items.size} items (${ds.images} images, ${ds.videos} clips) to the running Colab and trains '${ds.lora}' there.",
        time, cost,
        "LTX renders are refused until it finishes or you cancel: training holds the GPU.",
        "The first run downloads the official LTX-2.5 Dev model (42 GB) onto Colab.",
        "The LoRA lands in the LTX LoRA list, on the relay (size-checked) and in your private Hugging Face repo.",
    )
}

class LoraTrainClient(hubUrl: String, relayUser: String, relayPass: String, engine: HttpClientEngine? = null) : AutoCloseable {
    private val hub = HubCaller(hubUrl, relayUser, relayPass, engine)
    private fun ds(text: String) = parseLoraDataset(Json.parseToJsonElement(text))?.let { MediaResult.Ok(it) } ?: hub.bad<LoraDataset>("The hub's dataset was not what was expected.")
    private fun okId(id: String) = id.matches(Regex("[0-9a-f]{12}"))
    private fun <T> badId(): MediaResult<T> = MediaResult.Failure(MediaError(MediaErrorKind.ClientError, "Not a dataset or run id."))

    suspend fun datasets(): MediaResult<List<LoraDatasetRow>> = hub.call("/api/loratrain/datasets", 30_000) { t ->
        parseLoraDatasetRows(Json.parseToJsonElement(t))?.let { MediaResult.Ok(it) } ?: hub.bad("The hub's dataset list was not what was expected.")
    }

    suspend fun dataset(id: String): MediaResult<LoraDataset> = if (!okId(id)) badId() else hub.call("/api/loratrain/datasets/$id", 60_000, parse = ::ds)

    suspend fun create(name: String, kind: String, trigger: String): MediaResult<LoraDataset> {
        if (name.isBlank()) return MediaResult.Failure(MediaError(MediaErrorKind.ClientError, "Give the LoRA a name."))
        val body = buildJsonObject { put("name", name.trim()); put("kind", kind); put("trigger", trigger.trim()) }
        return hub.call("/api/loratrain/datasets", 30_000, post = body.toString(), json = true, parse = ::ds)
    }

    /**
     * One request per file, streamed from disk (never read into memory), so a multi-GB clip or zip is fine. Any file
     * type: the hub converts media, uses .txt as captions and unpacks .zip. The timeout allows ~1 MB/s for the file
     * plus the hub's conversion.
     */
    suspend fun upload(id: String, file: File): MediaResult<Int> {
        if (!okId(id)) return badId()
        val form = formData {
            append("files", InputProvider(file.length()) { file.inputStream().asInput() }, Headers.build {
                append(HttpHeaders.ContentDisposition, "filename=\"${file.name.replace("\"", "")}\"")
            })
        }
        val timeoutMs = 600_000L + file.length() / 1_000
        return hub.call("/api/loratrain/datasets/$id/files", timeoutMs, post = MultiPartFormDataContent(form)) { t ->
            val o = Json.parseToJsonElement(t) as? JsonObject
            val added = o?.i("added") ?: 0
            val skipped = o?.strings("skipped") ?: emptyList()
            if (added == 0 && skipped.isNotEmpty()) hub.bad(skipped.joinToString("; ")) else MediaResult.Ok(added)
        }
    }

    suspend fun deleteItem(id: String, file: String): MediaResult<LoraDataset> =
        if (!okId(id)) badId() else hub.call("/api/loratrain/datasets/$id/files/${java.net.URLEncoder.encode(file, "UTF-8")}", 30_000, method = "DELETE", parse = ::ds)

    suspend fun saveCaptions(id: String, captions: Map<String, String>): MediaResult<LoraDataset> {
        if (!okId(id)) return badId()
        val body = buildJsonObject { captions.forEach { (k, v) -> put(k, v) } }
        return hub.call("/api/loratrain/datasets/$id/captions", 30_000, post = body.toString(), json = true, method = "PUT", parse = ::ds)
    }

    suspend fun settings(id: String, steps: Int?, rank: Int?, trigger: String?): MediaResult<LoraDataset> {
        if (!okId(id)) return badId()
        val body = buildJsonObject { steps?.let { put("steps", it) }; rank?.let { put("rank", it) }; trigger?.let { put("trigger", it) } }
        return hub.call("/api/loratrain/datasets/$id", 30_000, post = body.toString(), json = true, method = "PATCH", parse = ::ds)
    }

    suspend fun autoCaption(id: String, redo: Boolean = false): MediaResult<Unit> =
        if (!okId(id)) badId() else hub.call("/api/loratrain/datasets/$id/caption?redo=$redo", 30_000, post = "") { MediaResult.Ok(Unit) }

    suspend fun deleteDataset(id: String): MediaResult<Unit> =
        if (!okId(id)) badId() else hub.call("/api/loratrain/datasets/$id", 30_000, method = "DELETE") { MediaResult.Ok(Unit) }

    suspend fun train(id: String): MediaResult<LoraRun> = if (!okId(id)) badId() else
        hub.call("/api/loratrain/datasets/$id/train", 60_000, post = "") { t ->
            parseLoraRun(Json.parseToJsonElement(t))?.let { MediaResult.Ok(it) } ?: hub.bad("The hub started training but its answer was not what was expected.")
        }

    suspend fun runs(): MediaResult<List<LoraRun>> = hub.call("/api/loratrain/runs", 30_000) { t ->
        parseLoraRuns(Json.parseToJsonElement(t))?.let { MediaResult.Ok(it) } ?: hub.bad("The hub's run list was not what was expected.")
    }

    suspend fun run(id: String): MediaResult<LoraRun> = if (!okId(id)) badId() else hub.call("/api/loratrain/runs/$id", 30_000) { t ->
        parseLoraRun(Json.parseToJsonElement(t))?.let { MediaResult.Ok(it) } ?: hub.bad("The hub's run was not what was expected.")
    }

    suspend fun cancel(id: String): MediaResult<Unit> = if (!okId(id)) badId() else hub.call("/api/loratrain/runs/$id/cancel", 30_000, post = "") { MediaResult.Ok(Unit) }

    override fun close() { hub.close() }
}
