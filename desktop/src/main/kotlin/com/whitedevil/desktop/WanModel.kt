package com.whitedevil.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * The Wan 2.2 14B chain builder's rules, as plain code. It reproduces what hub/static/generator.html does
 * for the I2V-A14B target: the same brief lines, the same system prompt, the same request to
 * POST /api/gen/chain, and the same exported chain format that the 14B runner reads. The prompt text is
 * the generator page's own (src/main/resources/wan, cut out of the page by script) and WanModelTest fails if
 * the two ever differ.
 */
data class WanTarget(
    val modelTarget: String, val label: String, val fps: Int, val landscape: String, val portrait: String,
    val task: String, val ckpt: String, val runner: String,
    val guide: Double = 5.0, val shift: Double = 5.0, val steps: Int = 50,
)

/** The 14B image-to-video model that the Thunder 14B queue always renders with (thunder.py forces wan22-14b-i2v). */
val WAN_14B = WanTarget(
    modelTarget = "Wan2.2-I2V-A14B", label = "WAN 2.2 I2V-A14B", fps = 16, landscape = "1280*720", portrait = "720*1280",
    task = "i2v-A14B", ckpt = "./Wan2.2-I2V-A14B", runner = "wan22-14b-i2v",
)

const val WAN_CHUNK = 12
const val WAN_MAX_CLIPS = 60
const val WAN_DEFAULT_LLM = "cognitivecomputations/dolphin-mistral-24b-venice-edition:free"

val WAN_DURATIONS = listOf(49 to "2 s (49 frames)", 73 to "3 s (73 frames)", 81 to "5 s (81 frames, the 14B recipe)", 97 to "6 s (97 frames)", 121 to "7.5 s (121 frames)")

val WAN_CAMERAS = listOf(
    "static camera, eye-level, no zoom, no pan" to "Static, eye-level",
    "slow dolly in" to "Slow dolly in",
    "slow dolly out" to "Slow dolly out",
    "slow pan left to right" to "Slow pan",
    "slow orbit around the subject" to "Slow orbit",
    "handheld, subtle natural shake" to "Handheld",
    "tracking shot following the subject" to "Tracking",
    "crane shot rising upward" to "Crane up",
    "" to "Let the model choose",
)
val WAN_FRAMINGS = listOf("medium shot", "close-up", "extreme close-up", "medium close-up", "wide shot", "full shot", "over-the-shoulder", "low angle", "high angle")
val WAN_MOTIONS = listOf(
    "very subtle, minimal movement" to "Very subtle", "gentle, natural movement" to "Gentle",
    "moderate, expressive movement" to "Moderate", "dynamic, energetic movement" to "Dynamic",
)
val WAN_WORDS = listOf("60-90" to "60–90 words", "80-120" to "80–120 words", "120-170" to "120–170 words")

// ---------------------------------------------------------------- prompt text (from the generator page)

object WanPrompts {
    private fun resource(name: String): String =
        WanPrompts::class.java.getResourceAsStream("/wan/$name")?.use { String(it.readBytes(), Charsets.UTF_8) }?.replace("\r\n", "\n")
            ?: error("missing resource /wan/$name")

    val coreRules: String by lazy { resource("core_rules.txt") }
    private val chainTemplate: String by lazy { resource("system_chain.txt") }
    val defaultNegative: String by lazy { resource("default_negative.txt") }

    /** SYSTEM_CHAIN() of the generator page for [t]. */
    fun systemChain(t: WanTarget): String =
        chainTemplate.replace("{{LABEL}}", t.label).replace("{{FPS}}", t.fps.toString()).replace("{{CORE_RULES}}", coreRules)
}

/**
 * The shorthand the prompt tools rewrite before the LLM sees it (hub/static/lexicon.js, same list as
 * hub/gen.py SHORTHAND): WAN only renders what it can see, and the LLM copies the words it is given.
 */
private val LEXICON: List<Pair<Regex, String>> = listOf(
    """\bbig\s*bro\b""" to "the older, broader man",
    """\b(?:lil|little)\s*bro\b""" to "the younger, slimmer man",
    """\bbros\b""" to "the two men",
    """\bbro\b""" to "man",
    """\beach\s*others\b(?!\s+(?:penis|penises|cock|dick|body|bodies|mouth))""" to "each other's penis",
    """\bgooning\b""" to "slow, entranced masturbation with a glazed expression",
    """\bgoon(?:s|ed)?\b""" to "masturbate slowly in a trance",
    """\bedging\b""" to "stroking to the brink of orgasm, stopping with breath held, then slowly starting again",
    """\bedged\b""" to "stopped just before orgasm",
    """\bcum\s*shots?\b""" to "visible ejaculation",
    """\bcumming\b""" to "ejaculating",
    """\bcums\b""" to "ejaculates",
    """\bcum(?:med)?\b""" to "ejaculate",
    """\bpre-?cum\b""" to "a clear drop of fluid at the tip of the penis",
    """\b(?:shoot|shoots|shooting|blow|blows|blowing)\s+(?:his|their|a)\s+loads?\b""" to "ejaculating",
    """\bbust(?:s|ing)?\s+a\s+nut\b""" to "ejaculating",
    """\bnutting\b""" to "ejaculating",
    """\b(?:jerk|jack)(?:ing|s|ed)?\s+off\b""" to "masturbating",
    """\b(?:jerk|jack)(?:ing|s|ed)?\s+each\s+other(?:\s+off)?\b""" to "stroking each other's penis",
    """\bwank(?:ing|s|ed)?\b""" to "masturbating",
    """\bfap(?:ping|s|ped)?\b""" to "masturbating",
    """\bj/?o\b""" to "masturbation",
    """\bhand\s*jobs?\b|\bhj\b""" to "one man stroking the other man's penis with his hand",
    """\bblow\s*jobs?\b|\bbj\b|\bsucking\s+off\b|\bsucks?\s+(?:him|each\s+other)\s+off\b""" to "one man takes the other's penis into his mouth, lips around it, head moving slowly up and down",
    """\bdeep\s*throat(?:ing|s)?\b""" to "taking the penis fully into his mouth, down to the base",
    """\bfrot(?:ting|tage)?\b""" to "the two men pressing their erect penises together and rubbing them against each other",
    """\bboners?\b|\bhard-?ons?\b""" to "erection",
    """\bcocks\b|\bdicks\b""" to "penises",
    """\bcock\b|\bdick\b""" to "penis",
    """\bballs\b""" to "testicles",
    """\beach\s*others\b""" to "each other's",
).map { (pattern, repl) -> Regex(pattern, RegexOption.IGNORE_CASE) to repl }

fun plainWords(s: String): String = LEXICON.fold(s) { t, (re, repl) -> re.replace(t) { repl } }

// ---------------------------------------------------------------- the brief

data class WanBrief(
    val idea: String = "",
    /** "i2v" = the first clip starts from a supplied picture; "t2v" = from words alone. */
    val mode: String = "i2v",
    val imageDescription: String = "",
    val cast: Int = 2,
    val frames: Int = 81,
    val clips: Int = 4,
    val link: String = "continuous",
    val beats: String = "",
    val camera: String = WAN_CAMERAS[0].first,
    val framing: String = "medium shot",
    val orient: String = "landscape",
    val motion: String = WAN_MOTIONS[1].first,
    val forbid: String = "",
    val style: String = "",
    val words: String = "80-120",
    val llmModel: String = WAN_DEFAULT_LLM,
    val temperature: Double = 0.7,
    val appendDefaultNegative: Boolean = true,
) {
    val clipCount: Int get() = clips.coerceIn(2, WAN_MAX_CLIPS)
}

/** JavaScript prints 5 for 5.0 and 4.5 for 4.5; the brief text must read the same as the page's. */
internal fun jsNumber(d: Double): String = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

fun sizeString(b: WanBrief, t: WanTarget, sep: String): String =
    (if (b.orient == "portrait") t.portrait else t.landscape).replace("*", sep)

/** Port of sharedLines() as the relay page wraps it (every line passed through plainWords). */
fun sharedLines(b: WanBrief, t: WanTarget = WAN_14B): List<String> {
    val secs = (b.frames - 1).toDouble() / t.fps
    val lines = mutableListOf(
        "Model: ${t.label} (${t.fps} fps)",
        "First clip mode: ${if (b.mode == "i2v") "image-to-video" else "text-to-video"}",
        "Scene idea: ${b.idea.trim()}",
    )
    if (b.mode == "i2v" && b.imageDescription.isNotBlank()) lines += "Start image contains: ${b.imageDescription.trim()}"
    lines += "Exact people count: ${b.cast}"
    lines += "Clip length: ${jsNumber(secs)} seconds (${b.frames} frames at ${t.fps} fps) — pace the actions to fit"
    lines += "Framing: ${b.framing}"
    lines += "Camera: ${b.camera.ifEmpty { "model may choose, but keep it a single continuous shot" }}"
    lines += "Orientation: ${b.orient}"
    lines += "Motion intensity: ${b.motion}"
    if (b.forbid.isNotBlank()) lines += "Must not happen: ${b.forbid.trim()}"
    if (b.style.isNotBlank()) lines += "Style/mood: ${b.style.trim()}"
    lines += "Target length per positive prompt: ${b.words} words"
    return lines.map(::plainWords)
}

fun beatLines(b: WanBrief): List<String> = b.beats.lineSequence().map { plainWords(it.trim()) }.filter { it.isNotEmpty() }.toList()

/** Same checks the page makes before it will generate, plus what the 14B runner needs. */
fun validateBrief(b: WanBrief, hasPicture: Boolean): String? {
    if (b.idea.isBlank()) return "Describe the scene first."
    if (b.link !in listOf("continuous", "cut")) return "Pick how the clips link."
    if (b.frames !in WAN_DURATIONS.map { it.first }) return "Pick a clip duration from the list."
    if (b.cast !in 0..10) return "People count must be 0 to 10."
    if (b.llmModel.isBlank()) return "Set the prompt-writer model under OpenRouter settings."
    return null
}

private fun idHex(): String = UUID.randomUUID().toString().replace("-", "").take(12)

data class WanMeta(val id: String, val at: String, val llm: String, val settings: JsonObject, val brief: JsonObject) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("at", at); put("llm", llm); put("settings", settings); put("brief", brief)
    }

    companion object {
        fun now(b: WanBrief, t: WanTarget = WAN_14B, id: String = idHex(), at: String = Instant.now().toString()): WanMeta = WanMeta(
            id = id, at = at, llm = b.llmModel.trim(),
            settings = buildJsonObject {
                put("model_target", t.modelTarget); put("size", sizeString(b, t, "*")); put("frames_per_clip", b.frames)
                put("fps", t.fps); put("guide_scale", t.guide); put("sample_shift", t.shift); put("steps", t.steps)
            },
            brief = buildJsonObject {
                put("idea", b.idea.trim()); put("first_clip_mode", b.mode)
                if (b.mode == "i2v") put("start_image", b.imageDescription.trim()) else put("start_image", JsonNull)
                put("people_count", b.cast); put("camera", b.camera); put("framing", b.framing); put("motion", b.motion)
                put("must_not", b.forbid.trim()); put("style", b.style.trim())
            },
        )

        fun fromJson(e: JsonElement?): WanMeta? {
            val o = e as? JsonObject ?: return null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            return WanMeta(s("id") ?: return null, s("at") ?: "", s("llm") ?: "", o["settings"] as? JsonObject ?: return null, o["brief"] as? JsonObject ?: return null)
        }
    }
}

/** The body of POST /api/gen/chain (generator_relay.js runChain). [key] is sent only when the user typed one. */
fun chainRequest(b: WanBrief, meta: WanMeta, key: String?, t: WanTarget = WAN_14B): JsonObject = buildJsonObject {
    put("idea", b.idea.trim())
    put("system", WanPrompts.systemChain(t))
    put("shared", buildJsonArray { sharedLines(b, t).forEach { add(JsonPrimitive(it)) } })
    put("beats", buildJsonArray { beatLines(b).forEach { add(JsonPrimitive(it)) } })
    put("link", b.link)
    put("total", b.clipCount)
    put("chunk", WAN_CHUNK)
    put("model", b.llmModel.trim())
    put("temperature", b.temperature)
    if (!key.isNullOrBlank()) put("key", key.trim()) else put("key", JsonNull)
    put("meta", meta.toJson())
    put("base", buildJsonObject {
        put("link", b.link); put("frames", b.frames); put("size", sizeString(b, t, "*"))
        put("firstI2V", b.mode == "i2v"); put("task", t.task); put("ckpt", t.ckpt); put("fps", t.fps)
    })
}

// ---------------------------------------------------------------- the generated chain

/** One clip as the review list shows it and the runner receives it. Editable before sending. */
data class WanClip(val title: String, val startState: String, val prompt: String, val endState: String, val negative: String)

data class WanChain(val link: String, val frames: Int, val firstI2V: Boolean, val fps: Int, val characters: String, val setting: String, val style: String, val clips: List<WanClip>)

fun parseWanChain(chain: JsonElement?): WanChain? {
    val o = chain as? JsonObject ?: return null
    fun s(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()
    val clips = (o["clips"] as? JsonArray).orEmpty().mapNotNull { c ->
        val co = c as? JsonObject ?: return@mapNotNull null
        WanClip(s(co["title"]), s(co["start_state"]), s(co["prompt"]), s(co["end_state"]), s(co["negative"]))
    }
    val bible = o["bible"] as? JsonObject
    return WanChain(
        link = s(o["link"]).ifEmpty { "continuous" },
        frames = (o["frames"] as? JsonPrimitive)?.intOrNull ?: 81,
        firstI2V = (o["firstI2V"] as? JsonPrimitive)?.contentOrNull == "true",
        fps = (o["fps"] as? JsonPrimitive)?.intOrNull ?: 16,
        characters = s(bible?.get("characters")), setting = s(bible?.get("setting")), style = s(bible?.get("style")),
        clips = clips,
    )
}

internal fun wordCount(s: String): Int = Regex("\\S+").findAll(s.trim()).count()
private fun pad2(n: Int) = n.toString().padStart(2, '0')

/** negFor(): the clip's own negative, then WAN's official default one when that box is ticked. */
fun negativeFor(c: WanClip, appendDefault: Boolean): String =
    listOf(c.negative, if (appendDefault) WanPrompts.defaultNegative else "").filter { it.isNotEmpty() }.joinToString(", ")

/** chainJSON(): the exported chain the 14B runner reads. */
fun chainSpec(chain: WanChain, meta: WanMeta, appendDefaultNegative: Boolean): JsonObject {
    val n = chain.clips.size
    val cont = chain.link == "continuous"
    val frames = chain.frames
    val fps = (meta.settings["fps"] as? JsonPrimitive)?.intOrNull ?: chain.fps
    val stitched = (if (cont) (n * frames - (n - 1)) else n * (frames - 1)).toDouble() / fps
    return buildJsonObject {
        put("type", "chain")
        put("chain_id", meta.id)
        put("generated_at", meta.at)
        put("llm", meta.llm)
        put("settings", JsonObject(meta.settings + mapOf(
            "linking" to JsonPrimitive(chain.link),
            "clip_count" to JsonPrimitive(n),
            "stitched_seconds" to JsonPrimitive(Math.round(stitched * 100) / 100.0),
        )))
        put("brief", meta.brief)
        put("bible", buildJsonObject { put("characters", chain.characters); put("setting", chain.setting); put("style", chain.style) })
        put("clips", buildJsonArray {
            chain.clips.forEachIndexed { i, c ->
                add(buildJsonObject {
                    put("index", i + 1)
                    put("title", c.title)
                    put("input", buildJsonObject {
                        if (i == 0) {
                            if (chain.firstI2V) { put("mode", "i2v"); put("image", "start.jpg") } else put("mode", "t2v")
                        } else if (cont) {
                            put("mode", "i2v"); put("image", "last_${pad2(i)}.png"); put("from_clip", i)
                        } else put("mode", "t2v")
                    })
                    put("start_state", c.startState)
                    put("prompt", c.prompt)
                    put("negative", negativeFor(c, appendDefaultNegative))
                    put("end_state", c.endState)
                    put("output_file", "clip_${pad2(i + 1)}.mp4")
                    put("word_count", wordCount(c.prompt))
                })
            }
        })
    }
}

/** Adds the start picture for the runner (generator page: runner.start_image_b64, a data URL). */
fun withStartImage(spec: JsonObject, dataUrl: String?): JsonObject {
    if (dataUrl == null) return spec
    val runner = (spec["runner"] as? JsonObject).orEmpty()
    return JsonObject(spec + ("runner" to JsonObject(runner + ("start_image_b64" to JsonPrimitive(dataUrl)))))
}

/** Rough length shown before sending, like the page: stitched seconds for [n] clips. */
fun stitchedSeconds(frames: Int, fps: Int, n: Int, link: String): Double =
    (if (link == "continuous") (n * frames - (n - 1)) else n * (frames - 1)).toDouble() / fps

fun formatSeconds(s: Double): String = String.format(Locale.US, "%.1f s", s)

// ---------------------------------------------------------------- the hub's generation job

data class GenJob(
    val id: String, val status: String, val error: String?, val total: Int, val batches: Int, val next: Int,
    val chain: WanChain?, val meta: WanMeta?, val file: String?, val log: List<String>,
)

fun parseGenJob(json: JsonElement): GenJob? {
    val o = json as? JsonObject ?: return null
    fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    return GenJob(
        id = s("id") ?: return null, status = s("status") ?: "unknown", error = s("error"),
        total = (o["total"] as? JsonPrimitive)?.intOrNull ?: 0, batches = (o["count"] as? JsonPrimitive)?.intOrNull ?: 1,
        next = (o["next"] as? JsonPrimitive)?.intOrNull ?: 1,
        chain = parseWanChain(o["chain"]), meta = WanMeta.fromJson(o["meta"]), file = s("file"),
        log = (o["log"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
    )
}

/** Progress line like the page's. */
fun genProgress(j: GenJob): String = when (j.status) {
    "done" -> "Done — ${j.chain?.clips?.size ?: 0} clips" + if (j.batches > 1) " in ${j.batches} batches." else "."
    "error" -> "Failed: ${j.error ?: "generation failed"}"
    "interrupted" -> "Interrupted (the hub restarted). Resume to continue."
    else -> if (j.batches > 1) "Generating batch ${maxOf(1, j.next)}/${j.batches} on the hub (${j.chain?.clips?.size ?: 0} of ${j.total} clips so far)."
    else "Generating the chain on the hub."
}
