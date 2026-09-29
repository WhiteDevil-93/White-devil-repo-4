package com.whitedevil.desktop.ops

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * LTX 2.5 LoRA pack, from hub/setup.py. Routes relied on:
 *   GET  /api/setup   setup.py:107-110  merge(): {saved, count, enabled, lightricks, nsfw, target, loras[], dest}
 *   POST /api/setup   setup.py:117-128  body {"enabled": [ids]|null}; writes ~/.forge_setup.json on the relay
 * Not used: GET /api/setup/download.sh (a script for the laptop to run).
 *
 * `enabled` is a COUNT, and it is 0 before the first save BY DESIGN (setup.py:83-92): the hub
 * refuses to claim a setup that never happened. So enabled=0 with saved=false means "not yet
 * saved", not "nothing configured" — the catalog values (count, lightricks, nsfw) are still real.
 * Note also that an empty selection is silently turned into "all" by the hub (setup.py:123-124),
 * which is why the screen never offers to save zero.
 */

data class SetupLora(
    val id: String,
    val name: String?,
    val kind: String?,
    val pack: String?,
    val filename: String?,
    val enabled: Boolean,
)

sealed interface SetupPhase {
    /** The hub has no saved selection. The catalog is real; the "enabled" count is meaningless yet. */
    data object NotYetSaved : SetupPhase

    data class Saved(val enabled: Int?, val of: Int?) : SetupPhase
}

data class SetupState(
    /** The hub's `saved` flag; null from an older hub that does not send it. */
    val savedFlag: Boolean?,
    val count: Int?,
    val enabledCount: Int?,
    val lightricks: Int?,
    val nsfw: Int?,
    val target: String?,
    val dest: String?,
    val loras: List<SetupLora>,
) {
    /**
     * Saved when the hub says so; without the flag, saved only if some LoRAs are on.
     * enabled=0 alone is therefore [SetupPhase.NotYetSaved] — never "unconfigured".
     */
    val phase: SetupPhase
        get() = if (savedFlag ?: ((enabledCount ?: 0) > 0)) SetupPhase.Saved(enabledCount, count ?: loras.size.takeIf { it > 0 })
        else SetupPhase.NotYetSaved

    val enabledIds: Set<String> get() = loras.filter { it.enabled }.map { it.id }.toSet()

    companion object {
        private val EXPECTED = listOf("saved", "count", "enabled", "loras", "lightricks", "nsfw")

        fun parse(json: JsonElement): OpsResult<SetupState> {
            val o = json as? JsonObject ?: return shapeError("Setup", "a JSON object", json)
            if (EXPECTED.none { it in o }) {
                return OpsResult.Err(
                    OpsError(
                        "The hub's Setup reply has none of the expected fields (${EXPECTED.joinToString(", ")}); it sent: ${o.keys.take(12).joinToString(", ").ifEmpty { "an empty object" }}.",
                        kind = OpsErrorKind.BadShape, body = capBody(o.toString()),
                    ),
                )
            }
            return OpsResult.Ok(
                SetupState(
                    savedFlag = o.bool("saved"),
                    count = o.int("count"),
                    enabledCount = o.int("enabled"),
                    lightricks = o.int("lightricks"),
                    nsfw = o.int("nsfw"),
                    target = o.nonBlankStr("target"),
                    dest = o.nonBlankStr("dest"),
                    loras = (o["loras"] as? JsonArray).objects().mapNotNull { l ->
                        val id = l.nonBlankStr("id") ?: return@mapNotNull null
                        SetupLora(
                            id = id, name = l.nonBlankStr("name"), kind = l.nonBlankStr("kind"),
                            pack = l.nonBlankStr("pack"), filename = l.nonBlankStr("filename"),
                            enabled = l.bool("enabled") == true,
                        )
                    },
                ),
            )
        }
    }
}

/** Reads only. */
class SetupApi(private val reader: OpsReader) {
    suspend fun state(): OpsResult<SetupState> =
        reader.getJson(PATH, 30_000L).flatMap { SetupState.parse(it) }

    companion object {
        const val PATH = "/api/setup"
    }
}

/** The one write. Only ever run from behind [ActionController.confirm]. */
class SetupActions(private val actor: OpsActor) {
    /**
     * Saves exactly [ids]. Also returns the state the hub reported back so the screen can adopt it
     * (a save is only "saved" if the hub's own reply says so — see [interpretSetupSave]).
     */
    suspend fun save(ids: Set<String>): Pair<ActionOutcome, SetupState?> {
        if (ids.isEmpty()) {
            return localRefusal("Refusing to save an empty selection: the hub would silently turn it into all LoRAs.") to null
        }
        val body = buildJsonObject {
            put("enabled", JsonArray(ids.sorted().map { JsonPrimitive(it) }))
        }
        return when (val r = actor.post(PATH, body, timeoutMs = 45_000L)) {
            is OpsResult.Err -> r.error.toFailedOutcome() to null
            is OpsResult.Ok -> interpretSetupSave(r.value, ids)
        }
    }

    companion object {
        const val PATH = "/api/setup"
    }
}

/**
 * A save counts only if the hub's own reply says saved=true and reports exactly the ids
 * we asked for. The hub drops unknown ids silently (setup.py:122), so a count mismatch
 * is reported, not hidden.
 */
internal fun interpretSetupSave(reply: HubReply, requested: Set<String>): Pair<ActionOutcome, SetupState?> {
    val json = reply.json ?: return notAnObject(reply, "Save LoRA selection") to null
    val state = when (val p = SetupState.parse(json)) {
        is OpsResult.Ok -> p.value
        is OpsResult.Err -> return ActionOutcome.Failed(
            headline = "The hub answered HTTP ${reply.status} but its reply could not be read (${p.error.message}), so the save is unconfirmed.",
            rawBody = capBody(reply.rawBody), status = reply.status, mayHaveExecuted = true,
        ) to null
    }
    val raw = capBody(reply.rawBody)
    val savedOk = state.savedFlag == true
    val got = state.enabledIds
    val matches = got == requested
    if (savedOk && matches) {
        return ActionOutcome.Succeeded(
            headline = "The hub confirms the selection is saved: ${got.size} of ${state.count ?: state.loras.size} LoRAs enabled.",
            detail = state.dest?.let { "Downloads go to $it when the laptop runs the download script; saving downloads nothing." },
            rawBody = raw, status = reply.status,
        ) to state
    }
    val parts = listOf(
        OutcomePart("Hub reports saved", savedOk, if (savedOk) null else "saved=${state.savedFlag}"),
        OutcomePart(
            "Hub kept exactly the ${requested.size} requested", matches,
            if (matches) null else "hub reports ${got.size} enabled; missing ${(requested - got).sorted().joinToString().ifEmpty { "none" }}, unexpected ${(got - requested).sorted().joinToString().ifEmpty { "none" }}",
        ),
    )
    return ActionOutcome.Failed(
        headline = "The hub answered HTTP ${reply.status} but its reply does not confirm the selection you asked for.",
        parts = parts, rawBody = raw, status = reply.status,
    ) to state
}
