package com.whitedevil.desktop.ops

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * LTX, from hub/ltx.py and hub/ltx_qa_cycle.py. Routes relied on (read side):
 *   GET /api/ltx/status        ltx.py:129   {online, billing, detail, installs{}} when ComfyUI is unreachable;
 *                                           plus transformers[], loras[], distilled, decoders{quality,fast}, clips[],
 *                                           writers[], triggers{}, busy, frames[], sizes[] when it answers.
 *                                           `online` is "ComfyUI answers AND has an LTX model", so online:false
 *                                           can mean either; `detail` says which. Read-only on the hub (GETs to
 *                                           ComfyUI; when it is down, the cached `colab usage`/`colab status`).
 *   GET /api/ltx/jobs          ltx.py:969   newest 30 job files, each a JSON object. Three kinds: a clip (no `kind`),
 *                                           `kind:"chain"` (parts[], lines[], step) and `kind:"sharpen"` (source).
 *   GET /api/ltx/cycle/status  ltx.py:1274  {running, pid, src, kept[], history_len, last{}, log_tail}
 * Mutating routes wrapped here, each behind the confirmation gate:
 *   POST /api/ltx/cycle/start        ltx.py:1300  body {src?, rounds}; STOPS a running cycle first; the script then
 *                                                 submits chains through /api/ltx/chain (GPU) and has a vision
 *                                                 model on OpenRouter review each one (ltx_qa_cycle.py)
 *   POST /api/ltx/cycle/stop         ltx.py:1338  signals the cycle process; does NOT cancel a chain it already submitted
 *   POST /api/ltx/jobs/{jid}/cancel  ltx.py:995   200 {ok:true}; 409 when the job is not queued/rendering
 *
 * Deliberately NOT wrapped, so they stay on the web LTX screen: /render, /chain and /jobs/{jid}/sharpen (each starts
 * GPU renders from a large option set, some with an uploaded picture), /install and /install-local (download models
 * onto the Colab), and /assist (spends OpenRouter credit writing prompts).
 * Deliberately NEVER fetched, both answer with a file rather than JSON (checked in ltx.py):
 *   GET /api/ltx/jobs/{jid}/input  ltx.py:981   FileResponse of the job's input picture (image, up to 30 MB as
 *                                                uploaded; 404 for text-to-video), so it is not a JSON route and
 *                                                can exceed the 8 MB thumbnail cap MediaClient enforces
 *   GET /api/ltx/stage/{name}      ltx.py:1139  FileResponse(media_type="application/octet-stream") of a staged
 *                                                .safetensors LoRA: hundreds of MB, and it exists for the Colab to pull
 */

data class LtxInstall(
    val name: String,
    val kind: String?,
    val state: String?,
    val sizeMb: Double?,
    val detail: String?,
)

data class LtxStatus(
    val online: Boolean?,
    val billing: Boolean?,
    val detail: String?,
    /** ComfyUI's running + pending queue length; null when ComfyUI did not answer. */
    val queueBusy: Int?,
    /** null when the hub did not send the list (ComfyUI unreachable), which is not the same as an empty list. */
    val transformers: List<String>?,
    val loras: List<String>?,
    val distilled: Boolean?,
    val decoderQuality: Boolean?,
    val decoderFast: Boolean?,
    val installs: List<LtxInstall>,
) {
    companion object {
        fun parse(json: JsonElement): OpsResult<LtxStatus> {
            val o = json as? JsonObject
                ?: return shapeError("LTX status", "a JSON object", json)
            if ("online" !in o) {
                return OpsResult.Err(
                    OpsError(
                        "The hub's LTX status has no `online` field; it sent: ${o.keys.take(12).joinToString(", ").ifEmpty { "an empty object" }}.",
                        kind = OpsErrorKind.BadShape, body = capBody(o.toString()),
                    ),
                )
            }
            val decoders = o.obj("decoders")
            val installs = o.obj("installs")?.entries.orEmpty().mapNotNull { (key, value) ->
                (value as? JsonObject)?.let {
                    LtxInstall(
                        name = it.nonBlankStr("name") ?: key,
                        kind = it.nonBlankStr("kind"),
                        state = it.nonBlankStr("state"),
                        sizeMb = it.num("size_mb"),
                        detail = it.nonBlankStr("detail"),
                    )
                }
            }
            return OpsResult.Ok(
                LtxStatus(
                    online = o.bool("online"),
                    billing = o.bool("billing"),
                    detail = o.nonBlankStr("detail"),
                    queueBusy = o.int("busy"),
                    transformers = o.arr("transformers")?.strings(),
                    loras = o.arr("loras")?.strings(),
                    distilled = o.bool("distilled"),
                    decoderQuality = decoders?.bool("quality"),
                    decoderFast = decoders?.bool("fast"),
                    installs = installs,
                ),
            )
        }
    }
}

data class LtxJob(
    val id: String?,
    val name: String?,
    /** null = a plain clip, "chain", or "sharpen". Anything else is kept as the hub sent it. */
    val kind: String?,
    /** queued | rendering | done | failed, as the hub wrote it. */
    val status: String?,
    val step: String?,
    val error: String?,
    val frames: Int?,
    val size: String?,
    /** Length of `parts` on a chain job. Meaningless on a clip; a sharpen job copies its source's. */
    val partCount: Int?,
    /** Length of `lines` (one prompt per part). The hub stores it only when there was more than one line. */
    val lineCount: Int?,
    val textToVideo: Boolean?,
    val createdEpochSec: Double?,
    val seconds: Int?,
) {
    val active: Boolean get() = status == "queued" || status == "rendering"

    val kindLabel: String
        get() = when (kind) {
            null -> "clip"
            "chain" -> "chain"
            "sharpen" -> "2× sharpen"
            else -> kind.orEmpty()
        }

    /** True when the id is safe to put in a URL path: the hub's own job ids are 12 lowercase hex digits. */
    val hasValidId: Boolean get() = id != null && isValidId(id)

    /**
     * Whether the cycle script can use this job as its source. `queue()` in ltx_qa_cycle.py reads the job's
     * `lines` (one prompt per part), `frames` and `size`, and the job's start picture; it raises on a job
     * with no `lines`, and a text-to-video chain has no start picture. The hub route itself only checks that
     * the job file exists, so this is the screen being stricter than the hub, on purpose.
     */
    val usableAsCycleSource: Boolean
        get() = hasValidId && kind == "chain" && textToVideo != true && (lineCount ?: 0) > 0 && frames != null && size != null

    companion object {
        private val ID = Regex("^[0-9a-f]{12}$")

        fun isValidId(id: String): Boolean = ID.matches(id)

        fun parseList(json: JsonElement): OpsResult<List<LtxJob>> {
            val arr = json as? JsonArray
                ?: return shapeError("LTX jobs", "a JSON array", json)
            return OpsResult.Ok(arr.objects().map(::parseJob))
        }

        private fun parseJob(j: JsonObject) = LtxJob(
            id = j.nonBlankStr("id"),
            name = j.nonBlankStr("name"),
            kind = j.nonBlankStr("kind"),
            status = j.nonBlankStr("status"),
            step = j.nonBlankStr("step"),
            error = j.nonBlankStr("error"),
            frames = j.int("frames"),
            size = j.nonBlankStr("size"),
            partCount = j.arr("parts")?.size,
            lineCount = j.arr("lines")?.size,
            textToVideo = j.bool("t2v"),
            createdEpochSec = j.num("created"),
            seconds = j.int("seconds"),
        )
    }
}

/** One line of the cycle's history: a review verdict, or a failed render. */
data class LtxCycleEntry(
    val round: Int?,
    val stack: Int?,
    val jobId: String?,
    val verdict: String?,
    val status: String?,
    val error: String?,
    val adjustNote: String?,
    val report: String?,
)

data class LtxCycle(
    val running: Boolean,
    val pid: Int?,
    /** From the script's state file, which is written after each render, so it can describe the previous run. */
    val src: String?,
    val kept: List<String>,
    val historyLen: Int?,
    val last: LtxCycleEntry?,
    val logTail: String,
) {
    companion object {
        fun parse(json: JsonElement): OpsResult<LtxCycle> {
            val o = json as? JsonObject
                ?: return shapeError("LTX cycle status", "a JSON object", json)
            val running = o.bool("running")
                ?: return OpsResult.Err(
                    OpsError(
                        "The hub's LTX cycle status has no boolean `running` field; it sent: ${o.keys.take(12).joinToString(", ").ifEmpty { "an empty object" }}.",
                        kind = OpsErrorKind.BadShape, body = capBody(o.toString()),
                    ),
                )
            val last = o.obj("last")?.let {
                LtxCycleEntry(
                    round = it.int("round"),
                    stack = it.int("stack"),
                    jobId = it.nonBlankStr("jid"),
                    verdict = it.nonBlankStr("verdict"),
                    status = it.nonBlankStr("status"),
                    error = it.nonBlankStr("error"),
                    adjustNote = it.nonBlankStr("adjust_note"),
                    report = it.nonBlankStr("report"),
                )
            }
            return OpsResult.Ok(
                LtxCycle(
                    running = running,
                    pid = o.int("pid"),
                    src = o.nonBlankStr("src"),
                    kept = o.arr("kept").strings(),
                    historyLen = o.int("history_len"),
                    last = last,
                    logTail = o.str("log_tail").orEmpty(),
                ),
            )
        }
    }
}

/**
 * What a cycle would ask of the GPU, worked out the way ltx_qa_cycle.py works it out: every round renders
 * one chain per recipe (two recipes), each chain has one part per line of the source job, and it stops after
 * the first round that yields a KEEP. So this is the worst case; a KEEP in round one costs a quarter of round four.
 */
data class LtxCycleCost(val rounds: Int, val chains: Int, val clipsPerChain: Int, val clips: Int, val frames: Int?, val size: String?) {
    companion object {
        const val RECIPES_PER_ROUND = 2

        fun of(rounds: Int, source: LtxJob): LtxCycleCost {
            val parts = source.lineCount ?: 0
            val chains = rounds * RECIPES_PER_ROUND
            return LtxCycleCost(rounds, chains, parts, chains * parts, source.frames, source.size)
        }
    }
}

/** Reads only. Holds an [OpsReader], which has no way to POST. */
class LtxApi(private val reader: OpsReader) {
    suspend fun status(): OpsResult<LtxStatus> =
        reader.getJson(STATUS_PATH, STATUS_TIMEOUT_MS).flatMap { LtxStatus.parse(it) }

    suspend fun jobs(): OpsResult<List<LtxJob>> =
        reader.getJson(JOBS_PATH).flatMap { LtxJob.parseList(it) }

    suspend fun cycle(): OpsResult<LtxCycle> =
        reader.getJson(CYCLE_STATUS_PATH).flatMap { LtxCycle.parse(it) }

    companion object {
        const val STATUS_PATH = "/api/ltx/status"
        const val JOBS_PATH = "/api/ltx/jobs"
        const val CYCLE_STATUS_PATH = "/api/ltx/cycle/status"

        /**
         * ComfyUI is asked four times (30s each on the hub) and, when it does not answer, the hub reads
         * `colab usage` (<=60s) and `colab status` (<=45s) on a cold cache, all inline.
         */
        const val STATUS_TIMEOUT_MS = 150_000L
    }
}

/** The three mutating calls. Only ever run from behind [ActionController.confirm]. */
class LtxActions(private val actor: OpsActor) {
    /** [src] must be a job id from the hub's own list; [rounds] is the hub's own 1..10 range. */
    suspend fun startCycle(rounds: Int, src: String): ActionOutcome {
        require(rounds in MIN_ROUNDS..MAX_ROUNDS) { "rounds must be $MIN_ROUNDS..$MAX_ROUNDS, got $rounds" }
        require(LtxJob.isValidId(src)) { "not a job id: $src" }
        val body = buildJsonObject {
            put("rounds", rounds)
            put("src", src)
        }
        return actor.post(CYCLE_START_PATH, body, timeoutMs = START_TIMEOUT_MS).toOutcome(::interpretLtxCycleStart)
    }

    suspend fun stopCycle(): ActionOutcome =
        actor.post(CYCLE_STOP_PATH, timeoutMs = START_TIMEOUT_MS).toOutcome(::interpretLtxCycleStop)

    /** Asks ComfyUI (three calls of up to 30s each on the hub) to drop the job, so the wait is long. */
    suspend fun cancelJob(id: String): ActionOutcome {
        require(LtxJob.isValidId(id)) { "not a job id: $id" }
        return actor.post("/api/ltx/jobs/$id/cancel", timeoutMs = CANCEL_TIMEOUT_MS).toOutcome(::interpretLtxCancel)
    }

    companion object {
        const val CYCLE_START_PATH = "/api/ltx/cycle/start"
        const val CYCLE_STOP_PATH = "/api/ltx/cycle/stop"
        const val MIN_ROUNDS = 1
        const val MAX_ROUNDS = 10
        const val START_TIMEOUT_MS = 60_000L
        const val CANCEL_TIMEOUT_MS = 120_000L
    }
}

internal fun interpretLtxCycleStart(reply: HubReply): ActionOutcome {
    val o = reply.json as? JsonObject ?: return notAnObject(reply, "Start LTX cycle")
    val ok = o.bool("ok")
    val raw = capBody(reply.rawBody)
    return when (ok) {
        true -> ActionOutcome.Succeeded(
            headline = "The hub started the cycle process${o.int("pid")?.let { " (pid $it)" } ?: ""}. " +
                "It runs in the background, so this is not confirmation that a render has begun: watch the Cycle card and the Jobs list. " +
                "Renders bill on the Colab GPU while they run.",
            detail = o.nonBlankStr("message"),
            rawBody = raw, status = reply.status,
        )
        else -> ActionOutcome.Failed(
            headline = if (ok == false) "The hub reported the cycle start as failed (ok=false, HTTP ${reply.status})."
            else "The hub's reply has no ok flag, so the cycle start cannot be confirmed.",
            rawBody = raw, status = reply.status, mayHaveExecuted = ok == null,
        )
    }
}

internal fun interpretLtxCycleStop(reply: HubReply): ActionOutcome {
    val o = reply.json as? JsonObject ?: return notAnObject(reply, "Stop LTX cycle")
    val ok = o.bool("ok")
    val raw = capBody(reply.rawBody)
    if (ok != true) {
        return ActionOutcome.Failed(
            headline = if (ok == false) "The hub reported the cycle stop as failed (ok=false, HTTP ${reply.status})."
            else "The hub's reply has no ok flag, so the cycle stop cannot be confirmed.",
            rawBody = raw, status = reply.status, mayHaveExecuted = ok == null,
        )
    }
    val stopped = o.arr("stopped").strings()
    return ActionOutcome.Succeeded(
        headline = if (stopped.isEmpty()) "The hub found no cycle process to stop."
        else "The hub signalled the cycle process (pid ${stopped.joinToString(", ")}) to stop. " +
            "A chain it had already submitted is NOT cancelled by this and keeps using the GPU until it finishes: cancel it under Jobs if you do not want it.",
        rawBody = raw, status = reply.status,
    )
}

internal fun interpretLtxCancel(reply: HubReply): ActionOutcome {
    val o = reply.json as? JsonObject ?: return notAnObject(reply, "Cancel LTX job")
    val ok = o.bool("ok")
    val raw = capBody(reply.rawBody)
    return if (ok == true) {
        ActionOutcome.Succeeded(
            headline = "The hub marked the job cancelled and asked ComfyUI to drop it. Check the Jobs list to see it as failed (Cancelled).",
            rawBody = raw, status = reply.status,
        )
    } else {
        ActionOutcome.Failed(
            headline = if (ok == false) "The hub reported the cancel as failed (ok=false, HTTP ${reply.status})."
            else "The hub's reply has no ok flag, so the cancel cannot be confirmed.",
            rawBody = raw, status = reply.status, mayHaveExecuted = ok == null,
        )
    }
}
