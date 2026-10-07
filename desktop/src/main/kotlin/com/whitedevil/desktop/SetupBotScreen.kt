package com.whitedevil.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whitedevil.desktop.ops.ActionController
import com.whitedevil.desktop.ops.ActionDialog
import com.whitedevil.desktop.ops.ActionOutcome
import com.whitedevil.desktop.ops.ActionSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

/** The Setup bot's state and actions. The rules (what a plan is) live in SetupBotModel.kt where they are tested. */
@Stable
class SetupBotState(private val scope: CoroutineScope, val client: SetupBotClient) {
    var catalog by mutableStateOf<SetupCatalog?>(null)
        private set
    var catalogProblem by mutableStateOf<String?>(null)
        private set
    var loadingCatalog by mutableStateOf(false)
        private set

    // the form (the same controls as the web page)
    var whereKey by mutableStateOf("")
    var gpuIndex by mutableIntStateOf(0)
    var cpu by mutableStateOf<Int?>(null)
    var diskGb by mutableStateOf("0")
    var offerId by mutableStateOf<String?>(null)
    var vastDiskGb by mutableStateOf("0")
    var recipeId by mutableStateOf("")
    var options by mutableStateOf<Map<String, Any>>(emptyMap())
    var useForRenders by mutableStateOf(false)

    // the sentence box
    val chat = mutableStateListOf<Pair<String, String>>()
    var say by mutableStateOf("")
    var asking by mutableStateOf(false)
        private set

    // the plan under review
    var preview by mutableStateOf<PlanPreview?>(null)
        private set
    var previewing by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var secretInput by mutableStateOf("")

    // runs
    var runs by mutableStateOf<List<SetupRun>>(emptyList())
        private set
    var runsLoaded by mutableStateOf(false)
        private set
    var runsProblem by mutableStateOf<String?>(null)
        private set

    val recipe: SetupRecipe? get() = catalog?.recipes?.firstOrNull { it.id == recipeId }

    fun loadCatalog() {
        if (loadingCatalog) return
        loadingCatalog = true
        scope.launch {
            when (val r = client.catalog()) {
                is MediaResult.Ok -> {
                    val c = r.value
                    catalog = c; catalogProblem = null
                    if (whereKey.isEmpty() || whereChoices(c).none { it.key == whereKey }) whereKey = whereChoices(c).first().key
                    // the web page starts on a single L40 when there is one
                    c.thunderNew.indexOfFirst { it.gpuType == "l40" && it.numGpus == 1 && it.available }.takeIf { it >= 0 }?.let { gpuIndex = it }
                    if (recipe == null) c.recipes.firstOrNull()?.let { selectRecipe(it.id) }
                    if (offerId == null || c.vastOffers.none { it.id == offerId }) offerId = c.vastOffers.firstOrNull()?.id
                    cpu = c.thunderNew.getOrNull(gpuIndex)?.let { defaultCpu(it) }
                }
                is MediaResult.Failure -> catalogProblem = r.error.message
            }
            loadingCatalog = false
        }
    }

    private fun defaultCpu(g: ThunderNewSpec) = if (8 in g.cpuOptions) 8 else g.cpuOptions.firstOrNull() ?: 8

    fun selectGpu(i: Int) { gpuIndex = i; cpu = catalog?.thunderNew?.getOrNull(i)?.let(::defaultCpu) }

    fun selectRecipe(id: String) {
        recipeId = id
        options = catalog?.recipes?.firstOrNull { it.id == id }?.defaults().orEmpty()
    }

    fun setOption(key: String, value: Any) { options = options + (key to value) }

    private fun formPlan(): kotlinx.serialization.json.JsonObject? {
        val c = catalog ?: return null
        val target = targetFromForm(whereKey, c, gpuIndex, cpu, diskGb.toIntOrNull() ?: 0, offerId, vastDiskGb.toIntOrNull() ?: 0) ?: return null
        return buildPlan(recipeId, target, options, useForRenders && canMoveRenders(whereKey))
    }

    /** "Review plan": the hub checks the machine and says what would happen, what blocks it, and what it costs. */
    fun review() {
        if (previewing) return
        val plan = formPlan() ?: run { problem = "The setup catalog hasn't loaded yet, or nothing is chosen. Press Refresh."; return }
        previewing = true; problem = null; notice = null
        scope.launch {
            when (val r = client.preview(plan)) {
                is MediaResult.Ok -> preview = r.value
                is MediaResult.Failure -> { preview = null; problem = r.error.message }
            }
            previewing = false
        }
    }

    /** "Plan it": a sentence in, a previewed plan (and a short reply) out. */
    fun ask() {
        val text = say.trim()
        if (text.isEmpty() || asking) return
        chat += "user" to text; say = ""; asking = true; problem = null
        val history = chat.dropLast(1).toList()
        scope.launch {
            when (val r = client.chat(text, history)) {
                is MediaResult.Ok -> {
                    if (r.value.reply.isNotBlank()) chat += "assistant" to r.value.reply
                    r.value.preview?.let { preview = it }
                }
                is MediaResult.Failure -> chat += "assistant" to r.error.message
            }
            asking = false
        }
    }

    fun discardPlan() { preview = null }

    /** "Change it": put the plan's choices back into the form (the web page's toForm()). */
    fun changeIt() {
        val p = preview ?: return
        val c = catalog ?: return
        val plan = p.plan
        whereKeyFor(plan)?.let { whereKey = it }
        val t = plan["target"] as? kotlinx.serialization.json.JsonObject
        fun s(k: String) = (t?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
        when (p.targetKind) {
            "vast_new" -> { s("offer_id")?.let { offerId = it }; s("disk_gb")?.let { vastDiskGb = it } }
            "thunder_new" -> {
                c.thunderNew.indexOfFirst { it.gpuType == s("gpu_type") && it.numGpus.toString() == s("num_gpus") }.takeIf { it >= 0 }?.let { gpuIndex = it }
                s("cpu_cores")?.toIntOrNull()?.let { cpu = it }; s("disk_gb")?.let { diskGb = it }
            }
        }
        (plan["recipe"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { id -> if (c.recipes.any { it.id == id }) recipeId = id }
        val defaults = recipe?.defaults().orEmpty()
        options = defaults + p.options.mapNotNull { (k, v) -> defaults[k]?.let { d -> k to (if (d is Boolean) (v == "yes") else v) } }.toMap()
        useForRenders = p.useForRenders
        preview = null
    }

    fun saveSecret(name: String) {
        val v = secretInput.trim()
        if (v.isEmpty()) return
        scope.launch {
            when (val r = client.saveSecret(name, v)) {
                is MediaResult.Ok -> {
                    notice = secretOutcomeMessage(r.value); secretInput = ""
                    preview?.plan?.let { plan -> (client.preview(plan) as? MediaResult.Ok)?.let { preview = it.value } }
                }
                is MediaResult.Failure -> problem = r.error.message
            }
        }
    }

    fun refreshRuns() {
        scope.launch {
            when (val r = client.runs()) {
                is MediaResult.Ok -> { runs = r.value; runsProblem = null; runsLoaded = true }
                is MediaResult.Failure -> { runsProblem = r.error.message; runsLoaded = true }
            }
        }
    }
}

@Composable
fun SetupBotScreen(settings: Settings) {
    val client = remember(settings.hubUrl, settings.relayUser, settings.relayPass) { SetupBotClient(settings.hubUrl, settings.relayUser, settings.relayPass) }
    DisposableEffect(client) { onDispose { client.close() } }
    val scope = rememberCoroutineScope()
    val state = remember(client) { SetupBotState(scope, client) }
    val controller = remember(state) { ActionController(onFinished = { state.refreshRuns() }) }

    LaunchedEffect(state) { state.loadCatalog() }
    // Runs: every 8 s while one is going, every minute otherwise.
    LaunchedEffect(state) { while (true) { state.refreshRuns(); delay(if (state.runs.any { it.live }) 8_000 else 60_000) } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 1000.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Left(state) }
                Box(Modifier.width(1.dp).fillMaxSize().background(Forge.Line))
                Column(Modifier.weight(1.1f).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Right(state, settings, controller) }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Left(state); Right(state, settings, controller)
            }
        }
    }
    ActionDialog(controller)
}

// ---------------------------------------------------------------- left: ask, or pick it yourself

@Composable
private fun Left(s: SetupBotState) {
    Column {
        Text("Install a setup on a GPU", color = Forge.Dim, fontSize = 11.sp)
        Text("Setup", color = Forge.Fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
    s.catalogProblem?.let { p ->
        Card("The setup catalog didn't load") {
            Text(p, color = Forge.Bad, fontSize = 13.sp)
            SmallButton("TRY AGAIN", true) { s.loadCatalog() }
        }
    }
    s.catalog?.thunderError?.let { Tip("Thunder: $it", Forge.Warn) }
    AskCard(s)
    FormCard(s)
}

@Composable
private fun AskCard(s: SetupBotState) = Card("Tell it what you want") {
    Tip("For example “put the 14B remix on a new L40, image-to-video only”, “cheapest 48 GB card on vast” or “add the missing models to my current instance, keep comfy”.")
    s.chat.forEach { (role, text) ->
        val me = role == "user"
        Box(Modifier.fillMaxWidth(), contentAlignment = if (me) Alignment.CenterEnd else Alignment.CenterStart) {
            Text(
                text, color = Forge.Fg, fontSize = 13.sp,
                modifier = Modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(12.dp)).background(if (me) Forge.AccSoft else Forge.Well)
                    .border(1.dp, if (me) Forge.Acc2 else Forge.Line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
    OutlinedTextField(
        value = s.say, onValueChange = { s.say = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
        shape = RoundedCornerShape(10.dp), colors = forgeFieldColors(), placeholder = { Text("What should it set up, and where?", color = Forge.Dim, fontSize = 13.sp) },
    )
    BigButton(if (s.asking) "Thinking…" else "Plan it", primary = true, enabled = !s.asking && s.say.isNotBlank()) { s.ask() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormCard(s: SetupBotState) = Card("Or pick it yourself") {
    val c = s.catalog
    if (c == null) { Tip(if (s.loadingCatalog) "Loading the machines and recipes…" else "Waiting for the catalog."); return@Card }
    val choices = whereChoices(c)
    Label("Where")
    Pick(s.whereKey, choices.map { it.key to (if (it.enabled) it.label else it.label + " — none available") }) { k -> if (choices.firstOrNull { it.key == k }?.enabled != false) s.whereKey = k }

    if (s.whereKey == "new") {
        Label("GPU")
        Pick(s.gpuIndex.toString(), c.thunderNew.mapIndexed { i, g -> i.toString() to "${g.name} x${g.numGpus} · ${g.vramGb ?: "?"} GB · ${money(g.price)}${if (g.available) "" else " · sold out"}" }) { v -> v.toIntOrNull()?.let(s::selectGpu) }
        c.thunderNew.getOrNull(s.gpuIndex)?.let { g ->
            Label("vCPUs")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { g.cpuOptions.ifEmpty { listOf(8) }.forEach { n -> Chip("$n", s.cpu == n) { s.cpu = n } } }
        }
        NumberField("Disk (GB, 0 = sized for you)", s.diskGb) { s.diskGb = it }
    }
    if (s.whereKey == "vastnew") {
        Label("Vast machine (cheapest first)")
        Pick(s.offerId.orEmpty(), c.vastOffers.map { it.id to "${it.gpu} · ${it.vramGb ?: "?"} GB · ${money(it.price)} · ${it.location.orEmpty()} · ${it.reliabilityLabel}%" }) { s.offerId = it }
        NumberField("Disk (GB, 0 = sized for you)", s.vastDiskGb) { s.vastDiskGb = it }
    }
    c.vastError?.let { Tip(it, Forge.Warn) }

    Label("Setup")
    Pick(s.recipeId, c.recipes.map { it.id to it.title }) { s.selectRecipe(it) }
    s.recipe?.let { r ->
        Tip(r.summary)
        r.options.forEach { o ->
            if (o.isBool) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Switch(checked = s.options[o.key] as? Boolean ?: false, onCheckedChange = { s.setOption(o.key, it) }, colors = switchColors())
                    Text(o.label, color = Forge.Mut, fontSize = 13.sp)
                }
            } else {
                Label(o.label)
                Pick(s.options[o.key]?.toString().orEmpty(), o.choices.map { it.value to it.label }) { s.setOption(o.key, it) }
            }
        }
    }
    if (canMoveRenders(s.whereKey)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(checked = s.useForRenders, onCheckedChange = { s.useForRenders = it }, colors = switchColors())
            Text("Move the 14B renders to this machine when it's done", color = Forge.Mut, fontSize = 13.sp)
        }
    }
    BigButton(if (s.previewing) "Checking the machine…" else "Review plan", primary = true, enabled = !s.previewing) { s.review() }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.filter { c -> c.isDigit() }.take(5)) }, modifier = Modifier.width(260.dp), singleLine = true,
        shape = RoundedCornerShape(10.dp), colors = forgeFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        label = { Text(label, color = Forge.Dim, fontSize = 12.sp) },
    )
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedTrackColor = Forge.Acc2, checkedThumbColor = Color.White, uncheckedTrackColor = Forge.Panel2, uncheckedThumbColor = Forge.Mut, uncheckedBorderColor = Forge.Line,
)

// ---------------------------------------------------------------- right: the plan and the runs

@Composable
private fun Right(s: SetupBotState, settings: Settings, controller: ActionController) {
    s.problem?.let { Text(it, color = Forge.Bad, fontSize = 13.sp) }
    s.notice?.let { Text(it, color = Forge.Ok, fontSize = 13.sp) }
    s.preview?.let { PlanCard(it, s, controller) }
    RunsCard(s, settings, controller)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanCard(p: PreviewHolder, s: SetupBotState, controller: ActionController) {
    Card("Plan") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.recipe, color = Forge.Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(p.targetLabel, color = Forge.Mut, fontSize = 12.sp)
            }
            StatusPill(if (p.ok) "Ready" else "Blocked", if (p.ok) Forge.Ok else Forge.Bad)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Fact(money(p.price), if (p.price == null) "Colab credits" else "while it exists", Modifier.weight(1f))
            Fact(p.diskNeedGb?.let { "$it GB" } ?: "—", p.freeGb?.let { "$it GB free" } ?: "disk needed", Modifier.weight(1f))
            Fact(p.minutes?.let { "~$it min" } ?: "—", p.vramGb?.let { "$it GB VRAM" } ?: "install", Modifier.weight(1f))
        }
        Text(p.options.entries.joinToString(" · ") { "${it.key}=${it.value}" } + if (p.useForRenders) " · 14B renders move here" else "", color = Forge.Dim, fontSize = 11.sp)
        p.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", color = Forge.Mut, fontSize = 12.sp) }
        p.blocking.forEach { Tip(it, Forge.Bad) }
        p.needsSecret?.let { name ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = s.secretInput, onValueChange = { s.secretInput = it }, modifier = Modifier.weight(1f), singleLine = true,
                    shape = RoundedCornerShape(10.dp), colors = forgeFieldColors(), visualTransformation = PasswordVisualTransformation(),
                    label = { Text(if (name == "HF_TOKEN") "Hugging Face token" else name, color = Forge.Dim, fontSize = 12.sp) },
                    placeholder = { Text(if (name == "HF_TOKEN") "hf_…" else "", color = Forge.Dim, fontSize = 13.sp) },
                )
                SmallButton("SAVE", true) { s.saveSecret(name) }
            }
            Tip("The token is saved on the hub only, never in this app.")
        }
        p.warnings.forEach { Tip(it, Forge.Warn) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButtonWide("Confirm and run…", enabled = p.ok && !controller.busy) {
                controller.request(
                    ActionSpec(
                        title = "Install ${p.recipe}",
                        consequences = runConsequences(p),
                        confirmLabel = "Install",
                        typedPhrase = if (p.targetKind == "thunder_new" || p.targetKind == "vast_new") "INSTALL" else null,
                        danger = true,
                        run = {
                            when (val r = s.client.startRun(p.plan)) {
                                is MediaResult.Ok -> { s.discardPlan(); ActionOutcome.Succeeded("Started run ${r.value}. It shows under Runs below.") }
                                is MediaResult.Failure -> ActionOutcome.Failed(r.error.message, status = r.error.status)
                            }
                        },
                    ),
                )
            }
            SmallButton("CHANGE IT", false) { s.changeIt() }
            SmallButton("CANCEL", false) { s.discardPlan() }
        }
    }
}

private typealias PreviewHolder = PlanPreview

@Composable
private fun BigButtonWide(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.width(240.dp)) { BigButton(text, primary = true, enabled = enabled, onClick = onClick) }
}

@Composable
private fun Fact(value: String, caption: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Forge.Well).border(1.dp, Forge.Line, RoundedCornerShape(8.dp)).padding(10.dp)) {
        Text(value, color = Forge.Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(caption, color = Forge.Dim, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunsCard(s: SetupBotState, settings: Settings, controller: ActionController) {
    Card("Runs", if (s.runs.any { it.live }) "${s.runs.count { it.live }} running" else "idle") {
        s.runsProblem?.let { Tip("Couldn't refresh: $it", Forge.Bad) }
        if (s.runs.isEmpty()) Tip(if (!s.runsLoaded) "Loading…" else "Nothing set up yet.")
        s.runs.forEach { r -> RunRow(r, s, settings, controller) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunRow(r: SetupRun, s: SetupBotState, settings: Settings, controller: ActionController) {
    var logOpen by remember(r.id) { mutableStateOf(r.live) }
    val shape = RoundedCornerShape(10.dp)
    val (pillColor) = listOf(when (r.status) { "done" -> Forge.Ok; "failed" -> Forge.Bad; "cancelled" -> Forge.Mut; else -> Forge.Warn })
    Column(Modifier.fillMaxWidth().clip(shape).background(Forge.Well).border(1.dp, Forge.Line, shape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.recipe.ifBlank { "Setup" }, color = Forge.Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(r.targetLabel, color = Forge.Dim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            StatusPill(r.statusWord, pillColor)
        }
        if (r.live) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forge.Acc2, trackColor = Forge.Panel2)
        if (r.step.isNotBlank()) Text(r.step, color = Forge.Mut, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.logTail.isNotBlank()) SmallButton(if (logOpen) "HIDE LOG" else "SHOW LOG", false) { logOpen = !logOpen }
            if (r.live) SmallButton("STOP", false) {
                controller.request(
                    ActionSpec(
                        title = "Stop this setup",
                        consequences = listOf("Stops \"${r.recipe}\" part-way on ${r.targetLabel}.", "Whatever was already installed stays there; a machine it created keeps billing until you delete it."),
                        confirmLabel = "Stop setup",
                        run = {
                            when (val x = s.client.cancelRun(r.id)) {
                                is MediaResult.Ok -> ActionOutcome.Succeeded("Stopping.")
                                is MediaResult.Failure -> ActionOutcome.Failed(x.error.message, status = x.error.status)
                            }
                        },
                    ),
                )
            }
            if (r.status == "done" && r.targetKind == "colab") SmallButton("OPEN COMFYUI", true) {
                runCatching { Desktop.getDesktop().browse(URI("https://comfy." + (URI(settings.hubUrl.trim()).host ?: "") + "/")) }
            }
        }
        if (logOpen && r.logTail.isNotBlank()) {
            Text(
                r.logTail, color = Forge.Mut, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(6.dp)).background(Forge.Bg).verticalScroll(rememberScrollState()).padding(8.dp),
            )
        }
    }
}
