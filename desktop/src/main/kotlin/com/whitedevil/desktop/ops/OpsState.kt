package com.whitedevil.desktop.ops

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What a panel is showing. Three states, and an error is never folded into an
 * empty [Loaded]: "the hub said there are no jobs" and "the hub did not answer"
 * look identical as an empty list, and only one of them is good news.
 */
sealed interface OpsState<out T> {
    data object Loading : OpsState<Nothing>
    data class Error(val message: String, val status: Int? = null) : OpsState<Nothing>
    data class Loaded<T>(val value: T) : OpsState<T>
}

/** A value with the wall-clock time it was read, for "stale since" labels. */
data class Stamped<T>(val value: T, val atMillis: Long)

/**
 * One read-only panel: loads through [load], one request at a time.
 *
 * Single-flight is the "requests must not pile up" rule made structural: a
 * refresh (button or poll) that arrives while another is in flight is dropped,
 * not queued (the one exception, [refresh]'s follow-up, coalesces into a single
 * extra load). [shouldPoll] additionally holds the automatic poll back while the
 * panel is showing an error, so a 401 or a dead hub is not hammered every 15s —
 * after a failure only the operator's explicit Retry loads again.
 */
class PanelState<T>(
    private val load: suspend () -> OpsResult<T>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var state: OpsState<T> by mutableStateOf(OpsState.Loading)
        private set

    /** Last successfully loaded value, kept so an error can show it as STALE beside the reason. */
    var lastGood: Stamped<T>? by mutableStateOf(null)
        private set

    var refreshing: Boolean by mutableStateOf(false)
        private set

    private val inFlight = AtomicBoolean(false)
    private val rerun = AtomicBoolean(false)

    /**
     * Loads once, unless a load is already in flight, in which case this returns
     * false and does nothing.
     *
     * [followUp] is for the read that must reflect a just-finished action: if a
     * load is in flight it may predate the action, so instead of being dropped
     * the request is remembered and one more load runs when the current one ends.
     * Any number of follow-ups collapse into that one, so requests still never stack.
     */
    suspend fun refresh(followUp: Boolean = false): Boolean {
        if (!inFlight.compareAndSet(false, true)) {
            if (followUp) rerun.set(true)
            return false
        }
        try {
            do {
                rerun.set(false)
                refreshing = true
                try {
                    loadOnce()
                } finally {
                    refreshing = false
                }
            } while (rerun.get())
        } finally {
            inFlight.set(false)
        }
        // A follow-up that arrived between the last check and releasing the flag.
        if (rerun.compareAndSet(true, false)) refresh()
        return true
    }

    private suspend fun loadOnce() {
        val result = try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OpsResult.Err(OpsError("Unexpected error reading the hub reply: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim(), kind = OpsErrorKind.BadShape))
        }
        when (result) {
            is OpsResult.Ok -> {
                lastGood = Stamped(result.value, clock())
                state = OpsState.Loaded(result.value)
            }
            is OpsResult.Err -> state = OpsState.Error(result.error.message, result.error.status)
        }
    }

    /** Adopt a value the hub just returned (e.g. from a confirmed save), without another request. */
    fun adopt(value: T) {
        lastGood = Stamped(value, clock())
        state = OpsState.Loaded(value)
    }

    /** Whether the bounded automatic poll may fire now. */
    fun shouldPoll(): Boolean = state !is OpsState.Error && !refreshing
}

/** How a confirmed action ended. Built by the interpreters in ActionOutcomes.kt. */
sealed interface ActionOutcome {
    val status: Int?

    data class Succeeded(
        val headline: String,
        val detail: String? = null,
        val rawBody: String? = null,
        override val status: Int? = null,
    ) : ActionOutcome

    data class Failed(
        val headline: String,
        /** Per-part results, so a partial failure shows which parts worked and which did not. */
        val parts: List<OutcomePart> = emptyList(),
        val detail: String? = null,
        val rawBody: String? = null,
        override val status: Int? = null,
        /** The hub may have done it anyway (timeout, garbled reply): check state before retrying. */
        val mayHaveExecuted: Boolean = false,
    ) : ActionOutcome
}

data class OutcomePart(val label: String, val ok: Boolean, val detail: String? = null)

/**
 * Everything the confirmation dialog needs, plus the one lambda that performs
 * the action. [run] is invoked from [ActionController.confirm] and nowhere else.
 *
 * [typedPhrase] non-null means the operator must type it exactly (used where the
 * action bills, stops billing, or destroys data). Null means a two-step: the
 * dialog names the action and its consequences and the operator presses the
 * confirm button.
 */
class ActionSpec(
    val title: String,
    val consequences: List<String>,
    val confirmLabel: String,
    val typedPhrase: String? = null,
    val danger: Boolean = true,
    val run: suspend () -> ActionOutcome,
)

sealed interface ActionPhase {
    data object Idle : ActionPhase
    data class Confirming(val spec: ActionSpec) : ActionPhase
    data class Running(val spec: ActionSpec) : ActionPhase
    data class Finished(val spec: ActionSpec, val outcome: ActionOutcome) : ActionPhase
}

/**
 * The confirmation gate. Nothing in the ops screens calls the hub's mutating
 * endpoints except through [confirm], which refuses to run unless the operator
 * has opened the dialog for that exact action and (where required) typed the
 * phrase. It also refuses a second run while one is in progress.
 */
class ActionController(private val onFinished: (ActionOutcome) -> Unit = {}) {
    var phase: ActionPhase by mutableStateOf(ActionPhase.Idle)
        private set

    var typed: String by mutableStateOf("")
        private set

    /** Opens the confirmation dialog. Ignored while an action is running. */
    fun request(spec: ActionSpec): Boolean {
        if (phase is ActionPhase.Running) return false
        typed = ""
        phase = ActionPhase.Confirming(spec)
        return true
    }

    fun updateTyped(text: String) {
        typed = text
    }

    fun canConfirm(): Boolean {
        val p = phase as? ActionPhase.Confirming ?: return false
        val phrase = p.spec.typedPhrase ?: return true
        return typed.trim() == phrase
    }

    /** Runs the action if, and only if, the gate is satisfied. Returns whether it ran. */
    suspend fun confirm(): Boolean {
        if (!canConfirm()) return false
        val spec = (phase as ActionPhase.Confirming).spec
        phase = ActionPhase.Running(spec) // synchronous: a second click sees Running and does nothing
        val outcome = try {
            spec.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionOutcome.Failed(
                headline = "The action failed unexpectedly: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim(),
                mayHaveExecuted = true,
            )
        }
        phase = ActionPhase.Finished(spec, outcome)
        onFinished(outcome)
        return true
    }

    /** Cancel or close. Not possible mid-run: the outcome must be seen. */
    fun dismiss() {
        if (phase is ActionPhase.Running) return
        typed = ""
        phase = ActionPhase.Idle
    }

    val busy: Boolean get() = phase !is ActionPhase.Idle
}
