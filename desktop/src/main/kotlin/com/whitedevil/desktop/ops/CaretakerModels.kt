package com.whitedevil.desktop.ops

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The caretaker domain on the relay (a peer of Forge Hub, served at /caretaker/): a resident maintenance
 * model that watches the box and, once switched live, fixes it from a fixed list of actions.
 * Reads: GET /caretaker/api/state and /caretaker/api/audit. The only writes are pause/resume and a
 * read-only chat question.
 */
data class CaretakerState(
    /** Things the rules flagged right now (disk, hub down, pending upgrades, old backup, ...). */
    val anomalies: List<String>,
    /** Work that makes a restart or reboot unsafe right now. */
    val inFlight: List<String>,
    val paused: Boolean,
    /** True when it may act on its own; false = dry-run (it only logs what it would do). */
    val live: Boolean,
    val modelLoaded: Boolean,
    val diskUsedPct: Double?,
    val memAvailableMb: Int?,
    val load1: Double?,
    val hubService: String?,
    val hubHttp: String?,
    val aptUpgradable: String?,
    val rebootRequired: Boolean,
    val kernel: String?,
    val lastBackupAgeHours: Double?,
    val failedUnits: List<String>,
    val factsTime: String?,
) {
    val hubHealthy: Boolean get() = hubService == "active" && hubHttp == "200"

    companion object {
        fun parse(json: JsonElement): OpsResult<CaretakerState> {
            val root = json.asObj()
                ?: return OpsResult.Err(OpsError("The caretaker's state was not a JSON object.", kind = OpsErrorKind.BadShape))
            val f = root.obj("facts")
                ?: return OpsResult.Err(OpsError("The caretaker's state has no 'facts' section: this may not be the caretaker.", kind = OpsErrorKind.BadShape))
            return OpsResult.Ok(
                CaretakerState(
                    anomalies = root.arr("anomalies").strings(),
                    inFlight = root.arr("inflight").strings(),
                    paused = root.bool("paused") ?: false,
                    live = root.bool("live") ?: false,
                    modelLoaded = root.bool("model_loaded") ?: false,
                    diskUsedPct = f.num("disk_used_pct"),
                    memAvailableMb = f.int("mem_available_mb"),
                    load1 = f.num("load1"),
                    hubService = f.nonBlankStr("forge_hub_service"),
                    hubHttp = (f["forge_hub_http"] as? JsonPrimitive)?.content,
                    aptUpgradable = f.nonBlankStr("apt_upgradable"),
                    rebootRequired = f.bool("reboot_required") ?: false,
                    kernel = f.nonBlankStr("running_kernel"),
                    lastBackupAgeHours = f.num("last_backup_age_h"),
                    failedUnits = f.str("failed_units").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() && it != "none" },
                    factsTime = f.nonBlankStr("time"),
                ),
            )
        }
    }
}

/** One line of the caretaker's activity log. */
data class CaretakerEvent(val time: String, val event: String, val detail: String)

object CaretakerAudit {
    /** Newest first. Entries without an event name are dropped, extra fields are ignored. */
    fun parse(json: JsonElement): OpsResult<List<CaretakerEvent>> {
        val arr = json.asArr()
            ?: return OpsResult.Err(OpsError("The caretaker's activity log was not a JSON list.", kind = OpsErrorKind.BadShape))
        val rows = arr.objects().mapNotNull { o ->
            val event = o.nonBlankStr("event") ?: return@mapNotNull null
            val detail = listOfNotNull(
                o.nonBlankStr("action"),
                o.nonBlankStr("reason"),
                o.arr("why").strings().takeIf { it.isNotEmpty() }?.joinToString("; "),
                o.arr("anomalies").strings().takeIf { it.isNotEmpty() }?.joinToString("; "),
                o.nonBlankStr("msg"),
                o.nonBlankStr("err"),
                (o["paused"] as? JsonPrimitive)?.content?.let { "paused=$it" },
            ).joinToString(" · ")
            CaretakerEvent(o.str("t").orEmpty(), event, detail)
        }
        return OpsResult.Ok(rows.asReversed())
    }
}

/** Read side: GET only, through [OpsReader]. */
class CaretakerApi(private val reader: OpsReader) {
    suspend fun state(): OpsResult<CaretakerState> = reader.getJson("/caretaker/api/state").flatMap(CaretakerState::parse)
    suspend fun audit(): OpsResult<List<CaretakerEvent>> = reader.getJson("/caretaker/api/audit").flatMap(CaretakerAudit::parse)
}

/** Write side. Pause/resume is only ever run from behind the confirmation dialog. */
class CaretakerActions(private val actor: OpsActor) {
    suspend fun pause(paused: Boolean): ActionOutcome =
        actor.post("/caretaker/api/pause", buildJsonObject { put("paused", paused) }, timeoutMs = 20_000L)
            .toOutcome { interpretCaretakerPause(it, paused) }

    /**
     * A question for the caretaker's own model. Read-only on the relay (it can explain, it cannot act), so
     * unlike the other writes it needs no confirmation. The first answer after idle can take a minute while the model loads.
     */
    suspend fun ask(message: String, history: List<Pair<String, String>>): OpsResult<String> {
        val body = buildJsonObject {
            put("message", message)
            put("history", buildJsonArray {
                history.takeLast(6).forEach { (role, text) -> add(buildJsonObject { put("role", role); put("content", text) }) }
            })
        }
        return actor.post("/caretaker/api/chat", body, timeoutMs = 180_000L).flatMap { reply ->
            val o = reply.json as? JsonObject
            val text = o?.nonBlankStr("reply")
            if (text != null) OpsResult.Ok(text)
            else OpsResult.Err(OpsError(o?.nonBlankStr("error") ?: "The caretaker sent no reply.", status = reply.status, kind = OpsErrorKind.BadShape))
        }
    }
}

/** Success only if the service reports the state we asked for: a 200 alone proves nothing. */
internal fun interpretCaretakerPause(reply: HubReply, wantPaused: Boolean): ActionOutcome {
    val o = reply.json as? JsonObject ?: return notAnObject(reply, "Pause the caretaker")
    val now = o.bool("paused")
    return if (now == wantPaused) {
        ActionOutcome.Succeeded(
            headline = if (wantPaused) "Caretaker paused" else "Caretaker resumed",
            rawBody = capBody(reply.rawBody),
            status = reply.status,
        )
    } else {
        ActionOutcome.Failed(
            headline = "The caretaker did not change state",
            detail = "Asked for paused=$wantPaused, the service answered paused=${now ?: "(missing)"}.",
            rawBody = capBody(reply.rawBody),
            status = reply.status,
        )
    }
}
