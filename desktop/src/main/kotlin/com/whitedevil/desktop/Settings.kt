package com.whitedevil.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Desktop settings, stored per-user outside the repo.
 *
 * The Venice key and relay password live here in plaintext for now, exactly as
 * the Electron app kept them in its user-data folder. That is a known weakness,
 * not an oversight: the replacement is the device key in the Windows TPM
 * (desktop/hello-helper), which removes the need to hold the relay password at
 * all once Caddy is switched over to device tokens. Until that switch, basic
 * auth is still what the relay demands, so the password has to be reachable.
 */
@Serializable
data class Settings(
    val hubUrl: String = DEFAULT_HUB_URL,
    val relayUser: String = "anon3",
    val relayPass: String = "",
    val veniceApiKey: String = "",
    val model: String = "zai-org-glm-5-2",
    val enableWebSearch: Boolean = false,
    val deviceId: String = "",
    val deviceName: String = "",
) {
    companion object {
        const val DEFAULT_HUB_URL = "https://84-12-112-249.sslip.io"

        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        /** %LOCALAPPDATA%\WhiteDevil on Windows, ~/.whitedevil elsewhere. */
        val dir: File by lazy {
            val local = System.getenv("LOCALAPPDATA")
            if (!local.isNullOrBlank()) File(local, "WhiteDevil")
            else File(System.getProperty("user.home"), ".whitedevil")
        }

        private val file: File get() = File(dir, "settings.json")

        /** The agent's sandbox. ToolBox confines file tools to this directory. */
        val workspaceDir: File by lazy { File(dir, "workspace").apply { mkdirs() } }

        fun load(): Settings = runCatching {
            if (!file.isFile) Settings() else json.decodeFromString<Settings>(file.readText())
        }.getOrElse {
            // A corrupt settings file must not stop the app from starting; the
            // user can re-enter values, but cannot fix a window that never opens.
            Settings()
        }

        fun save(settings: Settings): Result<Unit> = runCatching {
            dir.mkdirs()
            val tmp = File(dir, "settings.json.tmp")
            tmp.writeText(json.encodeToString(serializer(), settings))
            if (!tmp.renameTo(file)) {
                // renameTo will not replace an existing file on Windows.
                file.delete()
                check(tmp.renameTo(file)) { "could not replace ${file.absolutePath}" }
            }
        }
    }

    /** Everything the agent needs before it can be started, or null when ready. */
    fun blockedReason(): String? = when {
        veniceApiKey.isBlank() -> "Add your Venice API key in Settings."
        hubUrl.isBlank() -> "Set the hub URL in Settings."
        else -> null
    }
}
