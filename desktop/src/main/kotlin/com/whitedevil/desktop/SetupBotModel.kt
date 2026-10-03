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
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * The Setup bot, as plain code. It installs a recipe (Wan 2.2 Remix 14B, LTX 2.5) on a GPU you pick:
 * a Thunder instance (existing or new), a Vast machine (existing or new) or Colab. Mirrors
 * hub/static/setup/index.html and hub/setupbot.py: GET /catalog, POST /preview, POST /chat, POST /runs.
 */

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
private fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.doubleOrNull
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray).orEmpty()
private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

fun money(v: Double?): String = if (v == null) "—" else String.format(Locale.US, "$%.2f/h", v)

// ---------------------------------------------------------------- catalog

data class RecipeChoice(val value: String, val label: String)

data class RecipeOption(val key: String, val label: String, val isBool: Boolean, val boolDefault: Boolean, val choices: List<RecipeChoice>, val choiceDefault: String?)

data class SetupRecipe(val id: String, val title: String, val summary: String, val minVramGb: Int?, val options: List<RecipeOption>) {
    /** Starting values for the option controls, as the web form sets them. */
    fun defaults(): Map<String, Any> = options.associate { o -> o.key to (if (o.isBool) o.boolDefault else (o.choiceDefault ?: o.choices.firstOrNull()?.value.orEmpty())) }
}

data class ThunderExisting(val id: String, val name: String?, val gpu: String, val numGpus: Int, val vramGb: Int?, val status: String?, val price: Double?, val rendersHere: Boolean)
data class ThunderNewSpec(val gpuType: String, val numGpus: Int, val name: String, val vramGb: Int?, val price: Double?, val cpuOptions: List<Int>, val available: Boolean)
data class VastExisting(val id: String, val gpu: String, val numGpus: Int, val status: String?, val location: String?, val price: Double?)
data class VastOffer(val id: String, val gpu: String, val vramGb: Int?, val price: Double?, val location: String?, val reliability: Double?) {
    /** 97.5 stays 97.5, 98.0 reads 98: the page prints the number as it is. */
    val reliabilityLabel: String get() = reliability?.let { if (it == Math.floor(it)) it.toInt().toString() else it.toString() } ?: "?"
}

data class SetupCatalog(
    val recipes: List<SetupRecipe>,
    val thunderExisting: List<ThunderExisting>,
    val thunderNew: List<ThunderNewSpec>,
    val vastExisting: List<VastExisting>,
    val vastOffers: List<VastOffer>,
    val vastError: String?,
    val colabLabel: String,
    val thunderError: String?,
)

fun parseSetupCatalog(json: JsonElement): SetupCatalog? {
    val o = json.obj() ?: return null
    val recipes = o["recipes"].arr().mapNotNull { r ->
        val ro = r.obj() ?: return@mapNotNull null
        val id = ro["id"].str() ?: return@mapNotNull null
        SetupRecipe(
            id = id, title = ro["title"].str() ?: id, summary = ro["summary"].str().orEmpty(), minVramGb = ro["min_vram_gb"].int(),
            options = ro["options"].arr().mapNotNull { opt ->
                val oo = opt.obj() ?: return@mapNotNull null
                val key = oo["key"].str() ?: return@mapNotNull null
                val isBool = oo["type"].str() == "bool"
                RecipeOption(
                    key = key, label = oo["label"].str() ?: key, isBool = isBool, boolDefault = oo["default"].bool() ?: false,
                    choices = oo["choices"].arr().mapNotNull { c -> (c as? JsonArray)?.let { a -> a.getOrNull(0).str()?.let { v -> RecipeChoice(v, a.getOrNull(1).str() ?: v) } } },
                    choiceDefault = if (isBool) null else oo["default"].str(),
                )
            },
        )
    }
    val vast = o["vast"].obj()
    return SetupCatalog(
        recipes = recipes,
        thunderExisting = o["existing"].arr().mapNotNull { e ->
            val x = e.obj() ?: return@mapNotNull null
            ThunderExisting(x["id"].str() ?: return@mapNotNull null, x["name"].str(), x["gpu"].str().orEmpty(), x["num_gpus"].int() ?: 1, x["vram_gb"].int(), x["status"].str(), x["price"].dbl(), x["renders_here"].bool() == true)
        },
        thunderNew = o["new"].arr().mapNotNull { e ->
            val x = e.obj() ?: return@mapNotNull null
            ThunderNewSpec(
                x["gpu_type"].str() ?: return@mapNotNull null, x["num_gpus"].int() ?: 1, x["name"].str().orEmpty(), x["vram_gb"].int(), x["price"].dbl(),
                x["cpu_options"].arr().mapNotNull { it.int() }, x["available"].bool() != false,
            )
        },
        vastExisting = vast?.get("existing").arr().mapNotNull { e ->
            val x = e.obj() ?: return@mapNotNull null
            VastExisting(x["id"].str() ?: return@mapNotNull null, x["gpu"].str().orEmpty(), x["num_gpus"].int() ?: 1, x["status"].str(), x["location"].str(), x["price"].dbl())
        },
        vastOffers = vast?.get("offers").arr().mapNotNull { e ->
            val x = e.obj() ?: return@mapNotNull null
            VastOffer(x["id"].str() ?: return@mapNotNull null, x["gpu"].str().orEmpty(), x["vram_gb"].int(), x["price"].dbl(), x["location"].str(), x["reliability"].dbl())
        },
        vastError = vast?.get("error").str(),
        colabLabel = o["colab"].obj()?.get("label").str() ?: "Colab G4",
        thunderError = o["thunder_error"].str(),
    )
}

// ---------------------------------------------------------------- the plan

/** Where a setup goes. [json] is exactly the `target` object the hub's planner expects (the web form's formPlan()). */
sealed interface SetupTarget {
    val json: JsonObject

    data class Thunder(val id: String) : SetupTarget { override val json get() = buildJsonObject { put("kind", "thunder"); put("id", id) } }
    data class ThunderNew(val gpuType: String, val numGpus: Int, val cpuCores: Int, val diskGb: Int) : SetupTarget {
        override val json get() = buildJsonObject { put("kind", "thunder_new"); put("gpu_type", gpuType); put("num_gpus", numGpus); put("cpu_cores", cpuCores); put("disk_gb", diskGb) }
    }
    data class Vast(val id: String) : SetupTarget { override val json get() = buildJsonObject { put("kind", "vast"); put("id", id) } }
    data class VastNew(val offerId: String, val diskGb: Int, val gpu: String?, val price: Double?) : SetupTarget {
        override val json get() = buildJsonObject {
            put("kind", "vast_new"); put("offer_id", offerId); put("disk_gb", diskGb)
            if (gpu != null) put("gpu", gpu); if (price != null) put("price", price)
        }
    }
    data object Colab : SetupTarget { override val json get() = buildJsonObject { put("kind", "colab") } }

    /** Creating a machine starts a bill, so the confirmation asks for a typed word. */
    val createsMachine: Boolean get() = this is ThunderNew || this is VastNew
}

/** The plan the form builds. Only a Thunder machine can take the 14B renders (the web form's rule). */
fun buildPlan(recipe: String, target: SetupTarget, options: Map<String, Any>, useForRenders: Boolean): JsonObject = buildJsonObject {
    put("recipe", recipe)
    put("target", target.json)
    put("options", buildJsonObject {
        options.forEach { (k, v) -> when (v) { is Boolean -> put(k, v); else -> put(k, v.toString()) } }
    })
    put("use_for_renders", useForRenders && (target is SetupTarget.Thunder || target is SetupTarget.ThunderNew))
}

data class PlanPreview(
    val ok: Boolean,
    /** The hub's normalised plan, sent back unchanged to start the run. */
    val plan: JsonObject,
    val recipe: String,
    val targetLabel: String,
    val price: Double?,
    val diskNeedGb: Int?,
    val freeGb: Int?,
    val minutes: Int?,
    val vramGb: Int?,
    val options: Map<String, String>,
    val steps: List<String>,
    val blocking: List<String>,
    val warnings: List<String>,
    val needsSecret: String?,
    val useForRenders: Boolean,
    val targetKind: String?,
)

fun parsePlanPreview(json: JsonElement?): PlanPreview? {
    val o = json.obj() ?: return null
    val info = o["info"].obj() ?: return null
    val plan = o["plan"].obj() ?: return null
    return PlanPreview(
        ok = o["ok"].bool() == true, plan = plan, recipe = info["recipe"].str().orEmpty(), targetLabel = info["target"].str().orEmpty(),
        price = info["price"].dbl(), diskNeedGb = info["disk_need_gb"].int(), freeGb = info["free_gb"].int(), minutes = info["minutes"].int(), vramGb = info["vram_gb"].int(),
        options = info["options"].obj().orEmpty().mapValues { (_, v) -> (v as? JsonPrimitive)?.let { p -> when (p.booleanOrNull) { true -> "yes"; false -> "no"; null -> p.contentOrNull.orEmpty() } }.orEmpty() },
        steps = o["steps"].arr().mapNotNull { it.str() }, blocking = o["blocking"].arr().mapNotNull { it.str() }, warnings = o["warnings"].arr().mapNotNull { it.str() },
        needsSecret = o["needs_secret"].str(), useForRenders = plan["use_for_renders"].bool() == true, targetKind = plan["target"].obj()?.get("kind").str(),
    )
}

data class ChatReply(val reply: String, val preview: PlanPreview?)

fun parseChatReply(json: JsonElement): ChatReply? {
    val o = json.obj() ?: return null
    return ChatReply(o["reply"].str().orEmpty(), parsePlanPreview(o["preview"]))
}

// ---------------------------------------------------------------- runs

data class SetupRun(
    val id: String, val status: String, val step: String, val recipe: String, val targetLabel: String,
    val created: Double?, val launched: Double?, val logTail: String, val targetKind: String?,
) {
    val live: Boolean get() = status in LIVE_STATUSES
    val statusWord: String get() = STATUS_WORD[status] ?: status.replaceFirstChar { it.uppercase() }

    companion object {
        val LIVE_STATUSES = setOf("planned", "creating", "waiting", "uploading", "installing")
        private val STATUS_WORD = mapOf(
            "planned" to "Starting", "creating" to "Creating", "waiting" to "Starting up", "uploading" to "Uploading",
            "installing" to "Installing", "done" to "Done", "failed" to "Failed", "cancelled" to "Cancelled",
        )
    }
}

/** Null when the reply is not a list of runs at all: that must not read as "nothing set up yet". */
fun parseSetupRuns(json: JsonElement): List<SetupRun>? {
    val arr = json as? JsonArray ?: return null
    return arr.mapNotNull { e ->
        val o = e.obj() ?: return@mapNotNull null
        val id = o["id"].str()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: return@mapNotNull null
        val info = o["info"].obj()
        SetupRun(
            id = id, status = o["status"].str() ?: "unknown", step = o["step"].str().orEmpty(),
            recipe = info?.get("recipe").str().orEmpty(), targetLabel = info?.get("target").str().orEmpty(),
            created = o["created"].dbl(), launched = o["launched"].dbl(), logTail = o["log_tail"].str().orEmpty(),
            targetKind = o["plan"].obj()?.get("target").obj()?.get("kind").str(),
        )
    }
}

// ---------------------------------------------------------------- the form

/** The "Where" choices, keyed like the web select (`thunder:ID`, `new`, `vast:ID`, `vastnew`, `colab`). */
data class WhereChoice(val key: String, val label: String, val enabled: Boolean = true)

fun whereChoices(c: SetupCatalog): List<WhereChoice> = buildList {
    c.thunderExisting.forEach {
        add(WhereChoice("thunder:${it.id}", "Thunder ${it.gpu} x${it.numGpus} (current${if (it.rendersHere) ", 14B renders here" else ""}) · ${money(it.price)}"))
    }
    add(WhereChoice("new", "New Thunder instance…"))
    c.vastExisting.forEach { add(WhereChoice("vast:${it.id}", "Vast ${it.gpu} x${it.numGpus} (${it.status.orEmpty()}, ${it.location.orEmpty()}) · ${money(it.price)}")) }
    add(WhereChoice("vastnew", "New Vast machine…${if (c.vastError != null) " (unavailable)" else ""}", enabled = c.vastOffers.isNotEmpty()))
    add(WhereChoice("colab", c.colabLabel))
}

/** What "Move the 14B renders to this machine" applies to: only Thunder targets (the web form hides it elsewhere). */
fun canMoveRenders(whereKey: String) = whereKey == "new" || whereKey.startsWith("thunder:")

/** Turns the form's selections into a target, exactly as the page's formPlan() does. Null when something needed is missing. */
fun targetFromForm(whereKey: String, c: SetupCatalog, gpuIndex: Int, cpu: Int?, diskGb: Int, offerId: String?, vastDiskGb: Int): SetupTarget? = when {
    whereKey == "new" -> c.thunderNew.getOrNull(gpuIndex)?.let { g -> SetupTarget.ThunderNew(g.gpuType, g.numGpus, cpu ?: g.cpuOptions.firstOrNull { it == 8 } ?: g.cpuOptions.firstOrNull() ?: 8, diskGb.coerceAtLeast(0)) }
    whereKey == "colab" -> SetupTarget.Colab
    whereKey == "vastnew" -> c.vastOffers.firstOrNull { it.id == offerId }?.let { o -> SetupTarget.VastNew(o.id, vastDiskGb.coerceAtLeast(0), o.gpu, o.price) }
    whereKey.startsWith("vast:") -> SetupTarget.Vast(whereKey.substringAfter(':'))
    whereKey.startsWith("thunder:") -> SetupTarget.Thunder(whereKey.substringAfter(':'))
    else -> null
}

/** The whereKey that reproduces a target, for "Change it" (the web form's toForm()). */
fun whereKeyFor(plan: JsonObject): String? {
    val t = plan["target"].obj() ?: return null
    return when (t["kind"].str()) {
        "thunder" -> "thunder:${t["id"].str()}"
        "vast" -> "vast:${t["id"].str()}"
        "vast_new" -> "vastnew"
        "colab" -> "colab"
        "thunder_new" -> "new"
        else -> null
    }
}

/** A line saying what confirming will do, for the confirmation dialog. */
fun runConsequences(p: PlanPreview): List<String> = listOfNotNull(
    "Installs ${p.recipe} on ${p.targetLabel}.",
    p.price?.let { "This machine bills ${money(it)} while it exists." } ?: "This uses Colab credits.",
    listOfNotNull(p.diskNeedGb?.let { "needs $it GB of disk" }, p.minutes?.let { "about $it minutes" }).takeIf { it.isNotEmpty() }?.let { "It " + it.joinToString(", ") + "." },
    if (p.targetKind == "thunder_new" || p.targetKind == "vast_new") "A NEW machine is created and bills until you delete it on its screen." else null,
    if (p.useForRenders) "When it's done the 14B renders move to it." else null,
    "You can stop it part-way from the Runs list.",
)
