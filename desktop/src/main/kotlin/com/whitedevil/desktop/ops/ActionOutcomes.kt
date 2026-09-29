package com.whitedevil.desktop.ops

import kotlinx.serialization.json.JsonObject

/**
 * Turning hub replies to *mutating* calls into [ActionOutcome]s.
 *
 * The rule everywhere: HTTP 2xx is not success. Several hub endpoints answer 200
 * with `ok:false` (Thunder submit after a failed re-queue, Colab stop while the
 * assignment is still billing, Colab job actions when the runner is down), and
 * Vast can answer 200 with `success:false`. Where the hub documents an `ok`
 * field we require `ok == true`; where the reply is a vendor pass-through with
 * no `ok`, we report "accepted", show the body, and never say "done".
 */

/** Maps a transport result through an interpreter; transport failures become [ActionOutcome.Failed]. */
internal fun OpsResult<HubReply>.toOutcome(interpret: (HubReply) -> ActionOutcome): ActionOutcome = when (this) {
    is OpsResult.Ok -> interpret(value)
    is OpsResult.Err -> error.toFailedOutcome()
}

internal fun OpsError.toFailedOutcome(): ActionOutcome.Failed = ActionOutcome.Failed(
    headline = message,
    detail = if (mayHaveExecuted) {
        "The hub may still have carried this out. Refresh and check the current state before trying again."
    } else null,
    rawBody = body,
    status = status,
    mayHaveExecuted = mayHaveExecuted,
)

/** A 2xx whose body was not a JSON object: the action may have run, we cannot tell. */
internal fun notAnObject(reply: HubReply, what: String): ActionOutcome.Failed = ActionOutcome.Failed(
    headline = "$what: the hub answered HTTP ${reply.status} but the reply was not a JSON object, so the outcome is unknown.",
    rawBody = capBody(reply.rawBody).ifBlank { "(empty body)" },
    status = reply.status,
    mayHaveExecuted = true,
)

/**
 * For vendor pass-through endpoints that carry no documented `ok`. Only an explicit `ok:false`
 * or `success:false` marks failure (Vast answers 200 with `success:false`); otherwise the request
 * is reported as *accepted*, never as done. A bare `error` field does not decide it either way,
 * since a runner may echo a job object that still carries an old error, but it is surfaced so it
 * is not lost.
 */
internal fun interpretGeneric(reply: HubReply, what: String): ActionOutcome {
    val json = reply.json
        ?: return notAnObject(reply, what)
    val obj = json as? JsonObject
    if (obj != null) {
        val ok = obj.bool("ok")
        val success = obj.bool("success")
        val error = obj.nonBlankStr("error")
        val msg = obj.nonBlankStr("msg") ?: obj.nonBlankStr("message") ?: obj.nonBlankStr("detail")
        if (ok == false || success == false) {
            return ActionOutcome.Failed(
                headline = "$what: the hub answered HTTP ${reply.status} but reported failure.",
                parts = listOf(OutcomePart(what, false, error ?: msg ?: "the reply carries a failure flag")),
                rawBody = capBody(reply.rawBody),
                status = reply.status,
            )
        }
        val explicit = ok == true || success == true
        val note = if (!explicit && error != null) " Its reply also has an error field (\"${error.take(120)}\"); read the response below." else ""
        return ActionOutcome.Succeeded(
            headline = if (explicit) "$what: the hub reports success (HTTP ${reply.status})."
            else "$what: the hub accepted the request (HTTP ${reply.status}); its reply has no explicit success flag, so check the state below.$note",
            detail = msg,
            rawBody = capBody(reply.rawBody),
            status = reply.status,
        )
    }
    return ActionOutcome.Succeeded(
        headline = "$what: the hub accepted the request (HTTP ${reply.status}); its reply has no success flag, so check the state below.",
        rawBody = capBody(reply.rawBody),
        status = reply.status,
    )
}
