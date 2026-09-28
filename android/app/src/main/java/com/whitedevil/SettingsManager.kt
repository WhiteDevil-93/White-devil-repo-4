package com.whitedevil

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SettingsManager {
    private const val PREFS_FILE = "whitedevil_secure_prefs"
    private const val FALLBACK_PREFS_FILE = "whitedevil_prefs"

    const val KEY_VENICE_API_KEY = "venice_api_key"
    const val KEY_VENICE_MODEL = "venice_model"
    const val KEY_VENICE_SYSTEM_PROMPT = "venice_system_prompt"
    const val KEY_VENICE_WEB_SEARCH = "venice_web_search"
    const val KEY_RELAY_URL = "relay_url"
    const val KEY_RELAY_USER = "relay_user"
    const val KEY_RELAY_PASS = "relay_pass"
    const val KEY_LAPTOP_USER = "laptop_user"
    const val KEY_LAPTOP_PASS = "laptop_pass"
    const val KEY_ONBOARDING_COMPLETE = "onboarding_complete_v1"

    const val DEFAULT_RELAY_URL = "https://84-12-112-249.sslip.io"
    const val DEFAULT_RELAY_USER = "anon3"
    const val DEFAULT_LAPTOP_USER = "laptop"
    const val DEFAULT_MODEL = "zai-org-glm-5-2"

    const val DEFAULT_SYSTEM_PROMPT =
        "You are WhiteDevil Venice Agent, an autonomous AI assistant with tools to inspect and modify local workspace files, and monitor and trigger remote Forge Hub and Wan2.2 video generation pipelines on the relay and laptop. Be concise and proactive."

    fun getPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            context.getSharedPreferences(FALLBACK_PREFS_FILE, Context.MODE_PRIVATE)
        }
    }
}
