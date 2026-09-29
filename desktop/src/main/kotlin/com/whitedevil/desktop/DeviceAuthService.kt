package com.whitedevil.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * A device token and when it stops being good. Held in memory only: the token is a
 * bearer credential valid for hours, and writing it to settings.json next to the
 * relay password would widen exposure for the sake of skipping one Hello prompt.
 * The cost is signing in again after a restart, which is a button press.
 */
class TokenCache(
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Treat a token as expired this long before the hub does, so a request never races the deadline. */
    private val skewMs: Long = 60_000,
) {
    class Entry(val deviceId: String, val token: String, val expiresAtMs: Long) {
        override fun toString() = "TokenCache.Entry(deviceId=$deviceId, expiresAtMs=$expiresAtMs)"
    }

    @Volatile
    private var entry: Entry? = null

    fun put(deviceId: String, token: String, expiresInSeconds: Long) {
        entry = Entry(deviceId, token, nowMs() + expiresInSeconds * 1000)
    }

    /** The cached entry for [deviceId], or null if there is none, it is for another device, or it has (nearly) expired. */
    fun valid(deviceId: String): Entry? =
        entry?.takeIf { it.deviceId == deviceId && nowMs() < it.expiresAtMs - skewMs }

    fun clear() {
        entry = null
    }
}

/** What an enrolment or sign-in attempt came to. [message] is always fit to show the user. */
data class DeviceResult(
    val ok: Boolean,
    val message: String,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val expiresAtMs: Long? = null,
    /** True when the only obstacle is an existing key that would be destroyed; call again with replaceExisting. */
    val needsReplaceConfirmation: Boolean = false,
)

/**
 * Enrol and sign-in flows for the desktop's Windows Hello device key.
 *
 * ADDITIVE, per the migration: relay basic auth is still sent everywhere else, so
 * every failure here ends in [CARRY_ON] and nothing hard-fails. And because `create`
 * and `sign` prompt the user, they are reached only through [enrol] and [signIn],
 * which the UI calls only from a button — never from startup or a loop.
 *
 * Nothing here logs. Tokens, signatures, nonces and keys are not interpolated into
 * any message; the types that carry them redact themselves in toString().
 */
class DeviceAuthService(
    private val hello: HelloBackend,
    private val tokens: TokenCache = TokenCache(),
) {
    /** Serialises actions: two overlapping Hello prompts would confuse the user and each other. */
    private val gate = Mutex()

    /**
     * Public key made by a `create` whose hub call then failed. Retrying reuses it
     * instead of creating (and prompting for) a second key. Public, not secret.
     */
    @Volatile
    private var pendingPem: String? = null

    /** Cached token for [deviceId], if still valid. */
    fun cachedToken(deviceId: String): TokenCache.Entry? = tokens.valid(deviceId)

    /** Read-only probe; does not prompt. */
    suspend fun status(): Result<HelloStatus> = withContext(Dispatchers.IO) { hello.status() }

    /**
     * Makes a Hello key and registers its public half with the hub.
     *
     * `create` REPLACES any existing WhiteDevil key, which would strand a device
     * already enrolled with the old one — so if a key exists this refuses, and
     * says so, until the caller passes [replaceExisting] after asking the user.
     */
    suspend fun enrol(
        hub: HubAuthClient,
        name: String,
        enrolCode: String?,
        replaceExisting: Boolean,
    ): DeviceResult {
        if (!gate.tryLock()) return busy()
        try {
            val deviceName = name.trim()
            if (deviceName.isEmpty()) return fail("Give this device a name first.")
            if (deviceName.length > MAX_NAME) return fail("Device name is limited to $MAX_NAME characters.")

            val probe = status().getOrElse { return fail(it.message) }
            if (!probe.available) return fail(probe.detail.ifBlank { "Windows Hello is not set up on this account." })

            var pem = pendingPem
            if (pem == null) {
                if (probe.keyExists && !replaceExisting) {
                    return DeviceResult(
                        ok = false,
                        needsReplaceConfirmation = true,
                        message = "A WhiteDevil key already exists on this account. Enrolling again replaces it, and a device " +
                            "already enrolled with the old key could no longer sign in. Confirm to replace it.",
                    )
                }
                pem = withContext(Dispatchers.IO) { hello.createKey() }.getOrElse { return fail(it.message) }
                pendingPem = pem
            }

            val enrolled = try {
                hub.enrol(deviceName, pem, enrolCode)
            } catch (e: HubAuthException) {
                // pendingPem is kept so the retry does not re-prompt for a new key —
                // except when the hub already has this exact key.
                if (e.kind == HubAuthException.Kind.AlreadyEnrolled) pendingPem = null
                return fail(enrolFailure(e))
            }
            pendingPem = null
            tokens.clear()
            return DeviceResult(
                ok = true,
                deviceId = enrolled.id,
                deviceName = enrolled.name,
                message = "Enrolled as \"${enrolled.name}\". Use Sign in to get a device token.",
            )
        } finally {
            gate.unlock()
        }
    }

    /**
     * Proves possession of the device key to the hub and caches the token it issues.
     *
     * The challenge is single-use and consumed by the hub whether or not verification
     * succeeds, so a failure is never retried with the same nonce here: the user
     * presses the button again and gets a fresh one.
     */
    suspend fun signIn(hub: HubAuthClient, deviceId: String): DeviceResult {
        if (!gate.tryLock()) return busy()
        try {
            if (deviceId.isBlank()) return fail("This device is not enrolled yet.")

            // Checked first, without prompting and before touching the hub, so a
            // missing helper or key never burns a challenge or a rate-limit slot.
            val probe = status().getOrElse { return fail(it.message) }
            if (!probe.available) return fail(probe.detail.ifBlank { "Windows Hello is not set up on this account." })
            if (!probe.keyExists) return fail("There is no WhiteDevil key on this account. Enrol this device again.")

            val nonce = try {
                hub.challenge(deviceId).nonce
            } catch (e: HubAuthException) {
                return fail(signInFailure(e))
            }

            val signature = withContext(Dispatchers.IO) { hello.sign(nonce) }
                .getOrElse { return fail("${it.message} The hub's challenge expires unused; press Sign in again for a fresh one.") }

            val issued = try {
                hub.token(deviceId, signature)
            } catch (e: HubAuthException) {
                return fail(signInFailure(e))
            }
            if (issued.token.isBlank() || issued.expiresIn <= 0) return fail("The hub issued an unusable token.")

            tokens.put(deviceId, issued.token, issued.expiresIn)
            val expires = tokens.valid(deviceId)?.expiresAtMs
            return DeviceResult(
                ok = true,
                deviceId = deviceId,
                expiresAtMs = expires,
                message = "Signed in. The device token is held in memory for this session.",
            )
        } finally {
            gate.unlock()
        }
    }

    /**
     * Asks the hub whether it accepts the cached token. Soft by design: while Caddy
     * still enforces basic auth, a bearer-only request is turned away at the proxy
     * before the hub sees it, and that is expected — not a broken sign-in.
     */
    suspend fun verify(hub: HubAuthClient, deviceId: String): DeviceResult {
        val cached = tokens.valid(deviceId)
            ?: return fail("No valid device token — sign in first.", carryOn = false)
        return try {
            val who = hub.whoami(cached.token)
            if (who.authenticated) DeviceResult(true, "The hub confirmed this device token${who.name?.let { " for \"$it\"" } ?: ""}.", deviceId = who.deviceId)
            else fail("The hub does not recognise the cached token. Sign in again.")
        } catch (e: HubAuthException) {
            when (e.kind) {
                HubAuthException.Kind.Unauthorized ->
                    fail(
                        "The hub side did not confirm the token (HTTP ${e.status}). While the relay's Caddy still enforces basic auth " +
                            "this is expected: it rejects a bearer-only request before the hub sees it.",
                    )
                else -> fail(e.message)
            }
        }
    }

    /** Forget the token and any half-finished enrolment. Does not touch the hub or the Windows key. */
    fun forgetLocal() {
        tokens.clear()
        pendingPem = null
    }

    // ---- messages ---------------------------------------------------------------

    private fun enrolFailure(e: HubAuthException): String = when (e.kind) {
        HubAuthException.Kind.Unsupported ->
            "This hub does not have device auth (it predates it), so nothing was enrolled."
        HubAuthException.Kind.EnrolCode ->
            "${e.message} Issue a code from an already-trusted session (POST /api/auth/enrol-code), paste it above, and try again."
        HubAuthException.Kind.AlreadyEnrolled ->
            "The hub already has this key enrolled. If this device was enrolled before, use Sign in; otherwise revoke the old entry on the hub and enrol again."
        HubAuthException.Kind.Unauthorized ->
            "The hub refused the relay credentials, so nothing was enrolled. Check the relay user and password."
        HubAuthException.Kind.RateLimited -> rateLimited(e)
        else -> "The hub did not accept the enrolment: ${e.message} The key made in Windows Hello is kept; press Enrol to retry without a new prompt."
    }

    private fun signInFailure(e: HubAuthException): String = when (e.kind) {
        HubAuthException.Kind.UnknownDevice ->
            "The hub does not know this device id; it may have been revoked. Forget the device here and enrol again."
        HubAuthException.Kind.Unsupported ->
            "This hub does not have device auth (it predates it), so there is nothing to sign in to."
        HubAuthException.Kind.Unauthorized ->
            if (e.message.orEmpty().contains("signature", ignoreCase = true))
                "The hub says the signature does not match the key it has for this device. The Windows key may have been replaced; forget the device and enrol again."
            else "The hub refused the relay credentials. Check the relay user and password."
        HubAuthException.Kind.RateLimited -> rateLimited(e)
        else -> e.message.orEmpty()
    }

    private fun rateLimited(e: HubAuthException): String =
        "The hub is rate limiting this device${e.retryAfterSeconds?.let { "; try again in ${it}s" } ?: ""}."

    private fun busy() = fail("Another device-key action is already in progress.", carryOn = false)

    private fun fail(message: String?, carryOn: Boolean = true): DeviceResult {
        val base = message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
        return DeviceResult(ok = false, message = if (carryOn) "$base $CARRY_ON" else base)
    }

    companion object {
        const val MAX_NAME = 80
        const val CARRY_ON = "The app carries on with the relay password exactly as before."
    }
}
