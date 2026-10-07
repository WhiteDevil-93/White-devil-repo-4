package com.whitedevil.desktop

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

/*
 * What the Venice workspace (the left pane of the UX Pilot design) shows, as plain data built from the hub's own
 * answers. Nothing here is invented: a tile the hub did not answer for says so.
 */

enum class Tone { Ok, Warn, Bad, Quiet }

data class TileState(val label: String, val value: String, val sub: String, val tone: Tone)

data class HubSitrep(val tiles: List<TileState>, val fetchedAtMs: Long)

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.arr() = this as? JsonArray
private fun JsonElement?.str() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
private fun JsonElement?.bool() = (this as? JsonPrimitive)?.booleanOrNull
private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull

private val FINISHED = setOf("done", "error", "failed", "cancelled", "canceled", "stopped", "complete", "completed")

object Sitrep {
    fun colab(state: JsonElement?): TileState {
        val o = state.obj() ?: return unreachable("Colab")
        val runner = o["runner_online"].bool() == true
        val comfy = o["comfy_online"].bool() == true
        val billing = o["billing"].bool() == true
        val paused = o["paused"].str()
        return when {
            comfy -> TileState("Colab", "RUNNING", if (billing) "ComfyUI up · billing on" else "ComfyUI up", Tone.Ok)
            runner -> TileState("Colab", "STARTING", "runner up, ComfyUI not yet", Tone.Warn)
            billing -> TileState("Colab", "BILLING", "stopped but billing is on", Tone.Bad)
            paused != null -> TileState("Colab", "PAUSED", "since $paused", Tone.Quiet)
            else -> TileState("Colab", "OFF", "$0/h", Tone.Quiet)
        }
    }

    fun thunder(state: JsonElement?, queue: JsonElement?): TileState {
        val s = state.obj()
        val q = queue.obj()
        if (s == null && q == null) return unreachable("Thunder")
        val boxes = s?.get("instances").arr().orEmpty().mapNotNull { it.obj() }
        val active = q?.get("jobs").arr().orEmpty().mapNotNull { it.obj() }.count { (it["status"].str() ?: "").lowercase() !in FINISHED }
        val comfy = q?.get("comfy").obj()?.get("online").bool() == true
        val sub = buildList { add(if (active == 0) "queue empty" else "$active in queue"); if (boxes.isNotEmpty()) add(if (comfy) "ComfyUI up" else "ComfyUI down") }.joinToString(" · ")
        if (boxes.isEmpty()) return TileState("Thunder", "NO BOX", sub, if (active > 0) Tone.Warn else Tone.Quiet)
        val status = (boxes.first()["status"].str() ?: "unknown").uppercase()
        val tone = when { status == "RUNNING" && comfy -> Tone.Ok; status == "RUNNING" -> Tone.Warn; else -> Tone.Quiet }
        return TileState("Thunder", if (boxes.size > 1) "$status ×${boxes.size}" else status, sub, tone)
    }

    fun ltx(status: JsonElement?, jobs: JsonElement?): TileState {
        val s = status.obj() ?: return unreachable("LTX")
        val active = jobs.arr().orEmpty().mapNotNull { it.obj() }.count { (it["status"].str() ?: "").lowercase() !in FINISHED }
        val online = s["online"].bool() == true
        val busy = s["busy"].str()
        val sub = if (active == 0) "no jobs waiting" else "$active job${if (active == 1) "" else "s"} waiting"
        return when {
            online && busy != null -> TileState("LTX queue", "%02d".format(Locale.US, active), "busy: ${busy.take(40)}", Tone.Warn)
            online -> TileState("LTX queue", "%02d".format(Locale.US, active), "builder online · $sub", Tone.Ok)
            else -> TileState("LTX queue", "%02d".format(Locale.US, active), "builder offline · $sub", if (active > 0) Tone.Warn else Tone.Quiet)
        }
    }

    fun vast(state: JsonElement?): TileState {
        val o = state.obj() ?: return unreachable("Vast")
        val credit = o["credit"].num()
        val running = o["instances"].arr().orEmpty().mapNotNull { it.obj() }.filter { (it["status"].str() ?: "") == "running" }
        val perHour = running.sumOf { it["price"].num() ?: 0.0 }
        val value = credit?.let { "$" + "%.2f".format(Locale.US, it) } ?: "?"
        return if (running.isEmpty()) TileState("Vast credit", value, "no boxes running", Tone.Quiet)
        else TileState("Vast credit", value, "${running.size} running · $" + "%.2f".format(Locale.US, perHour) + "/h", Tone.Warn)
    }

    private fun unreachable(label: String) = TileState(label, "—", "hub did not answer", Tone.Bad)
}

/** Fetches the five hub answers the sitrep needs, in parallel. Each one may fail on its own. */
class SitrepClient(hubUrl: String, relayUser: String, relayPass: String, engine: HttpClientEngine? = null) : AutoCloseable {
    private val hub = HubCaller(hubUrl, relayUser, relayPass, engine)

    private suspend fun get(path: String): JsonElement? =
        (hub.call(path, 15_000) { text -> MediaResult.Ok(Json.parseToJsonElement(text)) } as? MediaResult.Ok)?.value

    suspend fun load(nowMs: Long = System.currentTimeMillis()): HubSitrep = coroutineScope {
        val colab = async { get("/api/colab/state") }
        val tState = async { get("/api/thunder/state") }
        val tQueue = async { get("/api/thunder/queue") }
        val lStatus = async { get("/api/ltx/status") }
        val lJobs = async { get("/api/ltx/jobs") }
        val vast = async { get("/api/vast/state") }
        HubSitrep(
            listOf(Sitrep.colab(colab.await()), Sitrep.thunder(tState.await(), tQueue.await()), Sitrep.ltx(lStatus.await(), lJobs.await()), Sitrep.vast(vast.await())),
            nowMs,
        )
    }

    override fun close() { hub.close() }
}

// ---------------------------------------------------------------- the current goal (top of the console)

enum class StepState { Done, Running, Failed }

data class GoalStep(val text: String, val state: StepState)

data class Goal(val text: String, val steps: List<GoalStep>)

/**
 * The goal card: the last thing you asked in this chat, and the tools Venice has run for it since, in order. The
 * last step spins while the agent is still working; a tool whose output starts with "Error" is marked failed.
 */
fun currentGoal(lines: List<ChatLine>, busy: Boolean): Goal? {
    val start = lines.indexOfLast { it.role == ROLE_USER }
    if (start < 0) return null
    val steps = mutableListOf<GoalStep>()
    for (l in lines.drop(start + 1)) {
        when (l.role) {
            ROLE_TOOL_CALL -> steps += GoalStep(stepText(l), StepState.Running)
            ROLE_TOOL_OUT -> steps.indexOfLast { it.state == StepState.Running }.takeIf { it >= 0 }?.let { i ->
                steps[i] = steps[i].copy(state = if (l.body.trimStart().startsWith("Error", ignoreCase = true)) StepState.Failed else StepState.Done)
            }
        }
    }
    // Nothing is still running once the agent has stopped, whatever the log says.
    val settled = if (busy) steps else steps.map { if (it.state == StepState.Running) it.copy(state = StepState.Done) else it }
    return Goal(lines[start].body.oneLine().take(220), settled)
}

/** "hub_request GET /api/ltx/jobs" from a tool-call line: the name, then the most telling argument, briefly. */
internal fun stepText(l: ChatLine): String {
    val name = l.title.removePrefix("Tool · ")
    val args = runCatching { Json.parseToJsonElement(l.body) as? JsonObject }.getOrNull()
    val hint = args?.let { a -> listOf("path", "command", "name", "note", "prompt", "query", "cloud").firstNotNullOfOrNull { k -> a[k].str() } }
    val method = args?.get("method").str()
    return listOfNotNull(name, method, hint?.oneLine()?.take(70)).joinToString(" ")
}

// ---------------------------------------------------------------- the live terminal (what the tools did)

enum class TermKind { Cmd, Out, Err }

data class TermLine(val text: String, val kind: TermKind)

/** Every tool call in this chat as a terminal transcript: "$ name args" then the first few lines of its output. */
fun terminalLines(lines: List<ChatLine>, outLines: Int = 4, maxTotal: Int = 400): List<TermLine> {
    val out = mutableListOf<TermLine>()
    for (l in lines) {
        when (l.role) {
            ROLE_TOOL_CALL -> out += TermLine("$ " + stepText(l), TermKind.Cmd)
            ROLE_TOOL_OUT -> {
                val err = l.body.trimStart().startsWith("Error", ignoreCase = true)
                val body = l.body.lines().filter { it.isNotBlank() }
                body.take(outLines).forEach { out += TermLine(it.take(160), if (err) TermKind.Err else TermKind.Out) }
                if (body.size > outLines) out += TermLine("… ${body.size - outLines} more lines", TermKind.Out)
            }
        }
    }
    return out.takeLast(maxTotal)
}

// ---------------------------------------------------------------- the token meter

/** A rough count (about four characters a token), good enough to warn before a model's context fills up. */
fun estimateTokens(chars: Int): Int = (chars + 3) / 4

fun tokenLabel(used: Int, context: Int?): String {
    fun k(n: Int) = if (n >= 1000) "%.1fk".format(Locale.US, n / 1000.0).replace(".0k", "k") else n.toString()
    return "Tokens: ~" + k(used) + (context?.let { "/" + k(it) } ?: "")
}
