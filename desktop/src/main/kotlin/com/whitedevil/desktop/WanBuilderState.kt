package com.whitedevil.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.whitedevil.desktop.ops.ChainSpec
import com.whitedevil.desktop.ops.OpsResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Base64

/** What is remembered between runs: the form, the generation being followed, and the send settings. */
data class WanDraft(val brief: WanBrief = WanBrief(), val jobId: String? = null, val picturePath: String? = null, val name: String = "", val seed: String = "") {
    fun toJson(): String = buildJsonObject {
        val b = brief
        put("idea", b.idea); put("mode", b.mode); put("imageDescription", b.imageDescription); put("cast", b.cast); put("frames", b.frames)
        put("clips", b.clips); put("link", b.link); put("beats", b.beats); put("camera", b.camera); put("framing", b.framing)
        put("orient", b.orient); put("motion", b.motion); put("forbid", b.forbid); put("style", b.style); put("words", b.words)
        put("llmModel", b.llmModel); put("temperature", b.temperature); put("appendDefaultNegative", b.appendDefaultNegative)
        put("jobId", jobId); put("picturePath", picturePath); put("name", name); put("seed", seed)
    }.toString()

    companion object {
        /** A damaged or hand-edited file must never stop the screen opening: anything unreadable falls back to the default. */
        fun parse(text: String?): WanDraft {
            val o = runCatching { Json.parseToJsonElement(text ?: "") }.getOrNull() as? JsonObject ?: return WanDraft()
            val d = WanBrief()
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            fun i(k: String) = (o[k] as? JsonPrimitive)?.intOrNull
            val frames = i("frames")?.takeIf { f -> f in WAN_DURATIONS.map { it.first } } ?: d.frames
            return WanDraft(
                brief = WanBrief(
                    idea = s("idea").orEmpty(), mode = s("mode")?.takeIf { it == "i2v" || it == "t2v" } ?: d.mode,
                    imageDescription = s("imageDescription").orEmpty(), cast = i("cast")?.takeIf { it in 0..10 } ?: d.cast, frames = frames,
                    clips = i("clips")?.coerceIn(2, WAN_MAX_CLIPS) ?: d.clips, link = s("link")?.takeIf { it == "continuous" || it == "cut" } ?: d.link,
                    beats = s("beats").orEmpty(), camera = s("camera") ?: d.camera, framing = s("framing")?.takeIf { it in WAN_FRAMINGS } ?: d.framing,
                    orient = s("orient")?.takeIf { it == "landscape" || it == "portrait" } ?: d.orient,
                    motion = s("motion")?.takeIf { m -> WAN_MOTIONS.any { it.first == m } } ?: d.motion,
                    forbid = s("forbid").orEmpty(), style = s("style").orEmpty(), words = s("words")?.takeIf { w -> WAN_WORDS.any { it.first == w } } ?: d.words,
                    llmModel = s("llmModel")?.takeIf { it.isNotBlank() } ?: d.llmModel,
                    temperature = (o["temperature"] as? JsonPrimitive)?.doubleOrNull?.coerceIn(0.0, 2.0) ?: d.temperature,
                    appendDefaultNegative = (o["appendDefaultNegative"] as? JsonPrimitive)?.booleanOrNull ?: d.appendDefaultNegative,
                ),
                jobId = s("jobId")?.takeIf { it.matches(Regex("[\\w-]{1,64}")) }, picturePath = s("picturePath"),
                name = s("name").orEmpty(), seed = s("seed").orEmpty().filter { it.isDigit() }.take(9),
            )
        }
    }
}

/** A chain the user picked from disk. It is sent as it is: the file's negatives already include the default. */
class LoadedChain(val spec: ChainSpec)

@Stable
class WanBuilderState(
    private val scope: CoroutineScope,
    val client: WanGenClient,
    private val draftFile: File?,
    initial: WanDraft,
) {
    // ---- the brief
    var idea by mutableStateOf(initial.brief.idea)
    var mode by mutableStateOf(initial.brief.mode)
    var imageDescription by mutableStateOf(initial.brief.imageDescription)
    var cast by mutableIntStateOf(initial.brief.cast)
    var frames by mutableIntStateOf(initial.brief.frames)
    var clipsCount by mutableIntStateOf(initial.brief.clips)
    var link by mutableStateOf(initial.brief.link)
    var beats by mutableStateOf(initial.brief.beats)
    var camera by mutableStateOf(initial.brief.camera)
    var framing by mutableStateOf(initial.brief.framing)
    var orient by mutableStateOf(initial.brief.orient)
    var motion by mutableStateOf(initial.brief.motion)
    var forbid by mutableStateOf(initial.brief.forbid)
    var style by mutableStateOf(initial.brief.style)
    var words by mutableStateOf(initial.brief.words)
    var llmModel by mutableStateOf(initial.brief.llmModel)
    var temperature by mutableStateOf(initial.brief.temperature.toString())
    var appendNegative by mutableStateOf(initial.brief.appendDefaultNegative)

    // ---- sending
    var name by mutableStateOf(initial.name)
    var seed by mutableStateOf(initial.seed)
    var nextUp by mutableStateOf(false)

    // ---- the start picture
    var picture by mutableStateOf<BuilderPicture?>(null)
        private set
    var pictureBitmap by mutableStateOf<ImageBitmap?>(null)
        private set
    var picturePath by mutableStateOf<String?>(null)
        private set

    // ---- the OpenRouter key (kept on the hub)
    var keyConfigured by mutableStateOf<Boolean?>(null)
        private set
    var keyInput by mutableStateOf("")

    // ---- generation
    var jobId by mutableStateOf(initial.jobId)
        private set
    var generating by mutableStateOf(false)
        private set
    var progress by mutableStateOf<String?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
    var canResume by mutableStateOf(false)
        private set

    // ---- the chain being reviewed
    var chain by mutableStateOf<WanChain?>(null)
        private set
    var meta by mutableStateOf<WanMeta?>(null)
        private set
    var clips by mutableStateOf<List<WanClip>>(emptyList())
        private set
    var loaded by mutableStateOf<LoadedChain?>(null)
        private set

    private var follower: Job? = null

    init {
        initial.picturePath?.let { p -> scope.launch { loadPicture(File(p), quiet = true) } }
        // Re-attach to a generation that was running when the app closed: it keeps running on the hub.
        initial.jobId?.let { follow(it) }
    }

    fun brief() = WanBrief(
        idea = idea, mode = mode, imageDescription = imageDescription, cast = cast, frames = frames, clips = clipsCount, link = link,
        beats = beats, camera = camera, framing = framing, orient = orient, motion = motion, forbid = forbid, style = style, words = words,
        llmModel = llmModel, temperature = temperature.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: 0.7, appendDefaultNegative = appendNegative,
    )

    fun draft() = WanDraft(brief(), jobId, picturePath, name, seed)

    fun saveDraft() {
        val f = draftFile ?: return
        runCatching { f.parentFile?.mkdirs(); f.writeText(draft().toJson()) }
    }

    // ---- key

    fun refreshKey() {
        scope.launch { (client.keyConfigured() as? MediaResult.Ok)?.let { keyConfigured = it.value } }
    }

    fun saveKey() {
        scope.launch {
            when (val r = client.saveKey(keyInput)) {
                is MediaResult.Ok -> { keyInput = ""; keyConfigured = true; problem = null }
                is MediaResult.Failure -> problem = r.error.message
            }
        }
    }

    // ---- picture

    fun choosePicture() {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val d = FileDialog(null as Frame?, "Choose the start picture", FileDialog.LOAD)
                d.isVisible = true
                d.file?.let { File(d.directory, it) }
            } ?: return@launch
            loadPicture(file, quiet = false)
        }
    }

    private suspend fun loadPicture(file: File, quiet: Boolean) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (!file.isFile) null
                else if (file.length() > 30_000_000) "big"
                else { val bytes = file.readBytes(); BuilderPicture(file.name, bytes) to decodeToBitmap(bytes) }
            }.getOrNull()
        }
        when (result) {
            is Pair<*, *> -> {
                picture = result.first as BuilderPicture; pictureBitmap = result.second as ImageBitmap; picturePath = file.absolutePath; problem = null
            }
            "big" -> if (!quiet) problem = "Pick a picture under 30 MB."
            else -> if (!quiet) problem = "That file isn't a picture the app can read."
        }
    }

    fun clearPicture() { picture = null; pictureBitmap = null; picturePath = null }

    // ---- generating

    fun generate() {
        if (generating) return
        val b = brief()
        validateBrief(b, picture != null)?.let { problem = it; return }
        generating = true; problem = null; progress = "Starting…"; canResume = false; loaded = null
        scope.launch {
            val m = WanMeta.now(b)
            when (val r = client.start(chainRequest(b, m, keyInput.takeIf { it.isNotBlank() }))) {
                is MediaResult.Ok -> {
                    if (keyInput.isNotBlank()) { keyInput = ""; keyConfigured = true }
                    jobId = r.value; saveDraft(); follow(r.value)
                }
                is MediaResult.Failure -> { generating = false; progress = null; problem = r.error.message }
            }
        }
    }

    fun resume() {
        val id = jobId ?: return
        scope.launch {
            when (val r = client.resume(id)) {
                is MediaResult.Ok -> { problem = null; canResume = false; follow(id) }
                is MediaResult.Failure -> problem = r.error.message
            }
        }
    }

    /** Polls the hub's generation job every 3 s until it ends. The job keeps running on the hub if this app closes. */
    private fun follow(id: String) {
        follower?.cancel()
        generating = true
        follower = scope.launch {
            var misses = 0
            while (true) {
                when (val r = client.job(id)) {
                    is MediaResult.Ok -> {
                        misses = 0
                        val j = r.value
                        progress = genProgress(j)
                        j.chain?.takeIf { it.clips.isNotEmpty() }?.let { c -> if (c.clips.size != clips.size || j.status == "done") adopt(c, j.meta) }
                        when (j.status) {
                            "done" -> { generating = false; return@launch }
                            "error", "interrupted" -> {
                                generating = false; canResume = true
                                if (j.status == "error") problem = j.error ?: "The generation failed."
                                return@launch
                            }
                        }
                    }
                    is MediaResult.Failure -> {
                        // A 404 means the hub no longer knows this job; anything else may be a blip, so keep trying a while.
                        misses++
                        if (r.error.status == 404 || misses > 40) {
                            generating = false; jobId = null; progress = null; problem = r.error.message; saveDraft(); return@launch
                        }
                        progress = "The hub isn't answering, retrying… (the generation continues on the hub)"
                    }
                }
                delay(3_000)
            }
        }
    }

    private fun adopt(c: WanChain, m: WanMeta?) {
        chain = c; meta = m ?: meta ?: WanMeta.now(brief()); clips = c.clips; loaded = null
    }

    // ---- reviewing and editing

    fun edit(index: Int, change: (WanClip) -> WanClip) {
        if (index !in clips.indices) return
        clips = clips.toMutableList().also { it[index] = change(it[index]) }
    }

    fun removeClip(index: Int) {
        if (index !in clips.indices || clips.size <= 1) return
        clips = clips.toMutableList().also { it.removeAt(index) }
    }

    fun discardChain() { chain = null; meta = null; clips = emptyList(); loaded = null; jobId = null; progress = null; canResume = false; saveDraft() }

    // ---- sending

    /** Reads chain file(s) from disk, to send without generating. */
    fun chooseChainFiles() {
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                val d = FileDialog(null as Frame?, "Choose wan_chain JSON file(s)", FileDialog.LOAD)
                d.isMultipleMode = true
                d.isVisible = true
                d.files.toList()
            }
            if (picked.isEmpty()) return@launch
            val texts = withContext(Dispatchers.IO) {
                runCatching { picked.map { f -> if (f.length() > 5_000_000) error("${f.name} is larger than 5 MB, which is not a chain file.") else f.readText() } }
            }
            val list = texts.getOrElse { problem = it.message; return@launch }
            when (val r = ChainSpec.fromFiles(list)) {
                is OpsResult.Ok -> { loaded = LoadedChain(r.value); chain = null; clips = emptyList(); name = r.value.suggestedName; problem = null }
                is OpsResult.Err -> problem = r.error.message
            }
        }
    }

    val clipTotal: Int get() = loaded?.spec?.clipCount ?: clips.size
    val canSend: Boolean get() = loaded != null || (chain != null && clips.isNotEmpty())

    /** The chain to send: the loaded file as it is, or the reviewed clips rebuilt in the runner's format with the start picture. */
    suspend fun buildChain(): OpsResult<ChainSpec> {
        loaded?.let { return OpsResult.Ok(it.spec) }
        val c = chain ?: return OpsResult.Err(com.whitedevil.desktop.ops.OpsError("There is no chain to send yet.", kind = com.whitedevil.desktop.ops.OpsErrorKind.BadShape))
        val m = meta ?: WanMeta.now(brief())
        val dataUrl = if (c.firstI2V) picture?.let { p -> withContext(Dispatchers.Default) { startImageDataUrl(p) } } else null
        val spec = withStartImage(chainSpec(c.copy(clips = clips), m, appendNegative), dataUrl)
        return ChainSpec.fromFiles(listOf(spec.toString()))
    }

    fun suggestedName(): String = name.ifBlank { idea.trim().take(60).ifBlank { "wan_chain" } }
}

/** The runner takes the picture as a data URL. Original bytes when they are small, otherwise a 2048 px JPEG. */
fun startImageDataUrl(p: BuilderPicture): String? =
    if (p.bytes.size <= 6_000_000) "data:${p.contentType};base64," + Base64.getEncoder().encodeToString(p.bytes)
    else shrinkToJpegDataUrl(p.bytes, maxSide = 2048, maxChars = 12_000_000)
