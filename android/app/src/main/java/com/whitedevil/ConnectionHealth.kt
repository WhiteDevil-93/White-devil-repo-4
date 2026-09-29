package com.whitedevil

import java.net.HttpURLConnection
import java.net.URL

object ConnectionHealth {

    data class Snapshot(
        val veniceLine: String,
        val relayLine: String,
        val laptopLine: String,
    ) {
        fun multiline(): String = "Venice: $veniceLine\nRelay: $relayLine\nLaptop: $laptopLine"
    }

    fun evaluate(
        veniceKey: String,
        relayUrl: String,
        relayUser: String,
        relayPass: String,
        laptopUser: String,
        laptopPass: String,
    ): Snapshot {
        val veniceLine = if (veniceKey.isNotBlank()) "API key configured" else "Missing API key"
        val relayLine = probeRelay(relayUrl.trim().trimEnd('/'), relayUser, relayPass)
        val laptopLine = when {
            laptopUser.isBlank() -> "User not set"
            laptopPass.isBlank() -> "Password not set (HTTP auth may fail)"
            else -> "Credentials saved"
        }
        return Snapshot(veniceLine, relayLine, laptopLine)
    }

    private fun probeRelay(base: String, user: String, pass: String): String {
        if (base.isBlank()) return "URL not set"
        return try {
            val url = URL("$base/api/manifest")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 12_000
            conn.requestMethod = "GET"
            if (user.isNotEmpty()) {
                val token = android.util.Base64.encodeToString("$user:$pass".toByteArray(), android.util.Base64.NO_WRAP)
                conn.setRequestProperty("Authorization", "Basic $token")
            }
            when (val code = conn.responseCode) {
                in 200..299 -> "Online (manifest OK)"
                401 -> "Auth rejected — check relay user/password"
                else -> "HTTP $code"
            }
        } catch (e: Exception) {
            "Unreachable (${e.message ?: "network error"})"
        }
    }
}
