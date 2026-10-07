package com.whitedevil.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Everything the native LTX builder decides, as plain functions and data so it can be tested without a
 * window or a hub. It mirrors hub/static/ltx/index.html (the web builder) field for field: the same
 * endpoint choice (/render for one fresh clip, /chain for several clips or a continuation), the same form
 * fields, the same checks, and the same model defaults.
 */

val LTX_FRAMES = listOf(49, 73, 97, 121, 193, 241)
val LTX_SIZES = listOf("landscape", "portrait", "square")
val LTX_LENGTH_LABEL = mapOf(49 to "2 s", 73 to "3 s", 97 to "4 s", 121 to "5 s", 193 to "8 s", 241 to "10 s")
const val LTX_MAX_PARTS = 20

/** Model choices sent as the `opts` form field; the hub clamps and validates them again (norm_opts). */
data class BuilderOpts(
    val transformer: String? = null,
    val loras: List<Pair<String, Double>> = emptyList(),
    val distill: Double = 1.0,
    val vae: String = "quality",
    val clip: String? = null,
    val writer: String? = "x-ai/grok-4.5",
) {
    fun toJson(): JsonObject = buildJsonObject {
        if (transformer != null) put("transformer", transformer) else put("transformer", JsonNull)
        put("loras", buildJsonArray { loras.forEach { (n, s) -> add(buildJsonArray { add(JsonPrimitive(n)); add(JsonPrimitive(s)) }) } })
        put("distill", distill)
        put("vae", vae)
        if (clip != null) put("clip", clip) else put("clip", JsonNull)
        if (writer != null) put("writer", writer) else put("writer", JsonNull)
    }

    companion object {
        fun fromJson(e: JsonElement?): BuilderOpts? {
            val o = e as? JsonObject ?: return null
            val loras = (o["loras"] as? JsonArray).orEmpty().mapNotNull { item ->
                val a = item as? JsonArray ?: return@mapNotNull null
                val name = (a.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val s = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                name to s
            }
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            return BuilderOpts(
                transformer = str("transformer"),
                loras = loras.take(4),
                distill = ((o["distill"] as? JsonPrimitive)?.doubleOrNull ?: 1.0).coerceIn(0.0, 1.2),
                vae = if (str("vae") == "fast") "fast" else "quality",
                clip = str("clip"),
                writer = str("writer"),
            )
        }
    }
}

/** What the hub says it can do right now (GET /api/ltx/status). */
data class BuilderStatus(
    val online: Boolean,
    val billing: Boolean,
    val busy: String?,
    val detail: String?,
    val transformers: List<String>,
    val loras: List<String>,
    val clips: List<String>,
    val writers: List<Pair<String, String>>,
    val decoders: Map<String, Boolean>,
    val distilled: Boolean,
    val triggers: Map<String, String>,
    val installs: List<InstallItem> = emptyList(),
)

/** A LoRA / video model being downloaded onto Colab (POST /api/ltx/install, listed in status.installs). */
data class InstallItem(val name: String, val kind: String, val state: String, val sizeMb: Int?, val warn: String?, val detail: String?, val trigger: String?)

fun parseInstallItem(e: JsonElement?): InstallItem? {
    val o = e as? JsonObject ?: return null
    fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
    return InstallItem(
        name = str("name") ?: return null, kind = str("kind") ?: "lora", state = str("state") ?: "unknown",
        sizeMb = (o["size_mb"] as? JsonPrimitive)?.intOrNull, warn = str("warn"), detail = str("detail"), trigger = str("trigger"),
    )
}

private fun JsonElement?.strings(): List<String> = (this as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

fun parseBuilderStatus(json: JsonElement): BuilderStatus? {
    val o = json as? JsonObject ?: return null
    fun bool(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false
    val busy = (o["busy"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() && it != "0" && it != "false" }
    return BuilderStatus(
        online = bool("online"),
        billing = bool("billing"),
        busy = busy,
        detail = (o["detail"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull,
        transformers = o["transformers"].strings(),
        loras = o["loras"].strings(),
        clips = o["clips"].strings(),
        writers = (o["writers"] as? JsonArray).orEmpty().mapNotNull { w ->
            val a = w as? JsonArray ?: return@mapNotNull null
            val id = (a.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            id to ((a.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: id)
        },
        decoders = (o["decoders"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.booleanOrNull?.let { k to it } }.toMap(),
        distilled = bool("distilled"),
        triggers = (o["triggers"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap(),
        installs = (o["installs"] as? JsonObject).orEmpty().values.mapNotNull { parseInstallItem(it) },
    )
}

/** Strength a LoRA starts at, by what its name says it is (ported from the web builder). */
fun startStrength(name: String?): Double {
    val n = (name ?: "").lowercase()
    return when {
        Regex("camera|dolly|orbit|zoom").containsMatchIn(n) -> 0.35
        Regex("light|lux|glow").containsMatchIn(n) -> 0.35
        Regex("thrust|motion|action").containsMatchIn(n) -> 0.45
        Regex("style|beanflk|cinematic").containsMatchIn(n) -> 0.5
        else -> 0.65
    }
}

/** The web builder's defaults(): an anatomy LoRA first, then a motion LoRA; everything else at the hub's defaults. */
fun defaultOpts(s: BuilderStatus): BuilderOpts {
    val l = s.loras
    val first = l.firstOrNull { Regex("coachbate", RegexOption.IGNORE_CASE).containsMatchIn(it) && Regex("penis", RegexOption.IGNORE_CASE).containsMatchIn(it) && !Regex("uncut", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        ?: l.firstOrNull { Regex("coachbate", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        ?: l.firstOrNull { Regex("muscle|penile|praxis|anatomy|defined", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        ?: l.firstOrNull()
    val motion = l.firstOrNull { Regex("thrust|motion", RegexOption.IGNORE_CASE).containsMatchIn(it) && it != first }
    val loras = buildList {
        if (first != null) add(first to startStrength(first))
        if (motion != null) add(motion to 0.45)
    }
    return BuilderOpts(transformer = null, loras = loras, distill = 1.0, vae = "quality", clip = null, writer = "x-ai/grok-4.5")
}

/** Drop choices the hub no longer has (a LoRA that was removed from Colab), as the web builder does. */
fun reconcileOpts(o: BuilderOpts, s: BuilderStatus): BuilderOpts = o.copy(
    loras = o.loras.filter { it.first in s.loras },
    transformer = o.transformer?.takeIf { it in s.transformers },
    clip = o.clip?.takeIf { it in s.clips },
)

class BuilderPicture(val name: String, val bytes: ByteArray) {
    val contentType: String get() = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; "bmp" -> "image/bmp"; else -> "image/jpeg"
    }
}

data class BuildRequest(
    val prompt: String,
    val picture: BuilderPicture? = null,
    val frames: Int = 49,
    val parts: Int = 1,
    val size: String = "landscape",
    val seed: Long? = null,
    /** Id of a finished clip to continue from; the new parts start on its last frame. */
    val continueFrom: String? = null,
    val opts: BuilderOpts = BuilderOpts(),
) {
    /** One fresh clip goes to /render; several clips, or any continuation, go to /chain. */
    val isChain: Boolean get() = parts > 1 || continueFrom != null
    val endpoint: String get() = if (isChain) "/api/ltx/chain" else "/api/ltx/render"

    /** The picture only counts for a fresh run; a continuation starts from the clip's last frame. */
    val usesPicture: Boolean get() = continueFrom == null && picture != null

    /** Text fields of the multipart form, named exactly as the hub's endpoints expect. */
    fun fields(): List<Pair<String, String>> = buildList {
        if (isChain) {
            if (continueFrom != null) add("from_job" to continueFrom) else add("size" to this@BuildRequest.size)
            add("idea" to prompt)
            add("parts" to parts.toString())
            add("frames" to frames.toString())
        } else {
            add("prompt" to prompt)
            add("frames" to frames.toString())
            add("size" to this@BuildRequest.size)
        }
        add("opts" to opts.toJson().toString())
        if (seed != null) add("seed" to seed.toString())
    }
}

/** Same checks and wording as the web builder; null means the request may be sent. */
fun validateRequest(r: BuildRequest): String? {
    val text = r.prompt.trim()
    if (r.frames !in LTX_FRAMES) return "Pick a length from the list."
    if (r.size !in LTX_SIZES) return "Pick landscape, portrait or square."
    if (r.parts !in 1..LTX_MAX_PARTS) return "Between 1 and $LTX_MAX_PARTS clips in a row."
    if (r.isChain) {
        if (r.continueFrom == null && !r.usesPicture && text.isEmpty()) return "With no picture, describe who is in the video and what happens."
    } else if (text.length < 10) {
        return if (r.usesPicture) "Write what happens in the clip." else "Describe who is in the video and what happens."
    }
    if (r.picture != null && r.picture.bytes.size > 30_000_000) return "Pick a picture under 30 MB."
    return null
}

/** The label the Render button carries, so the mode is clear before pressing it. */
fun renderButtonLabel(r: BuildRequest, sending: Boolean): String {
    if (sending) return "Sending…"
    val n = if (r.parts > 1) "Render ${r.parts} clips" else "Render"
    return when {
        r.continueFrom != null -> "Continue: $n"
        r.usesPicture -> n
        else -> "Text-to-video: $n"
    }
}

data class BuilderJob(
    val id: String,
    val kind: String?,
    val status: String,
    val step: String?,
    val name: String,
    val frames: Int?,
    val size: String?,
    val seed: Long?,
    val textToVideo: Boolean,
    val out: String?,
    val error: String?,
    val fromJob: String?,
    val partsTotal: Int,
    val partsDone: Int,
    val prompt: String?,
    val idea: String?,
    val lines: List<String>,
    val opts: BuilderOpts?,
    val created: Double?,
) {
    val live: Boolean get() = status == "queued" || status == "rendering"
    val done: Boolean get() = status == "done" && !out.isNullOrBlank()
    val isSharpen: Boolean get() = kind == "sharpen"
    val isChain: Boolean get() = kind == "chain"

    /** Text to put back in the box when reusing this job. */
    val reusePrompt: String get() = when {
        lines.isNotEmpty() -> lines.joinToString("\n")
        !idea.isNullOrBlank() -> idea
        else -> prompt.orEmpty()
    }

    /**
     * A row title. A director plan's stored name starts with bookkeeping ("DURATION: 10 seconds"), which tells
     * you nothing, so for those the START STATE (or ACTION) line is used. The hub stores only the first 60
     * characters of the name, so a cut-off one gets an ellipsis.
     */
    val shortName: String get() {
        val lines = name.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return "Untitled"
        val bookkeeping = Regex("^[A-Z][A-Z ]{2,24}:").containsMatchIn(lines[0])
        val idx = if (bookkeeping) listOf("START STATE:", "ACTION:").firstNotNullOfOrNull { k -> lines.indexOfFirst { it.startsWith(k) }.takeIf { it >= 0 } } ?: 0 else 0
        val line = lines[idx]
        val text = if (line.startsWith("START STATE:") || line.startsWith("ACTION:")) line.substringAfter(':').trim() else line
        val cutOff = name.length >= 60 && idx == lines.lastIndex
        return (text.take(90) + if (cutOff) "…" else "").ifEmpty { "Untitled" }
    }
}

/** Null when the reply is not a list at all (an error page, say): that must not read as "no jobs". */
fun parseBuilderJobs(json: JsonElement): List<BuilderJob>? {
    val arr = json as? JsonArray ?: return null
    return arr.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        val id = str("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: return@mapNotNull null
        val parts = o["parts"] as? JsonArray
        BuilderJob(
            id = id,
            kind = str("kind"),
            status = str("status") ?: "unknown",
            step = str("step"),
            name = str("name") ?: "",
            frames = (o["frames"] as? JsonPrimitive)?.intOrNull,
            size = str("size"),
            seed = (o["seed"] as? JsonPrimitive)?.longOrNull,
            textToVideo = (o["t2v"] as? JsonPrimitive)?.booleanOrNull ?: false,
            out = str("out"),
            error = str("error"),
            fromJob = str("from_job"),
            partsTotal = parts?.size ?: 0,
            partsDone = parts?.count { ((it as? JsonObject)?.get("done") as? JsonPrimitive)?.booleanOrNull == true } ?: 0,
            prompt = str("prompt"),
            idea = str("idea"),
            lines = (o["lines"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            opts = BuilderOpts.fromJson(o["opts"]),
            created = (o["created"] as? JsonPrimitive)?.doubleOrNull,
        )
    }
}

/** What the hub's FastAPI errors look like: `{"detail": "text"}` or a list of `{"msg": ...}`. */
fun hubDetail(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
    return when (val d = root["detail"]) {
        is JsonPrimitive -> d.contentOrNull
        is JsonArray -> d.mapNotNull { ((it as? JsonObject)?.get("msg") as? JsonPrimitive)?.contentOrNull }.joinToString("; ").ifBlank { null }
        else -> null
    }
}

/**
 * Saved between runs (the web builder keeps the same things in localStorage): the text, the settings
 * and the model choices. The picture is remembered by path only.
 */
data class BuilderDraft(
    val prompt: String = "",
    val frames: Int = 49,
    val parts: Int = 1,
    val size: String = "landscape",
    val seed: String = "",
    val opts: BuilderOpts? = null,
    val picturePath: String? = null,
) {
    fun toJson(): String = buildJsonObject {
        put("prompt", prompt); put("frames", frames); put("parts", parts); put("size", size); put("seed", seed)
        if (opts != null) put("opts", opts.toJson())
        if (picturePath != null) put("picturePath", picturePath)
    }.toString()

    companion object {
        /** A damaged or hand-edited file must never stop the screen opening: anything unreadable falls back to the default. */
        fun parse(text: String?): BuilderDraft {
            val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text ?: "") }.getOrNull() as? JsonObject ?: return BuilderDraft()
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            val frames = (o["frames"] as? JsonPrimitive)?.intOrNull?.takeIf { it in LTX_FRAMES } ?: 49
            val parts = (o["parts"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..LTX_MAX_PARTS } ?: 1
            val size = str("size")?.takeIf { it in LTX_SIZES } ?: "landscape"
            return BuilderDraft(
                prompt = str("prompt").orEmpty(), frames = frames, parts = parts, size = size,
                seed = str("seed").orEmpty().filter { it.isDigit() }.take(18),
                opts = BuilderOpts.fromJson(o["opts"]), picturePath = str("picturePath"),
            )
        }
    }
}

/** The next length up, for "same seed, longer"; the longest stays the longest. */
fun longerFrames(frames: Int): Int = LTX_FRAMES.firstOrNull { it > frames } ?: LTX_FRAMES.last()
