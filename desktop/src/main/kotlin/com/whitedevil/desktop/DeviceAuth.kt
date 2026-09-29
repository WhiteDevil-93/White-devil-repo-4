package com.whitedevil.desktop

import kotlinx.coroutines.CancellationException

/*
 * Enrol and sign-in flows for the Windows Hello device key.
 *
 * ADDITIVE. The relay's basic auth is still sent on every request (HubAuthClient),
 * and no flow here changes how anything else in the app authenticates. If the hub
 * has no device auth, or any step fails, the outcome is a message and the app
 * carries on exactly as it did before. Nothing here throws for a hub or helper problem.
 *
 * WINDOWS HELLO PROMPTS THE USER. `createKey` and `sign` are reached ONLY from
 * [DeviceAuth.enrol] and [DeviceAuth.signIn], which the UI calls ONLY from a button
 * press. There is no automatic retry anywhere: a 429 becomes a message, not a loop.
 */

sealed interface EnrolResult {
    data class Enrolled(val deviceId: String, val deviceName: String) : EnrolResult

    /**
     * Enrolment did not happen. [keyReplaced] is true when Windows Hello's key WAS
     * replaced before the failure (the hub step failed after `create` succeeded): any
     * earlier enrolment of this PC no longer works, because `create` replaces the key.
     */
    data class Failed(
        val message: String,
        val keyReplaced: Boolean = false,
        val rateLimited: Boolean = false,
        val retryAfterSeconds: Int? = null,
    ) : EnrolResult
}

sealed interface SignInResult {
    /** [validUntilMs] is when the cache stops trusting the token (expiry minus skew). */
    data class SignedIn(val validUntilMs: Long, val fromCache: Boolean) : SignInResult

    data class Failed(
        val message: String,
        val rateLimited: Boolean = false,
        val retryAfterSeconds: Int? = null,
        /** The hub does not know this device, or its key no longer matches: enrol again. */
        val reenrolNeeded: Boolean = false,
    ) : SignInResult
}

class DeviceAuth(
    private val helper: HelloHelper,
    private val hub: HubAuthClient,
    private val tokens: TokenCache,
) {
    /**
     * Enrol this PC: check everything that can be checked WITHOUT touching the Hello
     * key, and only then create the key and register its public half.
     *
     * The order matters because `create` replaces the existing key. Every refusal we can
     * predict (no Hello, hub without device auth, hub wants a code we were not given)
     * is therefore raised first, so a doomed enrolment neither prompts the user nor
     * destroys a working key.
     */
    suspend fun enrol(deviceName: String, enrolCode: String): EnrolResult = guarded(
        onError = { EnrolResult.Failed("Enrolment stopped by an unexpected error: ${it.describeForUser()}") },
    ) {
        helper.unavailableReason()?.let { return@guarded EnrolResult.Failed(it) }

        // 1. Windows Hello is usable on this account. (status never prompts)
        when (val status = helper.status()) {
            is HelperOutcome.Failure -> return@guarded EnrolResult.Failed(status.message)
            is HelperOutcome.Success ->
                if (!status.value.helloAvailable) {
                    return@guarded EnrolResult.Failed(
                        status.value.detail ?: "Windows Hello is not set up on this account. Add a PIN or fingerprint first.",
                    )
                }
        }

        // 2. The hub has device auth, and we know whether it wants a code.
        val requiresCode = when (val probe = probeHub()) {
            is HubAuthResult.Success -> probe.value
            is HubAuthError -> return@guarded EnrolResult.Failed(
                probe.userMessage(Stage.ENROL),
                rateLimited = probe is HubAuthError.RateLimited,
                retryAfterSeconds = (probe as? HubAuthError.RateLimited)?.retryAfterSeconds,
            )
        }
        val code = enrolCode.trim().ifEmpty { null }
        if (requiresCode && code == null) {
            return@guarded EnrolResult.Failed(
                "This hub requires a single-use enrolment code. Ask the hub operator for one " +
                    "(they mint it with POST /api/auth/enrol-code; it is good for 15 minutes), enter it above, then try again. " +
                    "Nothing was changed.",
            )
        }

        // 3. The Hello key. PROMPTS. From here on the key may have been replaced.
        val created = when (val outcome = helper.createKey()) {
            is HelperOutcome.Failure -> return@guarded EnrolResult.Failed(outcome.message)
            is HelperOutcome.Success -> outcome.value
        }

        // 4. Register its public half.
        val name = clampDeviceName(deviceName)
        return@guarded when (val r = hub.enrol(name, created.publicKeyPem, code)) {
            is HubAuthResult.Success -> {
                tokens.clear() // a token for any earlier enrolment of this PC is meaningless now
                EnrolResult.Enrolled(deviceId = r.value.id, deviceName = r.value.name.ifBlank { name })
            }
            is HubAuthError -> EnrolResult.Failed(
                message = r.userMessage(Stage.ENROL) +
                    " The Windows Hello key on this PC was replaced, so any earlier enrolment of this PC no longer works; enrol again.",
                keyReplaced = true,
                rateLimited = r is HubAuthError.RateLimited,
                retryAfterSeconds = (r as? HubAuthError.RateLimited)?.retryAfterSeconds,
            )
        }
    }

    /**
     * Prove this PC holds its enrolled key and cache the resulting token (memory only).
     *
     * A still-fresh cached token is returned without touching the hub or prompting,
     * unless [force]. Otherwise: challenge -> sign (PROMPTS) -> token. The challenge is
     * single use and the hub rate-limits it, so a failure at any step ends the attempt.
     */
    suspend fun signIn(deviceId: String, force: Boolean = false): SignInResult = guarded(
        onError = { SignInResult.Failed("Sign-in stopped by an unexpected error: ${it.describeForUser()}") },
    ) {
        if (deviceId.isBlank()) {
            return@guarded SignInResult.Failed("This PC is not enrolled yet. Enrol it first.")
        }
        val key = TokenCache.Key(hub.normalizedBaseUrl.orEmpty(), deviceId)
        if (!force) {
            tokens.validUntil(key)?.let { return@guarded SignInResult.SignedIn(it, fromCache = true) }
        }

        // Do not spend a challenge (and rate-limit budget) when there is no helper to answer it.
        helper.unavailableReason()?.let { return@guarded SignInResult.Failed(it) }

        val challenge = when (val r = hub.challenge(deviceId)) {
            is HubAuthResult.Success -> r.value
            is HubAuthError -> return@guarded r.toSignInFailure(Stage.CHALLENGE)
        }
        if (!NONCE_SHAPE.matches(challenge.nonce)) {
            // Never hand an unexpected string to a command line.
            return@guarded SignInResult.Failed("The hub sent a challenge in an unexpected format, so nothing was signed.")
        }

        val signature = when (val r = helper.sign(challenge.nonce)) {
            is HelperOutcome.Failure -> return@guarded SignInResult.Failed(r.message)
            is HelperOutcome.Success -> r.value
        }

        val token = when (val r = hub.token(deviceId, signature.signatureB64)) {
            is HubAuthResult.Success -> r.value
            is HubAuthError -> return@guarded r.toSignInFailure(Stage.TOKEN)
        }

        val validUntil = tokens.store(key, token.token, token.expiresIn)
            ?: return@guarded SignInResult.Failed("The hub issued a token that is already expired, so it was discarded.")
        SignInResult.SignedIn(validUntil, fromCache = false)
    }

    /** Sign out: forget the in-memory token. */
    fun signOut() = tokens.clear()

    /**
     * Is device auth here at all, and does it want an enrolment code?
     *
     * /config reports `require_enrol_code`. A hub that predates /config (but has
     * enrolment) answers 404 there, so fall back to a route every device-auth hub has.
     * Both 404 means no device auth: nothing has been prompted or changed.
     */
    private suspend fun probeHub(): HubAuthResult<Boolean> =
        when (val cfg = hub.config()) {
            is HubAuthResult.Success -> HubAuthResult.Success(cfg.value.requireEnrolCode)
            HubAuthError.NoDeviceAuth -> when (val devices = hub.probeDevices()) {
                is HubAuthResult.Success -> HubAuthResult.Success(false)
                is HubAuthError -> devices
            }
            is HubAuthError -> cfg
        }

    private fun HubAuthError.toSignInFailure(stage: Stage): SignInResult.Failed = SignInResult.Failed(
        message = userMessage(stage),
        rateLimited = this is HubAuthError.RateLimited,
        retryAfterSeconds = (this as? HubAuthError.RateLimited)?.retryAfterSeconds,
        reenrolNeeded = this is HubAuthError.UnknownDevice ||
            (stage == Stage.TOKEN && this is HubAuthError.Unauthorized),
    )

    /** Runs [block]; any surprise becomes a typed failure. Cancellation is never swallowed. */
    private suspend fun <R> guarded(onError: (Throwable) -> R, block: suspend () -> R): R = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e)
    }

    enum class Stage { ENROL, CHALLENGE, TOKEN }

    companion object {
        /** Hub nonces are token_urlsafe(32); allow the base64/url alphabet and nothing that needs shell quoting. */
        private val NONCE_SHAPE = Regex("^[A-Za-z0-9_\\-.~+/=]{1,512}$")

        /** hub/auth.py EnrolIn.name: min_length=1, max_length=80. */
        const val MAX_DEVICE_NAME = 80

        private fun Throwable.describeForUser(): String =
            HelloHelperOutput.clean(message ?: javaClass.simpleName)

        /** Wording for every hub failure, in one place. Includes the hub's own `detail` where it helps. */
        fun HubAuthError.userMessage(stage: Stage): String = when (this) {
            HubAuthError.NoDeviceAuth ->
                "This hub does not offer device authentication (it answered 'not found'), so nothing was changed. " +
                    "The app keeps using the relay password exactly as before."
            is HubAuthError.UnknownDevice ->
                "The hub does not know this device id (it was revoked, or the hub was reset). Enrol this PC again."
            is HubAuthError.Unauthorized ->
                if (stage == Stage.TOKEN) {
                    "The hub did not accept the signature${detail.suffix()} If the Windows Hello key was replaced since enrolment, enrol this PC again."
                } else {
                    "The relay rejected the user name or password${detail.suffix()} Check the Relay fields above."
                }
            is HubAuthError.Forbidden -> "The hub refused this${detail.suffix()}"
            is HubAuthError.Conflict -> "The hub says this key is already enrolled${detail.suffix()}"
            is HubAuthError.BadRequest -> "The hub rejected the request${detail.suffix()}"
            is HubAuthError.RateLimited -> {
                val wait = retryAfterSeconds?.let { "Try again in about $it seconds." } ?: "Try again in a minute or so."
                "The hub is rate limiting this device. $wait Nothing was retried automatically."
            }
            is HubAuthError.HttpError -> "The hub answered HTTP $status${detail.suffix()}"
            is HubAuthError.Network -> message
            is HubAuthError.BadConfig -> message
            is HubAuthError.BadResponse -> message
        }

        private fun String?.suffix(): String = if (isNullOrBlank()) "." else ": $this" + if (endsWith(".")) "" else "."

        /** Trimmed, at most 80 UTF-16 units (so at most 80 code points), never splitting a surrogate pair. */
        fun clampDeviceName(raw: String, fallback: String = defaultDeviceName()): String {
            var name = raw.trim().ifEmpty { fallback.trim() }
            if (name.length > MAX_DEVICE_NAME) {
                name = name.take(MAX_DEVICE_NAME)
                if (name.last().isHighSurrogate()) name = name.dropLast(1)
                name = name.trimEnd()
            }
            return name.ifEmpty { "WhiteDevil desktop" }
        }

        /** "WhiteDevil desktop (HOSTNAME)" where the host name is known. */
        fun defaultDeviceName(
            host: String? = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME"),
        ): String {
            val h = host?.trim().orEmpty()
            val base = if (h.isEmpty()) "WhiteDevil desktop" else "WhiteDevil desktop ($h)"
            return if (base.length <= MAX_DEVICE_NAME) base else base.take(MAX_DEVICE_NAME)
        }
    }
}

/**
 * How an [EnrolResult] changes what is persisted in settings.json, or null for "nothing".
 *
 *  - Enrolled: remember the id and name the hub gave us.
 *  - A failure after the Hello key was replaced, on a PC that had an enrolment: that
 *    enrolment is dead (its key is gone), so forget it instead of leaving a device id
 *    that can never sign in.
 */
fun settingsAfterEnrol(current: Settings, result: EnrolResult): Settings? = when (result) {
    is EnrolResult.Enrolled -> current.copy(deviceId = result.deviceId, deviceName = result.deviceName)
    is EnrolResult.Failed ->
        if (result.keyReplaced && current.deviceId.isNotBlank()) current.copy(deviceId = "", deviceName = "") else null
}

/**
 * Remembers a hub 429 so the buttons refuse to fire again until Retry-After has passed.
 * This is a guard against the user (or a stuck key) hammering a rate limiter, not a retry:
 * it never calls anything itself.
 */
class CooldownGate(private val clock: () -> Long = System::currentTimeMillis) {
    private var untilMs = 0L

    /** Block for [seconds] (or [DEFAULT_SECONDS] when the hub did not say). */
    @Synchronized
    fun start(seconds: Int?) {
        val s = (seconds ?: DEFAULT_SECONDS).coerceIn(1, MAX_SECONDS)
        untilMs = clock() + s * 1000L
    }

    /** Whole seconds left, rounded up; 0 when open. */
    @Synchronized
    fun remainingSeconds(): Int {
        val left = untilMs - clock()
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }

    @Synchronized
    fun clear() {
        untilMs = 0L
    }

    companion object {
        const val DEFAULT_SECONDS = 60
        const val MAX_SECONDS = 3600
    }
}
