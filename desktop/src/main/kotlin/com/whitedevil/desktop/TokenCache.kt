package com.whitedevil.desktop

/**
 * In-memory cache of the hub's device token.
 *
 * Deliberately NOT persisted. A token on disk is a bearer credential that
 * survives the process; held only in memory it dies with the app, and getting
 * a new one costs one Windows Hello prompt. The hub itself only stores token
 * hashes for the same reason (hub/auth.py `_load_state`).
 *
 * A token is trusted only until `expiry - skew`. The skew margin means a token
 * that would lapse mid-request is refreshed instead of used, so a caller never
 * gets handed a token that expires before it can spend it.
 *
 * The clock is injected (epoch milliseconds) so expiry is testable without
 * sleeping, and so tests can move time backwards as well as forwards.
 */
class TokenCache(
    private val clock: () -> Long = System::currentTimeMillis,
    private val skewMs: Long = DEFAULT_SKEW_MS,
) {
    /** A token is only meaningful for one hub and one enrolled device. */
    data class Key(val hubUrl: String, val deviceId: String)

    private class Entry(val token: String, val expiresAtMs: Long) {
        // Never let a log line or a debugger string render the credential.
        override fun toString() = "Entry(<redacted>, expiresAtMs=$expiresAtMs)"
    }

    private val entries = HashMap<Key, Entry>()

    /**
     * Remember [token] for [expiresInSeconds] (the hub's `expires_in`).
     *
     * Returns the moment this cache will stop handing the token out (expiry
     * minus skew), or null if it is already stale and so was not stored: a
     * hub that answers `expires_in: 0` must not leave a usable-looking token
     * behind.
     */
    @Synchronized
    fun store(key: Key, token: String, expiresInSeconds: Long): Long? {
        if (token.isBlank()) return null
        val clamped = expiresInSeconds.coerceIn(0L, MAX_LIFETIME_S)
        val expiresAt = clock() + clamped * 1000L
        val usableUntil = expiresAt - skewMs
        if (clock() >= usableUntil) {
            entries.remove(key)
            return null
        }
        entries[key] = Entry(token, expiresAt)
        return usableUntil
    }

    /** The token, or null when there is none or it is within the skew margin of expiry. */
    @Synchronized
    fun get(key: Key): String? {
        val entry = entries[key] ?: return null
        if (clock() >= entry.expiresAtMs - skewMs) {
            entries.remove(key)
            return null
        }
        return entry.token
    }

    /** When [get] will stop returning a token for [key], or null if it would return null now. */
    @Synchronized
    fun validUntil(key: Key): Long? {
        val entry = entries[key] ?: return null
        val usableUntil = entry.expiresAtMs - skewMs
        if (clock() >= usableUntil) {
            entries.remove(key)
            return null
        }
        return usableUntil
    }

    @Synchronized
    fun clear(key: Key) {
        entries.remove(key)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        /** Refresh two minutes early: comfortably more than a request plus clock drift. */
        const val DEFAULT_SKEW_MS = 120_000L

        /** A hub bug or hostile reply must not produce a token that lives for decades. */
        const val MAX_LIFETIME_S = 366L * 24 * 3600
    }
}

/**
 * The one process-wide cache. In-memory only; other screens can ask it for a
 * device token once something consumes one (nothing does today: the hub applies
 * `require_device` to no route yet, so sign-in currently proves the key works).
 */
object DeviceAuthSession {
    val tokens: TokenCache = TokenCache()
}
