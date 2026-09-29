package com.whitedevil.desktop.ops

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Colab, from hub/colab.py. Routes relied on (read side):
 *   GET /api/colab/state   colab.py:147-204  (runner_mode, runner_online, comfy_online, paused, billing,
 *                                             instance{}, status{label,kind,billing,detail}, gpu, jobs[],
 *                                             heartbeat{}, usage{balance,rate_per_hr,active,checked},
 *                                             recover_running, recover_log[], resume_packs[])
 *   GET /api/colab/packs   colab.py:215-235  ([{index,title,clips,rendered}])
 * Mutating routes wrapped here, both behind the typed-confirmation gate:
 *   POST /api/colab/stop-runtime  colab.py:288-306  (200 with ok:false when still billing)
 *   POST /api/colab/recover       colab.py:275-285  (launches colab_recover.sh: starts a G4)
 * Deliberately NOT wrapped: /runner-token (returns a secret), /queue and the
 * per-job cancel/retry routes (they spend GPU time or act on jobs), which this
 * screen does not offer.
 *
 * Note for the operator: the hub's GET /state has server-side effects of its own
 * (colab.py:171-174 clears ~/wan/paused_comfy and restarts the Comfy tunnel when
 * billing is active). Polling it is what the web screen does too; it is not a
 * start/stop/spend.
 */

data class ColabInstance(
    val name: String?,
    val endpoint: String?,
    val accelerator: String?,
    val status: String?,
    val running: Boolean?,
    val rawCliOutput: String?,
)

data class ColabSummary(val label: String?, val kind: String?, val billing: Boolean?, val detail: String?)

data class ColabUsage(
    val balance: Double?,
    val ratePerHr: Double?,
    val activeAssignments: Int?,
    /** Epoch seconds of the hub's last `colab usage` read; 0 means it never ran. */
    val checkedAtEpochSec: Double?,
) {
    /** False when `colab usage` produced nothing parseable — the numbers are unknown, not zero. */
    val readable: Boolean get() = balance != null || ratePerHr != null || activeAssignments != null
}

data class ColabJob(
    val id: String?,
    val name: String?,
    val chainId: String?,
    val status: String?,
    val progress: String?,
    val currentClip: Int?,
    val avgSeconds: Int?,
    val error: String?,
)

data class ColabState(
    val runnerMode: String?,
    val runnerOnline: Boolean?,
    val comfyOnline: Boolean?,
    val paused: String?,
    val billing: Boolean?,
    val instance: ColabInstance?,
    val summary: ColabSummary?,
    val gpu: String?,
    val jobs: List<ColabJob>,
    val heartbeat: Map<String, String>,
    val heartbeatAtEpochSec: Double?,
    val usage: ColabUsage?,
    val recoverRunning: Boolean?,
    val recoverLog: List<String>,
    val resumePacks: List<String>,
) {
    companion object {
        private val EXPECTED = listOf("status", "instance", "usage", "runner_online", "billing")

        fun parse(json: JsonElement): OpsResult<ColabState> {
            val o = json as? JsonObject
                ?: return shapeError("Colab state", "a JSON object", json)
            if (EXPECTED.none { it in o }) {
                return OpsResult.Err(
                    OpsError(
                        "The hub's Colab state has none of the expected fields (${EXPECTED.joinToString(", ")}); it sent: ${o.keys.take(12).joinToString(", ").ifEmpty { "an empty object" }}.",
                        kind = OpsErrorKind.BadShape, body = capBody(o.toString()),
                    ),
                )
            }
            val inst = o.obj("instance")?.let {
                ColabInstance(
                    name = it.nonBlankStr("name"), endpoint = it.nonBlankStr("endpoint"),
                    accelerator = it.nonBlankStr("accelerator"), status = it.nonBlankStr("status"),
                    running = it.bool("running"), rawCliOutput = it.nonBlankStr("raw"),
                )
            }
            val summary = o.obj("status")?.let {
                ColabSummary(it.nonBlankStr("label"), it.nonBlankStr("kind"), it.bool("billing"), it.nonBlankStr("detail"))
            }
            val usage = o.obj("usage")?.let {
                ColabUsage(it.num("balance"), it.num("rate_per_hr"), it.int("active"), it.num("checked"))
            }
            val hb = o.obj("heartbeat")
            val hbMap = hb?.entries
                ?.mapNotNull { (k, v) -> if (k == "at") null else (v as? JsonPrimitive)?.content?.let { c -> k to c } }
                ?.toMap() ?: emptyMap()
            return OpsResult.Ok(
                ColabState(
                    runnerMode = o.nonBlankStr("runner_mode"),
                    runnerOnline = o.bool("runner_online"),
                    comfyOnline = o.bool("comfy_online"),
                    paused = o.nonBlankStr("paused"),
                    billing = o.bool("billing") ?: summary?.billing,
                    instance = inst,
                    summary = summary,
                    gpu = o.nonBlankStr("gpu"),
                    jobs = o.arr("jobs").objects().map(::parseJob),
                    heartbeat = hbMap,
                    heartbeatAtEpochSec = hb?.num("at"),
                    usage = usage,
                    recoverRunning = o.bool("recover_running"),
                    recoverLog = o.arr("recover_log").strings(),
                    resumePacks = o.arr("resume_packs").strings(),
                ),
            )
        }

        private fun parseJob(j: JsonObject) = ColabJob(
            id = j.nonBlankStr("id"), name = j.nonBlankStr("name"), chainId = j.nonBlankStr("chain_id"),
            status = j.nonBlankStr("status"), progress = progressText(j["progress"]),
            currentClip = j.int("current"), avgSeconds = j.int("avg_seconds"), error = j.nonBlankStr("error"),
        )
    }
}

internal fun progressText(e: JsonElement?): String? = when (e) {
    is JsonObject -> {
        val d = e.int("done")
        val t = e.int("total")
        if (d != null && t != null) "$d/$t" else e.compact(60)
    }
    else -> e.compact(40)
}

internal fun shapeError(what: String, expected: String, got: JsonElement): OpsResult.Err = OpsResult.Err(
    OpsError(
        "The hub's $what reply was not $expected (got ${got::class.simpleName}).",
        kind = OpsErrorKind.BadShape, body = capBody(got.toString()),
    ),
)

data class ColabPack(val index: Int?, val title: String?, val clips: Int?, val rendered: Int?) {
    companion object {
        fun parseList(json: JsonElement): OpsResult<List<ColabPack>> {
            val arr = json as? kotlinx.serialization.json.JsonArray
                ?: return shapeError("Colab packs", "a JSON array", json)
            return OpsResult.Ok(
                arr.objects().map { ColabPack(it.int("index"), it.nonBlankStr("title"), it.int("clips"), it.int("rendered")) },
            )
        }
    }
}

/** Reads only. Holds an [OpsReader], which has no way to POST. */
class ColabApi(private val reader: OpsReader) {
    suspend fun state(): OpsResult<ColabState> =
        reader.getJson(STATE_PATH, STATE_TIMEOUT_MS).flatMap { ColabState.parse(it) }

    suspend fun packs(): OpsResult<List<ColabPack>> =
        reader.getJson(PACKS_PATH).flatMap { ColabPack.parseList(it) }

    companion object {
        const val STATE_PATH = "/api/colab/state"
        const val PACKS_PATH = "/api/colab/packs"

        /** The hub runs `colab usage` (<=60s) and `colab status` (<=45s) inline on a cold cache. */
        const val STATE_TIMEOUT_MS = 150_000L
    }
}

/** The two mutating calls. Only ever run from behind [ActionController.confirm]. */
class ColabActions(private val actor: OpsActor) {
    /** `colab stop -s colab` under a flock of up to 300s on the hub, so the wait is long. */
    suspend fun stopRuntime(): ActionOutcome =
        actor.post(STOP_PATH, timeoutMs = STOP_TIMEOUT_MS).toOutcome(::interpretColabStop)

    suspend fun recover(): ActionOutcome =
        actor.post(RECOVER_PATH, timeoutMs = 90_000L).toOutcome(::interpretColabRecover)

    companion object {
        const val STOP_PATH = "/api/colab/stop-runtime"
        const val RECOVER_PATH = "/api/colab/recover"
        const val STOP_TIMEOUT_MS = 420_000L
    }
}

internal fun interpretColabStop(reply: HubReply): ActionOutcome {
    val o = reply.json as? JsonObject ?: return notAnObject(reply, "Stop Colab runtime")
    val ok = o.bool("ok")
    val billing = o.bool("billing")
    val output = o.nonBlankStr("output")
    val raw = capBody(reply.rawBody)
    if (ok == true && billing != true) {
        return ActionOutcome.Succeeded(
            headline = "The hub reports the Colab runtime stopped and no active assignment remains.",
            detail = output, rawBody = raw, status = reply.status,
        )
    }
    val headline = when {
        billing == true -> "The stop did NOT take effect: Colab still shows an active assignment, so you are still being billed."
        ok == false -> "The hub reported the stop as failed (ok=false, HTTP ${reply.status})."
        else -> "The hub's reply has no ok flag, so the stop cannot be confirmed."
    }
    return ActionOutcome.Failed(
        headline = headline,
        parts = listOf(
            OutcomePart("Stop command", ok == true, output),
            OutcomePart(
                "Billing ended", billing == false,
                when (billing) { true -> "Colab still shows an active assignment"; null -> "unknown"; false -> null },
            ),
        ),
        rawBody = raw, status = reply.status, mayHaveExecuted = ok == null,
    )
}

internal fun interpretColabRecover(reply: HubReply): ActionOutcome {
    val o = reply.json as? JsonObject ?: return notAnObject(reply, "Start / recover Colab")
    val ok = o.bool("ok")
    val raw = capBody(reply.rawBody)
    return when {
        ok == true && o.bool("already_running") == true -> ActionOutcome.Succeeded(
            headline = "A recovery was already running on the hub; nothing new was launched.",
            rawBody = raw, status = reply.status,
        )
        ok == true -> ActionOutcome.Succeeded(
            headline = "The hub launched the recovery script. It runs in the background, so this is not confirmation that a GPU is up: watch Status and the recover log. Billing starts when Colab assigns the GPU.",
            rawBody = raw, status = reply.status,
        )
        else -> ActionOutcome.Failed(
            headline = if (ok == false) "The hub reported the recovery as failed (ok=false, HTTP ${reply.status})."
            else "The hub's reply has no ok flag, so the recovery cannot be confirmed.",
            rawBody = raw, status = reply.status, mayHaveExecuted = ok == null,
        )
    }
}
