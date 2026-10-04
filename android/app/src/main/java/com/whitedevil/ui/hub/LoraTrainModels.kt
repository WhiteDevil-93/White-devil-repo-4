package com.whitedevil.ui.hub

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

/* The hub's /api/loratrain (hub/lora_train.py), parsed for the native Train LoRA screen. */

data class LtItem(val file: String, val type: String, val size: Long, val caption: String, val captionBy: String?, val error: String?)

data class LtEstimate(val minutes: Int, val steps: Int, val secondsPerStep: Double, val costUnits: Double?, val unitsPerHr: Double?)

data class LtDataset(
    val id: String, val name: String, val lora: String, val kind: String, val trigger: String?,
    val items: List<LtItem>, val ready: Boolean, val problems: List<String>, val warnings: List<String>,
    val estimate: LtEstimate?, val captioning: String?, val captionDone: Int, val captionTotal: Int,
    val steps: Int?, val rank: Int?, val recommended: String?,
) {
    val images get() = items.count { it.type == "image" }
    val videos get() = items.count { it.type == "video" }
    val captioned get() = items.count { it.caption.isNotBlank() }
}

data class LtRow(val id: String, val name: String, val kind: String, val items: Int, val ready: Boolean)

data class LtPull(val size: Long, val verified: Boolean)

data class LtRun(
    val id: String, val dataset: String, val name: String, val status: String, val step: String,
    val stepNow: Int?, val stepTotal: Int?, val pulled: List<LtPull>, val final: LtPull?, val log: String?,
) {
    val active get() = status in setOf("queued", "uploading", "training")
}

data class LtUpload(val added: Int, val captions: Int, val skipped: List<String>)

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.o(k: String) = this[k] as? JsonObject
private fun JsonObject.strs(k: String) = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
private fun parse(text: String): JsonElement? = runCatching { Json.parseToJsonElement(text) }.getOrNull()

object LoraTrainJson {
    fun dataset(text: String): LtDataset? {
        val o = parse(text) as? JsonObject ?: return null
        val id = o.s("id") ?: return null
        val items = (o["items"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.mapNotNull { i ->
            LtItem(i.s("file") ?: return@mapNotNull null, i.s("type") ?: "image", i.l("size") ?: 0, i.s("caption") ?: "", i.s("caption_by"), i.s("caption_error"))
        } ?: emptyList()
        val est = o.o("estimate")?.let { LtEstimate(it.i("minutes") ?: 0, it.i("steps") ?: 0, it.d("seconds_per_step") ?: 0.0, it.d("cost_units"), it.d("units_per_hr")) }
        val cap = o.o("captioning")
        val set = o.o("settings")
        return LtDataset(
            id, o.s("name") ?: id, o.s("lora") ?: "", o.s("kind") ?: "character", o.s("trigger"), items,
            o.b("ready") == true, o.strs("problems"), o.strs("warnings"), est, cap?.s("status"), cap?.i("done") ?: 0, cap?.i("total") ?: 0,
            set?.i("steps"), set?.i("rank"), o.o("kind_info")?.s("recommended"),
        )
    }

    fun rows(text: String): List<LtRow>? = (parse(text) as? JsonArray)?.mapNotNull { it as? JsonObject }?.mapNotNull { o ->
        LtRow(o.s("id") ?: return@mapNotNull null, o.s("name") ?: "", o.s("kind") ?: "", o.i("items") ?: 0, o.b("ready") == true)
    }

    private fun pull(o: JsonObject?) = o?.let { LtPull(it.l("size") ?: 0, it.b("verified") == true) }

    fun run(o: JsonObject): LtRun? {
        val id = o.s("id") ?: return null
        return LtRun(
            id, o.s("dataset") ?: "", o.s("name") ?: "", o.s("status") ?: "?", o.s("step") ?: "",
            o.i("step_now"), o.i("step_total"), (o["pulled"] as? JsonArray)?.mapNotNull { pull(it as? JsonObject) } ?: emptyList(),
            pull(o.o("final")), o.s("log"),
        )
    }

    fun runs(text: String): List<LtRun>? = (parse(text) as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::run) }

    fun upload(text: String): LtUpload? = (parse(text) as? JsonObject)?.let { LtUpload(it.i("added") ?: 0, it.i("captions") ?: 0, it.strs("skipped")) }

    fun newDataset(name: String, kind: String, trigger: String) =
        buildJsonObject { put("name", name.trim()); put("kind", kind); put("trigger", trigger.trim()) }.toString()

    fun settings(trigger: String?, steps: Int?, rank: Int?) =
        buildJsonObject { trigger?.let { put("trigger", it.trim()) }; steps?.let { put("steps", it) }; rank?.let { put("rank", it) } }.toString()

    fun captions(map: Map<String, String>) = buildJsonObject { map.forEach { (k, v) -> put(k, v) } }.toString()
}

fun ltDuration(minutes: Int): String = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

/** What the confirm dialog states before training: time, cost (Colab compute units, not dollars), and the render pause. */
fun ltTrainConsequences(d: LtDataset): List<String> {
    val est = d.estimate
    return listOf(
        "Uploads ${d.items.size} items (${d.images} pictures, ${d.videos} clips) to the running Colab and trains ${d.lora} there.",
        est?.let { "About ${ltDuration(it.minutes)} — an ESTIMATE (${it.steps} steps), not yet measured on the G4." } ?: "Time unknown.",
        est?.costUnits?.let { "About $it Colab compute units (${est.unitsPerHr} units/h)." } ?: "Colab bills compute units while it runs.",
        "LTX renders are refused until it finishes: training holds the GPU.",
        "The first run downloads the 42 GB LTX-2.5 Dev model onto Colab.",
    )
}
