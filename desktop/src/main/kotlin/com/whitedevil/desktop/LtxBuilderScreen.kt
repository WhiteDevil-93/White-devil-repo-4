package com.whitedevil.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

/**
 * The LTX builder, native: starting picture, what happens, models, length / clips in a row / shape /
 * seed, Render and New render, and the clips below with Play, Save, Continue, Reuse and Cancel. It does
 * what hub/static/ltx/index.html does, against the same endpoints (see LtxBuilderModel.kt).
 */
@Composable
fun LtxBuilderScreen(settings: Settings) {
    val client = remember(settings.hubUrl, settings.relayUser, settings.relayPass) {
        LtxBuilderClient(settings.hubUrl, settings.relayUser, settings.relayPass)
    }
    DisposableEffect(client) { onDispose { client.close() } }
    val scope = rememberCoroutineScope()
    val draftFile = remember { File(Settings.dir, "ltx_draft.json") }
    val state = remember(client) {
        LtxBuilderState(scope, client, draftFile, BuilderDraft.parse(runCatching { draftFile.readText() }.getOrNull()))
    }
    val media = rememberMediaClient(settings)
    val actions = rememberClipActions(media)
    val thumbs = remember(media) { mutableStateMapOf<String, ImageBitmap>() }

    // Status: every 20 s. Jobs: every 8 s while one is running, every 30 s otherwise.
    LaunchedEffect(state) { while (true) { state.refreshStatus(); delay(20_000) } }
    LaunchedEffect(state) { while (true) { state.refreshJobs(); delay(if (state.jobs.any { it.live }) 8_000 else 30_000) } }
    // Save the form a moment after the last change, as the web builder does with localStorage.
    val draft = state.draft()
    LaunchedEffect(draft) { delay(500); state.saveDraft() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 1000.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Form(state)
                }
                Box(Modifier.width(1.dp).fillMaxSize().background(Forge.Line))
                Column(Modifier.weight(1.15f).fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp)) {
                    ClipsHeader(state)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.jobs, key = { it.id }) { JobCard(it, state, media, actions, thumbs) }
                        if (state.jobs.isEmpty()) item { EmptyClips(state) }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Form(state)
                ClipsHeader(state)
                state.jobs.forEach { JobCard(it, state, media, actions, thumbs) }
                if (state.jobs.isEmpty()) EmptyClips(state)
            }
        }
    }
}

// ---------------------------------------------------------------- form

@Composable
private fun Form(s: LtxBuilderState) {
    Header(s)
    PictureCard(s)
    PromptCard(s)
    ModelsCard(s)
    SettingsCard(s)
    RenderButtons(s)
}

@Composable
private fun Header(s: LtxBuilderState) {
    val st = s.status
    val (text, color) = when {
        st == null && s.statusProblem != null -> "can't reach the hub" to Forge.Bad
        st == null -> "checking…" to Forge.Mut
        !st.online && st.billing -> "BILLING · Comfy down" to Forge.Warn
        !st.online -> "not reachable" to Forge.Bad
        st.busy != null -> "busy · ${st.busy}" to Forge.Warn
        else -> "ready" to Forge.Ok
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("LTX-2.5 · on Colab", color = Forge.Dim, fontSize = 11.sp)
            Text("Build a render", color = Forge.Fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        StatusPill(text, color)
    }
    val problem = s.statusProblem ?: st?.takeIf { !it.online }?.detail
    if (problem != null) Tip(problem, Forge.Bad)
}

@Composable
private fun PictureCard(s: LtxBuilderState) = Card("1. Starting picture", "optional") {
    val cont = s.cont
    if (cont != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Continuing from “${cont.shortName}” — the new parts start on its last frame and get joined after it.", color = Forge.Fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
            SmallButton("✕", false) { s.stopContinuing() }
        }
        return@Card
    }
    Tip("With a picture, the clip starts from that exact frame (image-to-video). Leave it empty for text-to-video: the video is made from your words alone.")
    val bmp = s.pictureBitmap
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(shape).background(Forge.Well)
            .border(1.5.dp, Forge.Line, shape).clickable { s.choosePicture() },
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp, contentDescription = s.picture?.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp))
        } else {
            Text("Click to choose a picture (or leave empty for text-to-video)", color = Forge.Mut, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
        }
    }
    if (s.picture != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.picture?.name.orEmpty(), color = Forge.Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            SmallButton("CHANGE", false) { s.choosePicture() }
            Spacer(Modifier.width(8.dp))
            SmallButton("✕ NO PICTURE", false) { s.clearPicture() }
        }
    }
}

@Composable
private fun PromptCard(s: LtxBuilderState) = Card("2. What happens") {
    Tip("Type a few rough words (slang is fine) and press “Write it for me”: it looks at your picture and writes the full prompt. Edit it after if you like. With several clips, describe the whole story, or paste a director plan.")
    OutlinedTextField(
        value = s.prompt, onValueChange = { s.prompt = it },
        modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 14,
        shape = RoundedCornerShape(10.dp), colors = forgeFieldColors(),
        placeholder = { Text("e.g. he strokes slowly, looks at the camera", color = Forge.Dim, fontSize = 13.sp) },
    )
    Box(Modifier.fillMaxWidth()) {
        SmallButton(if (s.writing) (if (s.parts > 1 || s.cont != null) "PLANNING…" else "WRITING…") else "✦ WRITE IT FOR ME", true) { s.assist() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(s: LtxBuilderState) = Card("4. Settings") {
    Label("Length")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LTX_FRAMES.forEach { f -> Chip(LTX_LENGTH_LABEL[f] ?: "${f}f", s.frames == f) { s.frames = f } }
    }
    Label("Clips in a row")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("–", false) { if (s.parts > 1) s.parts-- }
        Text("${s.parts}", color = Forge.Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(28.dp))
        SmallButton("+", false) { if (s.parts < LTX_MAX_PARTS) s.parts++ }
        listOf(1, 2, 3, 5, 10, 20).forEach { n -> Chip("$n", s.parts == n) { s.parts = n } }
    }
    if (s.parts > 1 || s.cont != null) {
        Tip("Each clip starts on the last frame of the one before and they're joined into one video. Describe the whole story in rough words (it writes each part's prompt), one line per clip, or paste a director plan with exactly ${s.parts} CLIP blocks.")
    }
    if (s.cont == null) {
        Label("Shape")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LTX_SIZES.forEach { z -> Chip(z.replaceFirstChar { it.uppercase() }, s.size == z) { s.size = z } }
        }
    }
    Label("Seed (blank = random; reuse one to redo a take with a new length)")
    OutlinedTextField(
        value = s.seed, onValueChange = { v -> s.seed = v.filter { it.isDigit() }.take(18) },
        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp), colors = forgeFieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        placeholder = { Text("random", color = Forge.Dim, fontSize = 13.sp) },
    )
}

@Composable
private fun RenderButtons(s: LtxBuilderState) {
    val req = s.request()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BigButton(renderButtonLabel(req, s.sending), primary = true, enabled = !s.sending) { s.render() }
        BigButton("↺  New render", primary = false, enabled = !s.sending) { s.newRender() }
        s.message?.let { Text(it.text, color = if (it.error) Forge.Bad else Forge.Ok, fontSize = 13.sp) }
        Tip("Start at 2 seconds to find a good take, then redo that seed at 4–5 seconds. The first clip after the runtime starts takes a few extra minutes while the models load.")
    }
}

// ---------------------------------------------------------------- models

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelsCard(s: LtxBuilderState) {
    var open by remember { mutableStateOf(false) }
    val st = s.status
    val o = s.opts ?: BuilderOpts()
    Card("3. Models", summary(o, st)) {
        Chip(if (open) "Hide model settings ▴" else "Show model settings ▾", open) { open = !open }
        if (!open) return@Card
        if (st == null) { Tip("Waiting for the hub to list its models…"); return@Card }
        Label("Video model")
        Pick(o.transformer ?: st.transformers.firstOrNull().orEmpty(), st.transformers.map { it to nice(it) }) { s.opts = o.copy(transformer = it) }
        Label("Distilled 450 · %.2f  (1.0 on the speed schedule, 0 = full Dev, slower)".format(java.util.Locale.US, o.distill))
        Slider(
            value = o.distill.toFloat(), onValueChange = { s.opts = o.copy(distill = (Math.round(it * 20) / 20.0)) },
            valueRange = 0f..1.2f, enabled = st.distilled, colors = sliderColors(),
        )
        Label("Content LoRAs, in order: anatomy ~0.65, then motion ~0.45. Add camera or lighting only for that shot.")
        o.loras.forEachIndexed { i, (name, strength) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1.4f)) { Pick(name, st.loras.map { it to nice(it) }) { n -> s.opts = o.copy(loras = o.loras.toMutableList().also { it[i] = n to it[i].second }) } }
                Slider(
                    value = strength.toFloat(), onValueChange = { v -> s.opts = o.copy(loras = o.loras.toMutableList().also { it[i] = name to (Math.round(v * 20) / 20.0) }) },
                    valueRange = 0f..1.5f, colors = sliderColors(), modifier = Modifier.weight(1f),
                )
                Text("%.2f".format(java.util.Locale.US, strength), color = Forge.Fg, fontSize = 12.sp, modifier = Modifier.width(36.dp))
                SmallButton("✕", false) { s.opts = o.copy(loras = o.loras.toMutableList().also { it.removeAt(i) }) }
            }
            st.triggers[name]?.let { Text("adds “$it” to the prompt", color = Forge.Dim, fontSize = 11.sp) }
        }
        if (o.loras.isEmpty()) Tip(if (st.loras.isEmpty()) "No LoRAs on Colab yet." else "None: plain model.")
        val free = st.loras.filter { l -> o.loras.none { it.first == l } }
        if (o.loras.size < 4 && free.isNotEmpty()) {
            Box(Modifier.fillMaxWidth()) { SmallButton("+ ADD LORA", false) { s.opts = o.copy(loras = o.loras + (free.first() to startStrength(free.first()))) } }
        }
        Label("Decoder")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("quality" to "Quality", "fast" to "Fast").forEach { (k, label) ->
                if (st.decoders[k] != false) Chip(label, o.vae == k) { s.opts = o.copy(vae = k) }
            }
        }
        Label("Text encoder")
        Pick(o.clip ?: st.clips.firstOrNull().orEmpty(), st.clips.map { it to nice(it) }) { s.opts = o.copy(clip = it) }
        Label("Prompt writer: reads your picture and writes the prompts (if it refuses, Grok 4.5 takes over)")
        Pick(o.writer ?: "x-ai/grok-4.5", st.writers) { s.opts = o.copy(writer = it) }
        Box(Modifier.fillMaxWidth()) { SmallButton("RESET TO DEFAULTS", false) { s.opts = defaultOpts(st) } }

        Label("Install a LoRA or video model")
        Tip("Paste a CivitAI link (open the version you want first) or a Hugging Face .safetensors link. It downloads straight onto Colab and shows up in the lists above when done.")
        OutlinedTextField(
            value = s.installUrl, onValueChange = { s.installUrl = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp), colors = forgeFieldColors(),
            placeholder = { Text("https://civitai.com/models/…?modelVersionId=…", color = Forge.Dim, fontSize = 13.sp) },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("LoRA", s.installKind == "lora") { s.installKind = "lora" }
            Chip("Video model", s.installKind == "transformer") { s.installKind = "transformer" }
        }
        BigButton(if (s.installing) "Checking the link…" else "Download to Colab", primary = false, enabled = !s.installing) { s.install() }
        st.installs.forEach { it ->
            val c = when (it.state) { "downloading" -> Forge.Warn; "done" -> Forge.Ok; "failed" -> Forge.Bad; else -> Forge.Mut }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(nice(it.name), color = Forge.Fg, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    listOfNotNull(it.sizeMb?.let { mb -> "$mb MB" }, it.detail).joinToString(" · ").takeIf { t -> t.isNotEmpty() }?.let { t -> Text(t, color = Forge.Dim, fontSize = 11.sp, maxLines = 2) }
                }
                StatusPill(it.state, c)
            }
        }
    }
}

private fun summary(o: BuilderOpts, st: BuilderStatus?): String {
    val tr = o.transformer ?: st?.transformers?.firstOrNull().orEmpty()
    return "${nice(tr).take(26)} · ${o.loras.size} LoRA${if (o.loras.size == 1) "" else "s"} · ${o.vae}"
}

internal fun nice(file: String) = file.removeSuffix(".safetensors").removeSuffix(".comfy").replace('_', ' ')

// ---------------------------------------------------------------- clips

@Composable
private fun ClipsHeader(s: LtxBuilderState) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("CLIPS", color = Forge.Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
        Spacer(Modifier.width(10.dp))
        Text("${s.jobs.size}", color = Forge.Dim, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        s.jobsProblem?.let { Text("couldn't refresh: $it", color = Forge.Bad, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(3f)) }
        TextButton(onClick = { s.refreshJobs() }) { Text("REFRESH", color = Forge.Acc, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun EmptyClips(s: LtxBuilderState) {
    Text(
        if (!s.jobsLoaded) "Loading clips…" else s.jobsProblem?.let { "Couldn't load the clips: $it" } ?: "No clips yet.",
        color = if (s.jobsProblem != null) Forge.Bad else Forge.Mut, fontSize = 13.sp,
    )
}

@Composable
private fun JobCard(job: BuilderJob, s: LtxBuilderState, media: MediaClient, actions: ClipActions, thumbs: MutableMap<String, ImageBitmap>) {
    val shape = RoundedCornerShape(12.dp)
    val (pillText, pillColor) = when (job.status) {
        "queued" -> "queued" to Forge.Mut
        "rendering" -> "rendering" to Forge.Warn
        "done" -> "done" to Forge.Ok
        "failed" -> "failed" to Forge.Bad
        else -> job.status to Forge.Mut
    }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Forge.Panel).border(1.dp, Forge.Line, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Thumb(job, media, thumbs)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(job.shortName, color = Forge.Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(meta(job), color = Forge.Dim, fontSize = 11.sp)
                if (job.live && !job.step.isNullOrBlank()) Text(job.step, color = Forge.Mut, fontSize = 12.sp)
            }
            StatusPill(pillText, pillColor)
        }
        if (job.live && job.isChain && job.partsTotal > 0) {
            LinearProgressIndicator(
                progress = { job.partsDone.toFloat() / job.partsTotal }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(99.dp)),
                color = Forge.Acc2, trackColor = Forge.Panel2,
            )
        }
        if (job.status == "failed" && !job.error.isNullOrBlank()) Text(job.error, color = Forge.Bad, fontSize = 12.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
        FlowRowOf {
            if (job.done) {
                ClipButtons(actions, job.out.orEmpty())
                if (!job.isSharpen) SmallButton("CONTINUE ▸", false) { s.continueFrom(job) }
            }
            if (!job.isSharpen && (job.done || job.status == "failed")) SmallButton("REUSE", false) { s.reuse(job) }
            if (job.done && !job.isChain && !job.isSharpen && job.seed != null) SmallButton("SAME SEED, LONGER", false) { s.sameSeedLonger(job) }
            if (job.live) SmallButton("CANCEL", false) { s.cancel(job) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowOf(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }

private fun meta(j: BuilderJob): String = buildList {
    if (j.isSharpen) add("2×")
    if (j.textToVideo) add("text")
    if (j.isChain && j.partsTotal > 0) add("${j.partsTotal} ×")
    add(j.frames?.let { LTX_LENGTH_LABEL[it] ?: "${it}f" } ?: "")
    j.seed?.let { add("seed $it") }
}.filter { it.isNotEmpty() }.joinToString(" · ").replace("× ·", "×")

@Composable
private fun Thumb(job: BuilderJob, media: MediaClient, thumbs: MutableMap<String, ImageBitmap>) {
    val key = job.out.orEmpty()
    val bmp by produceState(thumbs[key], key, job.done) {
        if (!job.done || key.isEmpty()) return@produceState
        thumbs[key]?.let { value = it; return@produceState }
        (media.thumb(key) as? MediaResult.Ok)?.let { r -> runCatching { decodeToBitmap(r.value) }.getOrNull() }?.let { thumbs[key] = it; value = it }
    }
    Box(Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(Forge.Well), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

// ---------------------------------------------------------------- small parts

@Composable
internal fun Card(title: String, hint: String? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Forge.Panel).border(1.dp, Forge.Line, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Forge.Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (hint != null) { Spacer(Modifier.width(8.dp)); Text(hint, color = Forge.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        content()
    }
}

@Composable internal fun Tip(text: String, color: Color = Forge.Mut) = Text(text, color = color, fontSize = 12.sp, lineHeight = 18.sp)

@Composable internal fun Label(text: String) = Text(text, color = Forge.Dim, fontSize = 12.sp)

@Composable
internal fun BigButton(text: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxWidth().height(46.dp).clip(shape)
            .background(if (!enabled) Forge.Panel2 else if (primary) Forge.Acc2 else Forge.Panel)
            .border(1.dp, if (primary && enabled) Forge.Acc2 else Forge.Line, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (!enabled) Forge.Dim else if (primary) Color.White else Forge.Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
internal fun Pick(value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(Forge.Well).border(1.dp, Forge.Line, shape).clickable { open = true }.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.firstOrNull { it.first == value }?.second ?: nice(value).ifEmpty { "—" }, color = Forge.Fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("▾", color = Forge.Mut, fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Forge.Panel2) {
            options.forEach { (id, label) ->
                DropdownMenuItem(text = { Text(label, color = if (id == value) Forge.Acc3 else Forge.Fg, fontSize = 13.sp) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}

@Composable
internal fun sliderColors() = SliderDefaults.colors(
    thumbColor = Forge.Acc, activeTrackColor = Forge.Acc2, inactiveTrackColor = Forge.Panel2,
    disabledThumbColor = Forge.Dim, disabledActiveTrackColor = Forge.Panel2,
)

@Composable
internal fun forgeFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Forge.Well, unfocusedContainerColor = Forge.Well,
    focusedBorderColor = Forge.Acc, unfocusedBorderColor = Forge.Line,
    focusedTextColor = Forge.Fg, unfocusedTextColor = Forge.Fg, cursorColor = Forge.Acc,
)
