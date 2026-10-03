package com.whitedevil.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

data class BuilderMessage(val text: String, val error: Boolean)

/**
 * All of the builder's state and actions. The screen only draws it; every rule (what is sent, what is
 * checked) lives in LtxBuilderModel.kt where it is tested. Each action reports back through [message],
 * so a press always shows what happened, never nothing.
 */
@Stable
class LtxBuilderState(
    private val scope: CoroutineScope,
    val client: LtxBuilderClient,
    private val draftFile: File?,
    initial: BuilderDraft,
) {
    var prompt by mutableStateOf(initial.prompt)
    var frames by mutableIntStateOf(initial.frames)
    var parts by mutableIntStateOf(initial.parts)
    var size by mutableStateOf(initial.size)
    var seed by mutableStateOf(initial.seed)
    var opts by mutableStateOf(initial.opts)

    var picture by mutableStateOf<BuilderPicture?>(null)
        private set
    var picturePath by mutableStateOf<String?>(null)
        private set
    var pictureBitmap by mutableStateOf<ImageBitmap?>(null)
        private set
    var cont by mutableStateOf<BuilderJob?>(null)
        private set

    var status by mutableStateOf<BuilderStatus?>(null)
        private set
    var statusProblem by mutableStateOf<String?>(null)
        private set
    var jobs by mutableStateOf<List<BuilderJob>>(emptyList())
        private set
    var jobsLoaded by mutableStateOf(false)
        private set
    var jobsProblem by mutableStateOf<String?>(null)
        private set

    var sending by mutableStateOf(false)
        private set
    var writing by mutableStateOf(false)
        private set
    var message by mutableStateOf<BuilderMessage?>(null)
    var installUrl by mutableStateOf("")
    var installKind by mutableStateOf("lora")
    var installing by mutableStateOf(false)
        private set

    private var refreshing: Job? = null

    init {
        // Bring the last picture back if its file is still there.
        initial.picturePath?.let { p -> scope.launch { loadPicture(File(p), quiet = true) } }
    }

    fun request(): BuildRequest = BuildRequest(
        prompt = prompt.trim(),
        picture = picture,
        frames = frames,
        parts = parts,
        size = size,
        seed = seed.toLongOrNull(),
        continueFrom = cont?.id,
        opts = opts ?: status?.let(::defaultOpts) ?: BuilderOpts(),
    )

    fun draft() = BuilderDraft(prompt, frames, parts, size, seed, opts, picturePath)

    fun saveDraft() {
        val f = draftFile ?: return
        runCatching { f.parentFile?.mkdirs(); f.writeText(draft().toJson()) }
    }

    // ---- reading the hub ----

    fun refreshStatus() {
        scope.launch {
            when (val r = client.status()) {
                is MediaResult.Ok -> {
                    status = r.value; statusProblem = null
                    opts = opts?.let { reconcileOpts(it, r.value) } ?: defaultOpts(r.value)
                }
                is MediaResult.Failure -> statusProblem = r.error.message
            }
        }
    }

    fun refreshJobs() {
        refreshing?.cancel()
        refreshing = scope.launch {
            when (val r = client.jobs()) {
                is MediaResult.Ok -> { jobs = r.value; jobsProblem = null; jobsLoaded = true }
                // Keep showing the last list; say the refresh failed rather than blanking the clips.
                is MediaResult.Failure -> { jobsProblem = r.error.message; jobsLoaded = true }
            }
        }
    }

    // ---- the picture ----

    fun choosePicture() {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val d = FileDialog(null as Frame?, "Choose a starting picture", FileDialog.LOAD)
                d.isVisible = true
                d.file?.let { File(d.directory, it) }
            } ?: return@launch
            loadPicture(file, quiet = false)
        }
    }

    private sealed interface Loaded {
        class Picture(val pic: BuilderPicture, val bmp: ImageBitmap) : Loaded
        object TooBig : Loaded
        object Unreadable : Loaded
    }

    private suspend fun loadPicture(file: File, quiet: Boolean) {
        val loaded: Loaded = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    !file.isFile -> Loaded.Unreadable
                    file.length() > 30_000_000 -> Loaded.TooBig
                    else -> { val bytes = file.readBytes(); Loaded.Picture(BuilderPicture(file.name, bytes), decodeToBitmap(bytes)) }
                }
            }.getOrDefault(Loaded.Unreadable)
        }
        when (loaded) {
            is Loaded.Picture -> {
                picture = loaded.pic; pictureBitmap = loaded.bmp; picturePath = file.absolutePath
                cont = null // a picture and a continuation are different starting points
                message = null
            }
            Loaded.TooBig -> if (!quiet) message = BuilderMessage("Pick a picture under 30 MB.", true)
            Loaded.Unreadable -> if (!quiet) message = BuilderMessage("That file isn't a picture the app can read.", true)
        }
    }

    fun clearPicture() { picture = null; pictureBitmap = null; picturePath = null }

    // ---- actions ----

    fun continueFrom(job: BuilderJob) {
        cont = job
        clearPicture()
        message = BuilderMessage("Continuing from “${job.shortName}”. Describe what happens next, or leave it empty.", false)
    }

    fun stopContinuing() { cont = null }

    /** Put a job's text and settings back in the form, the way the web builder's Reuse does. */
    fun reuse(job: BuilderJob) {
        cont = null
        prompt = job.reusePrompt
        parts = if (job.isChain && job.partsTotal in 1..LTX_MAX_PARTS) job.partsTotal else 1
        job.frames?.takeIf { it in LTX_FRAMES }?.let { frames = it }
        job.size?.takeIf { it in LTX_SIZES }?.let { size = it }
        seed = job.seed?.toString().orEmpty()
        job.opts?.let { o -> opts = status?.let { reconcileOpts(o, it) } ?: o }
        message = BuilderMessage("Loaded “${job.shortName}” into the form.", false)
    }

    /** Clear the text, picture, seed and continuation so a brand new render can start; keeps length, shape and models. */
    fun newRender() {
        if (sending) return
        cont = null; prompt = ""; parts = 1; seed = ""
        clearPicture()
        message = BuilderMessage("Cleared. Pick a picture or describe a new video.", false)
        saveDraft()
    }

    fun render() {
        if (sending) return
        val req = request()
        validateRequest(req)?.let { message = BuilderMessage(it, true); return }
        sending = true; message = null
        scope.launch {
            when (val r = client.submit(req)) {
                is MediaResult.Ok -> {
                    message = BuilderMessage(
                        if (req.isChain) "Started. It writes and renders each part in turn; the joined video lands in Clips and in Renders."
                        else "Rendering. It appears in Clips and in Renders.",
                        false,
                    )
                    cont = null
                    refreshJobs(); refreshStatus()
                }
                is MediaResult.Failure -> message = BuilderMessage(r.error.message, true)
            }
            sending = false
        }
    }

    /** "Write it for me": the prompt writer reads the picture (or the clip being continued) and writes the full prompt. */
    fun assist() {
        if (writing) return
        val chain = parts > 1 || cont != null
        val text = prompt.trim()
        val pic = if (cont == null) picture else null
        if (text.isEmpty() && pic == null && cont == null) {
            message = BuilderMessage("Type a few words or pick a picture first.", true); return
        }
        writing = true; message = null
        scope.launch {
            val dataUrl = pic?.let { p -> withContext(Dispatchers.Default) { shrinkToJpegDataUrl(p.bytes) } }
            if (pic != null && dataUrl == null) {
                message = BuilderMessage("The picture couldn't be prepared for the prompt writer; pick it again.", true)
            } else {
                val req = AssistRequest(text, frames, if (chain) parts else 1, dataUrl, cont?.id, opts?.writer)
                when (val r = client.assist(req)) {
                    is MediaResult.Ok -> { prompt = r.value; message = null }
                    is MediaResult.Failure -> message = BuilderMessage(r.error.message, true)
                }
            }
            writing = false
        }
    }

    /** Same seed and settings at the next length up: finds a good take short, then redoes it longer. */
    fun sameSeedLonger(job: BuilderJob) {
        reuse(job)
        job.seed?.let { seed = it.toString() }
        frames = longerFrames(job.frames ?: frames)
        message = BuilderMessage("Loaded “${job.shortName}” with the same seed at ${LTX_LENGTH_LABEL[frames] ?: "${frames}f"}. Press Render.", false)
    }

    fun install() {
        if (installing) return
        installing = true
        scope.launch {
            when (val r = client.install(installUrl, installKind)) {
                is MediaResult.Ok -> {
                    installUrl = ""
                    message = BuilderMessage(r.value.warn ?: "Downloading ${r.value.name} to Colab.", r.value.warn != null)
                    refreshStatus()
                }
                is MediaResult.Failure -> message = BuilderMessage(r.error.message, true)
            }
            installing = false
        }
    }

    fun cancel(job: BuilderJob) {
        scope.launch {
            when (val r = client.cancel(job.id)) {
                is MediaResult.Ok -> message = BuilderMessage("Cancelled “${job.shortName}”.", false)
                is MediaResult.Failure -> message = BuilderMessage(r.error.message, true)
            }
            refreshJobs()
        }
    }
}
