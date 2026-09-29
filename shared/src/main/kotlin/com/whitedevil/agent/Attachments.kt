package com.whitedevil.agent

import java.util.Locale

/** Pure helpers for the attachment pipeline. Android-free on purpose so unit tests can cover them. */
object Attachments {

    const val MAX_IMAGES_PER_MESSAGE = 4
    const val MAX_IMAGE_BASE64_BYTES = 5 * 1024 * 1024
    const val MAX_IMAGE_DIMENSION_PX = 1568
    const val IMAGE_JPEG_QUALITY = 85
    const val MAX_FILE_COPY_BYTES = 200L * 1024L * 1024L

    enum class Kind { IMAGE, VIDEO, AUDIO, DOCUMENT }

    fun kindOf(mime: String?): Kind {
        val m = (mime ?: "").lowercase()
        return when {
            m.startsWith("image/") -> Kind.IMAGE
            m.startsWith("video/") -> Kind.VIDEO
            m.startsWith("audio/") -> Kind.AUDIO
            else -> Kind.DOCUMENT
        }
    }

    fun extensionFor(mime: String?): String = when ((mime ?: "").lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        "video/3gpp" -> "3gp"
        "audio/mpeg" -> "mp3"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/ogg" -> "ogg"
        "audio/wav", "audio/x-wav" -> "wav"
        "application/pdf" -> "pdf"
        "text/plain" -> "txt"
        else -> "bin"
    }

    fun formatSize(bytes: Long): String {
        // Locale.ROOT, not the default: String.format follows the machine locale,
        // so on en_ZA (this machine) "%.1f" produced "2,0 KB". That string goes
        // into the tool result the model reads, so it must not vary by machine.
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${String.format(Locale.ROOT, "%.1f", kb)} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${String.format(Locale.ROOT, "%.1f", mb)} MB"
        return "${String.format(Locale.ROOT, "%.2f", mb / 1024.0)} GB"
    }

    /** Sanitizes a display name into a safe file name, preserving an extension when present. */
    fun safeFileName(raw: String?, fallback: String): String {
        val base = (raw ?: "").substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.')
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return fallback
        return cleaned.take(120)
    }

    /** Reference note appended to the message text for non-image files saved into the workspace. */
    fun workspaceNote(relativePath: String, mime: String?, bytes: Long): String =
        "[Attached ${kindOf(mime).name.lowercase()} saved to workspace $relativePath ($mime, ${formatSize(bytes)})]"
}
