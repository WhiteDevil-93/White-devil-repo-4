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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whitedevil.desktop.ops.ActionController
import com.whitedevil.desktop.ops.ActionDialog
import com.whitedevil.desktop.ops.ActionOutcome
import com.whitedevil.desktop.ops.ActionSpec
import com.whitedevil.desktop.ops.OpsResult
import com.whitedevil.desktop.ops.OPS_POLL_MS
import com.whitedevil.desktop.ops.OpsState
import com.whitedevil.desktop.ops.PanelState
import com.whitedevil.desktop.ops.PanelView
import com.whitedevil.desktop.ops.PollWhileVisible
import com.whitedevil.desktop.ops.ThunderActions
import com.whitedevil.desktop.ops.ThunderApi
import com.whitedevil.desktop.ops.rememberOpsClients
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Build a Wan 2.2 14B render: describe the scene, the hub's prompt writer turns it into a chain of clips,
 * you read and edit them, and the chain goes to the 14B runner on Thunder. This is the web Prompt
 * generator (hub/static/generator.html) plus the Thunder screen's "send a chain", in one place, with the
 * 14B queue underneath. Sending asks for confirmation first because it uses billing GPU time.
 */
@Composable
fun WanBuilderScreen(settings: Settings) {
    val client = remember(settings.hubUrl, settings.relayUser, settings.relayPass) { WanGenClient(settings.hubUrl, settings.relayUser, settings.relayPass) }
    DisposableEffect(client) { onDispose { client.close() } }
    val scope = rememberCoroutineScope()
    val draftFile = remember { File(Settings.dir, "wan_draft.json") }
    val state = remember(client) { WanBuilderState(scope, client, draftFile, WanDraft.parse(runCatching { draftFile.readText() }.getOrNull())) }

    val clients = rememberOpsClients(settings)
    val api = remember(clients) { ThunderApi(clients.reader) }
    val actions = remember(clients) { ThunderActions(clients.actor) }
    val queue = remember(api) { PanelState(api::queue) }
    val controller = remember(queue) { ActionController(onFinished = { scope.launch { queue.refresh(followUp = true) } }) }
    PollWhileVisible(queue, OPS_POLL_MS)

    LaunchedEffect(state) { state.refreshKey() }
    val draft = state.draft()
    LaunchedEffect(draft) { delay(500); state.saveDraft() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 1000.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Form(state) }
                Box(Modifier.width(1.dp).fillMaxSize().background(Forge.Line))
                Column(Modifier.weight(1.15f).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Result(state, queue, controller, actions)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Form(state); Result(state, queue, controller, actions)
            }
        }
    }
    ActionDialog(controller)
}

// ---------------------------------------------------------------- form

@Composable
private fun Form(s: WanBuilderState) {
    Column {
        Text("Wan 2.2 14B · on Thunder", color = Forge.Dim, fontSize = 11.sp)
        Text("Build a chain", color = Forge.Fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
    SceneCard(s)
    ChainCard(s)
    LookCard(s)
    WriterCard(s)
    GenerateButtons(s)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SceneCard(s: WanBuilderState) = Card("1. The scene") {
    OutlinedTextField(
        value = s.idea, onValueChange = { s.idea = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
        shape = RoundedCornerShape(10.dp), colors = forgeFieldColors(),
        placeholder = { Text("A couple at a candlelit dinner, talking and smiling", color = Forge.Dim, fontSize = 13.sp) },
    )
    Label("First clip")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Image-to-video (start picture)", s.mode == "i2v") { s.mode = "i2v" }
        Chip("Text-to-video", s.mode == "t2v") { s.mode = "t2v" }
    }
    if (s.mode == "i2v") {
        OutlinedTextField(
            value = s.imageDescription, onValueChange = { s.imageDescription = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
            shape = RoundedCornerShape(10.dp), colors = forgeFieldColors(),
            label = { Text("What the start frame shows", color = Forge.Dim, fontSize = 12.sp) },
            placeholder = { Text("people, positions, clothing, lighting", color = Forge.Dim, fontSize = 13.sp) },
        )
        val bmp = s.pictureBitmap
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(shape).background(Forge.Well).border(1.5.dp, Forge.Line, shape).clickable { s.choosePicture() },
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) Image(bmp, s.picture?.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp))
            else Text("Click to choose the start picture (sent to the runner)", color = Forge.Mut, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
        }
        if (s.picture != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.picture?.name.orEmpty(), color = Forge.Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                SmallButton("✕ REMOVE", false) { s.clearPicture() }
            }
        } else {
            Tip("No picture chosen: the runner will look for start.jpg that is already on its machine.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainCard(s: WanBuilderState) = Card("2. The chain") {
    Label("Number of clips (2–$WAN_MAX_CLIPS)")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("–", false) { if (s.clipsCount > 2) s.clipsCount-- }
        Text("${s.clipsCount}", color = Forge.Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(32.dp))
        SmallButton("+", false) { if (s.clipsCount < WAN_MAX_CLIPS) s.clipsCount++ }
        listOf(4, 8, 12, 20, 30).forEach { n -> Chip("$n", s.clipsCount == n) { s.clipsCount = n } }
    }
    Label("Clip duration")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WAN_DURATIONS.forEach { (f, label) -> Chip(label, s.frames == f) { s.frames = f } }
    }
    Label("Linking")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Continuous (last frame → next)", s.link == "continuous") { s.link = "continuous" }
        Chip("Hard cuts", s.link == "cut") { s.link = "cut" }
    }
    Label("Shape")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Landscape 1280×720", s.orient == "landscape") { s.orient = "landscape" }
        Chip("Portrait 720×1280", s.orient == "portrait") { s.orient = "portrait" }
    }
    OutlinedTextField(
        value = s.beats, onValueChange = { s.beats = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
        shape = RoundedCornerShape(10.dp), colors = forgeFieldColors(),
        label = { Text("Story beats, one per line (optional)", color = Forge.Dim, fontSize = 12.sp) },
        placeholder = { Text("They sit down\nHe tells a joke, she laughs\nThey raise glasses", color = Forge.Dim, fontSize = 13.sp) },
    )
    val b = s.brief()
    val secs = stitchedSeconds(b.frames, WAN_14B.fps, b.clipCount, b.link)
    val batches = -(-b.clipCount / WAN_CHUNK)
    Tip("≈ ${formatSeconds(secs)} after stitching." + if (batches > 1) " Written in $batches requests of up to $WAN_CHUNK clips." else "")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookCard(s: WanBuilderState) = Card("3. The look") {
    Label("Exact number of people")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("–", false) { if (s.cast > 0) s.cast-- }
        Text("${s.cast}", color = Forge.Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(32.dp))
        SmallButton("+", false) { if (s.cast < 10) s.cast++ }
    }
    Label("Camera")
    Pick(s.camera, WAN_CAMERAS) { s.camera = it }
    Label("Framing")
    Pick(s.framing, WAN_FRAMINGS.map { it to it }) { s.framing = it }
    Label("Motion")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WAN_MOTIONS.forEach { (v, label) -> Chip(label, s.motion == v) { s.motion = v } }
    }
    OutlinedTextField(
        value = s.forbid, onValueChange = { s.forbid = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
        shape = RoundedCornerShape(10.dp), colors = forgeFieldColors(), label = { Text("Must not happen", color = Forge.Dim, fontSize = 12.sp) },
        placeholder = { Text("nobody enters frame, they don't stand up", color = Forge.Dim, fontSize = 13.sp) },
    )
    OutlinedTextField(
        value = s.style, onValueChange = { s.style = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        shape = RoundedCornerShape(10.dp), colors = forgeFieldColors(), label = { Text("Style / mood", color = Forge.Dim, fontSize = 12.sp) },
        placeholder = { Text("photoreal, soft even diffuse light", color = Forge.Dim, fontSize = 13.sp) },
    )
    Label("Target length of each prompt")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { WAN_WORDS.forEach { (v, label) -> Chip(label, s.words == v) { s.words = v } } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Switch(
            checked = s.appendNegative, onCheckedChange = { s.appendNegative = it },
            colors = SwitchDefaults.colors(checkedTrackColor = Forge.Acc2, checkedThumbColor = androidx.compose.ui.graphics.Color.White, uncheckedTrackColor = Forge.Panel2, uncheckedThumbColor = Forge.Mut, uncheckedBorderColor = Forge.Line),
        )
        Text("Append WAN's official default negative prompt", color = Forge.Mut, fontSize = 13.sp)
    }
}

@Composable
private fun WriterCard(s: WanBuilderState) {
    var open by remember { mutableStateOf(false) }
    Card("Prompt writer (OpenRouter)", if (s.keyConfigured == true) "key saved on the hub" else if (s.keyConfigured == false) "no key yet" else "") {
        Chip(if (open) "Hide ▴" else "Show ▾", open) { open = !open }
        if (!open && s.keyConfigured != false) return@Card
        if (s.keyConfigured != true) Tip("The hub writes the clips through OpenRouter, so it needs your key once. It is stored on the hub only (~/.openrouter_key), never in this app.", Forge.Mut)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = s.keyInput, onValueChange = { s.keyInput = it }, modifier = Modifier.weight(1f), singleLine = true,
                shape = RoundedCornerShape(10.dp), colors = forgeFieldColors(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                label = { Text(if (s.keyConfigured == true) "Replace the key (optional)" else "OpenRouter API key", color = Forge.Dim, fontSize = 12.sp) },
                placeholder = { Text("sk-or-v1-…", color = Forge.Dim, fontSize = 13.sp) },
            )
            SmallButton("SAVE", true) { s.saveKey() }
        }
        OutlinedTextField(
            value = s.llmModel, onValueChange = { s.llmModel = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(10.dp), colors = forgeFieldColors(), label = { Text("Model", color = Forge.Dim, fontSize = 12.sp) },
        )
        Tip("Drop :free to use the paid endpoint.")
        OutlinedTextField(
            value = s.temperature, onValueChange = { s.temperature = it.filter { c -> c.isDigit() || c == '.' }.take(4) },
            modifier = Modifier.width(160.dp), singleLine = true, shape = RoundedCornerShape(10.dp), colors = forgeFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), label = { Text("Temperature", color = Forge.Dim, fontSize = 12.sp) },
        )
    }
}

@Composable
private fun GenerateButtons(s: WanBuilderState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BigButton(if (s.generating) "Generating…" else "Generate chain", primary = true, enabled = !s.generating) { s.generate() }
        if (s.canResume) BigButton("Resume the chain", primary = false, enabled = !s.generating) { s.resume() }
        s.progress?.let { Text(it, color = Forge.Mut, fontSize = 13.sp) }
        s.problem?.let { Text(it, color = Forge.Bad, fontSize = 13.sp) }
    }
}

// ---------------------------------------------------------------- result + send + queue

@Composable
private fun Result(s: WanBuilderState, queue: PanelState<com.whitedevil.desktop.ops.ThunderQueue>, controller: ActionController, actions: ThunderActions) {
    ReviewCard(s)
    SendCard(s, queue, controller, actions)
    Card("14B render queue", "refreshes every ${OPS_POLL_MS / 1000}s") {
        PanelView(queue, "the 14B render queue", onRetry = { }) { q, stale -> QueueCard(q, stale, controller, actions) }
    }
}

@Composable
private fun ReviewCard(s: WanBuilderState) {
    val c = s.chain
    val loaded = s.loaded
    Card("Your chain", if (loaded != null) "${loaded.spec.clipCount} clips from file" else if (c != null) "${s.clips.size} clips" else "") {
        when {
            loaded != null -> {
                Tip("${loaded.spec.clipCount} clips from ${loaded.spec.sourceFiles} file${if (loaded.spec.sourceFiles > 1) "s" else ""}. Chain files are sent exactly as they are; to change prompts, edit the file or generate a new chain.")
                Box(Modifier.fillMaxWidth()) { SmallButton("DISCARD", false) { s.discardChain() } }
            }
            c == null -> Tip("Fill in the scene and press Generate chain. The clips appear here as soon as the hub writes them, and you can edit any prompt before sending.")
            else -> {
                if (c.characters.isNotBlank() || c.setting.isNotBlank()) {
                    Label("Continuity (the same in every clip)")
                    if (c.characters.isNotBlank()) Tip("Characters: ${c.characters}")
                    if (c.setting.isNotBlank()) Tip("Setting: ${c.setting}")
                    if (c.style.isNotBlank()) Tip("Style: ${c.style}")
                }
                s.clips.forEachIndexed { i, clip -> ClipEditor(i, clip, s) }
                Box(Modifier.fillMaxWidth()) { SmallButton("DISCARD THIS CHAIN", false) { s.discardChain() } }
            }
        }
    }
}

@Composable
private fun ClipEditor(i: Int, clip: WanClip, s: WanBuilderState) {
    var open by remember(i) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Forge.Well).border(1.dp, Forge.Line, shape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}.", color = Forge.Acc3, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(30.dp))
            Text(clip.title.ifBlank { "Clip ${i + 1}" }, color = Forge.Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${wordCount(clip.prompt)} words", color = Forge.Dim, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
            SmallButton(if (open) "LESS" else "EDIT", false) { open = !open }
        }
        if (!open) {
            Text(clip.prompt, color = Forge.Mut, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        } else {
            EditField("Title", clip.title, 1) { v -> s.edit(i) { it.copy(title = v) } }
            EditField("Positive prompt", clip.prompt, 6) { v -> s.edit(i) { it.copy(prompt = v) } }
            EditField("Negative (the default is appended on send when ticked)", clip.negative, 2) { v -> s.edit(i) { it.copy(negative = v) } }
            EditField("Start state", clip.startState, 3) { v -> s.edit(i) { it.copy(startState = v) } }
            EditField("End state", clip.endState, 3) { v -> s.edit(i) { it.copy(endState = v) } }
            if (s.clips.size > 1) Box(Modifier.fillMaxWidth()) { SmallButton("REMOVE THIS CLIP", false) { s.removeClip(i) } }
        }
    }
}

@Composable
private fun EditField(label: String, value: String, lines: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), minLines = lines.coerceAtMost(2), maxLines = lines + 4,
        shape = RoundedCornerShape(8.dp), colors = forgeFieldColors(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Forge.Fg),
        label = { Text(label, color = Forge.Dim, fontSize = 11.sp) },
    )
}

@Composable
private fun SendCard(s: WanBuilderState, queue: PanelState<com.whitedevil.desktop.ops.ThunderQueue>, controller: ActionController, actions: ThunderActions) {
    val scope = rememberCoroutineScope()
    Card("Send to the 14B runner") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallButton("OPEN CHAIN FILE(S)…", false) { s.chooseChainFiles() }
            Text("to send a chain you already have", color = Forge.Dim, fontSize = 12.sp)
        }
        OutlinedTextField(
            value = s.name, onValueChange = { s.name = it.take(80) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(10.dp), colors = forgeFieldColors(), label = { Text("Name", color = Forge.Dim, fontSize = 12.sp) },
            placeholder = { Text(s.suggestedName(), color = Forge.Dim, fontSize = 13.sp) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = s.seed, onValueChange = { s.seed = it.filter { c -> c.isDigit() }.take(9) }, modifier = Modifier.width(200.dp), singleLine = true,
                shape = RoundedCornerShape(10.dp), colors = forgeFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text("Seed (optional)", color = Forge.Dim, fontSize = 12.sp) },
            )
            Switch(
                checked = s.nextUp, onCheckedChange = { s.nextUp = it },
                colors = SwitchDefaults.colors(checkedTrackColor = Forge.Acc2, checkedThumbColor = androidx.compose.ui.graphics.Color.White, uncheckedTrackColor = Forge.Panel2, uncheckedThumbColor = Forge.Mut, uncheckedBorderColor = Forge.Line),
            )
            Text("Next up", color = Forge.Mut, fontSize = 13.sp)
        }
        Tip("Next up jumps ahead of queued jobs: the hub cancels them, submits this, then tries to put them back.")
        val runnerUp = (queue.state as? OpsState.Loaded)?.value?.runnerUp
        if (runnerUp == false) Tip("The queue says the 14B runner isn't reachable right now, so a send will most likely fail.", Forge.Warn)
        BigButton(if (s.canSend) "Send ${s.clipTotal} clips to the 14B runner…" else "Nothing to send yet", primary = true, enabled = s.canSend && !controller.busy) {
            val seedValue = s.seed.trim().toIntOrNull()
            val jobName = s.suggestedName()
            val position = s.nextUp
            scope.launch {
                when (val built = s.buildChain()) {
                    is OpsResult.Err -> s.problem = built.error.message
                    is OpsResult.Ok -> {
                        val spec = built.value
                        controller.request(
                            ActionSpec(
                                title = "Submit chain to the 14B runner",
                                consequences = listOfNotNull(
                                    "Sends ${spec.clipCount} clips as \"$jobName\" to the Wan 14B runner on the Thunder instance.",
                                    "Rendering uses GPU time on your billing Thunder instance, hour by hour, until the chain finishes.",
                                    if (position) "Queue position NEXT UP: the hub first CANCELS every queued job ahead of it, submits yours, then tries to put them back. If that fails they stay cancelled."
                                    else "Queue position: end of the queue.",
                                    if (runnerUp != true) "The queue panel does not report the 14B runner as reachable, so this will most likely fail." else null,
                                    "The hub's reply is shown as it came. HTTP 200 counts as success only when the reply says ok=true.",
                                ),
                                confirmLabel = "Submit chain",
                                typedPhrase = if (position) "SUBMIT" else null,
                                run = {
                                    val outcome = actions.submit(spec, jobName, seedValue, position)
                                    if (outcome is ActionOutcome.Succeeded) { s.discardChain(); s.seed = ""; s.nextUp = false }
                                    outcome
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}
