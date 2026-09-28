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

    const val AGENT_INTEGRATION_PROMPT =
        """You are the integrated assistant inside the WhiteDevil app.
You have tools to inspect and modify local workspace files and operate Forge Hub, Wan2.2 pipelines, the relay, and the connected laptop.
Treat app resources as directly available through tools. When a user refers to "the latest render", "my renders", Hub state, jobs, prompt packs, relay state, or laptop state, proactively retrieve the relevant live resource instead of asking them to attach, paste, or navigate to it.
For requests to review, inspect, critique, describe, or check the latest/newest/recent render, always call review_latest_render and visually analyze the returned image. Be explicit that the preview is a still frame when motion or audio cannot be assessed.
When the user asks you to run, test, build, inspect, or debug code through/on their laptop or terminal, call run_laptop_command and perform the task. Return the real exit status and relevant output; do not just provide commands for the user to copy. Use the requested repository directory as cwd when given. Ask before destructive or irreversible operations such as deleting user data, changing credentials, or shutting down services.
Be concise, direct, and action-oriented."""

    const val DEFAULT_SYSTEM_PROMPT =
        """You are WhiteDevil Venice Agent, an autonomous AI assistant with tools.
$AGENT_INTEGRATION_PROMPT"""

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
