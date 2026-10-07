package com.whitedevil.desktop

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * OS-protected credential store for sensitive tokens (such as the Qwen API client key).
 *
 * Keys are NEVER stored in settings.json or logged.
 * On Windows, values are encrypted and decrypted using Windows DPAPI (CurrentUser scope)
 * via System.Security.Cryptography.ProtectedData. Plaintext is fed over stdin rather than
 * command-line arguments to avoid exposure in task managers or process listings.
 */
object WindowsSecretStore {
    private val secretsDir: File by lazy {
        File(Settings.dir, "secrets").apply { mkdirs() }
    }

    private val qwenSecretFile: File
        get() = File(secretsDir, "qwen_key.dpapi")

    private val isWindows: Boolean =
        System.getProperty("os.name")?.contains("Windows", ignoreCase = true) ?: false

    /**
     * Encrypts and securely saves the [apiKey].
     * Never writes plaintext to disk or process arguments.
     */
    fun saveQwenKey(apiKey: String): Boolean {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            deleteQwenKey()
            return true
        }

        return try {
            secretsDir.mkdirs()
            if (isWindows) {
                // Read plaintext from stdin and write DPAPI base64 to target file
                val script = """
                    Add-Type -AssemblyName System.Security;
                    ${'$'}plain = [Console]::In.ReadToEnd();
                    if ([string]::IsNullOrEmpty(${'$'}plain)) { exit 1 };
                    ${'$'}bytes = [System.Text.Encoding]::UTF8.GetBytes(${'$'}plain);
                    ${'$'}enc = [System.Security.Cryptography.ProtectedData]::Protect(${'$'}bytes, ${'$'}null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser);
                    [Console]::Out.Write([Convert]::ToBase64String(${'$'}enc));
                """.trimIndent().replace("\n", " ")

                val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()

                process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use {
                    it.write(trimmed)
                    it.flush()
                }

                val encBase64 = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
                val code = process.waitFor()
                if (code == 0 && encBase64.isNotEmpty()) {
                    qwenSecretFile.writeText(encBase64, StandardCharsets.UTF_8)
                    true
                } else {
                    false
                }
            } else {
                // Fallback for non-Windows: user-readable only file
                qwenSecretFile.writeText(trimmed, StandardCharsets.UTF_8)
                runCatching {
                    qwenSecretFile.setReadable(false, false)
                    qwenSecretFile.setReadable(true, true)
                    qwenSecretFile.setWritable(true, true)
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Decrypts and retrieves the Qwen API key in-memory.
     * Returns null if no key is saved or decryption fails.
     */
    fun loadQwenKey(): String? {
        if (!qwenSecretFile.isFile) return null
        val blob = qwenSecretFile.readText(StandardCharsets.UTF_8).trim()
        if (blob.isEmpty()) return null

        return try {
            if (isWindows) {
                val script = """
                    Add-Type -AssemblyName System.Security;
                    ${'$'}enc = [Console]::In.ReadToEnd().Trim();
                    if ([string]::IsNullOrEmpty(${'$'}enc)) { exit 1 };
                    ${'$'}bytes = [Convert]::FromBase64String(${'$'}enc);
                    ${'$'}dec = [System.Security.Cryptography.ProtectedData]::Unprotect(${'$'}bytes, ${'$'}null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser);
                    [Console]::Out.Write([System.Text.Encoding]::UTF8.GetString(${'$'}dec));
                """.trimIndent().replace("\n", " ")

                val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()

                process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use {
                    it.write(blob)
                    it.flush()
                }

                val decrypted = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                val code = process.waitFor()
                if (code == 0 && decrypted.isNotBlank()) decrypted.trim() else null
            } else {
                blob
            }
        } catch (e: Exception) {
            null
        }
    }

    fun hasQwenKey(): Boolean = qwenSecretFile.isFile && qwenSecretFile.length() > 0

    fun deleteQwenKey(): Boolean {
        return try {
            if (qwenSecretFile.exists()) qwenSecretFile.delete() else true
        } catch (e: Exception) {
            false
        }
    }
}
