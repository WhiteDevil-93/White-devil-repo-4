package com.whitedevil.desktop

import java.io.File

/**
 * Files you attach to a Venice message. Pictures are shrunk and sent as images (the model must support vision);
 * text and code files are put into the message as fenced blocks. Anything else is refused with a reason.
 */
data class Attachment(val name: String, val imageDataUrl: String? = null, val text: String? = null) {
    val isImage get() = imageDataUrl != null
}

object Attachments {
    const val MAX_FILES = 6
    const val MAX_TEXT_BYTES = 200_000L
    const val MAX_TOTAL_TEXT_CHARS = 300_000

    private val imageExt = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    private val textExt = setOf(
        "txt", "md", "json", "csv", "tsv", "log", "yaml", "yml", "toml", "ini", "cfg", "conf", "xml", "html", "css", "js", "ts",
        "kt", "kts", "java", "py", "ps1", "bat", "cmd", "sh", "sql", "gradle", "properties", "rs", "go", "c", "h", "cpp", "cs",
    )

    sealed interface Loaded {
        data class Ok(val attachment: Attachment) : Loaded
        data class Refused(val reason: String) : Loaded
    }

    fun load(file: File): Loaded {
        if (!file.isFile) return Loaded.Refused("${file.name}: not a file.")
        val ext = file.extension.lowercase()
        return when {
            ext in imageExt -> {
                val url = runCatching { shrinkToJpegDataUrl(file.readBytes()) }.getOrNull()
                    ?: return Loaded.Refused("${file.name}: could not read this picture.")
                Loaded.Ok(Attachment(file.name, imageDataUrl = url))
            }
            ext in textExt || (ext.isEmpty() && looksLikeText(file)) -> {
                if (file.length() > MAX_TEXT_BYTES) return Loaded.Refused("${file.name}: ${file.length() / 1000} KB is over the ${MAX_TEXT_BYTES / 1000} KB limit for a text file.")
                if (!looksLikeText(file)) return Loaded.Refused("${file.name}: this does not look like text.")
                Loaded.Ok(Attachment(file.name, text = file.readText(Charsets.UTF_8)))
            }
            ext == "pdf" || ext == "docx" || ext == "zip" -> Loaded.Refused("${file.name}: .$ext files cannot be read yet. Attach a picture or paste the text.")
            else -> Loaded.Refused("${file.name}: .$ext is not a supported type (pictures and text/code files are).")
        }
    }

    /** No NUL bytes in the first few KB. */
    private fun looksLikeText(f: File): Boolean = runCatching { f.inputStream().use { s -> s.readNBytes(4096).none { it == 0.toByte() } } }.getOrDefault(false)

    /** Adds [new] to [current] up to the file limit. Returns the new list and any refusals as one message. */
    fun addAll(current: List<Attachment>, files: List<File>): Pair<List<Attachment>, String?> {
        val out = current.toMutableList(); val problems = mutableListOf<String>()
        for (f in files) {
            if (out.size >= MAX_FILES) { problems += "Only $MAX_FILES files per message; skipped ${f.name}."; continue }
            when (val r = load(f)) {
                is Loaded.Ok -> out += r.attachment
                is Loaded.Refused -> problems += r.reason
            }
        }
        return out to problems.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private val fileBlock = Regex("\\[Attached file: ([^\\]\\n]+)\\]\\n```\\n[\\s\\S]*?\\n```")

    /** What the chat bubble shows for a message that carried files: just the file names, not their contents. */
    fun forDisplay(text: String): String = fileBlock.replace(text) { "\uD83D\uDCCE ${it.groupValues[1]}" }

    /** The message text with the text files folded in, and the picture data URLs to send alongside it. */
    fun compose(text: String, attachments: List<Attachment>): Pair<String, List<String>> {
        var budget = MAX_TOTAL_TEXT_CHARS
        val blocks = attachments.filter { it.text != null }.map { a ->
            val body = a.text!!.take(budget.coerceAtLeast(0)); budget -= body.length
            val cut = if (body.length < a.text.length) "\n[… cut: the rest of this file is not included]" else ""
            "[Attached file: ${a.name}]\n```\n$body$cut\n```"
        }
        val images = attachments.mapNotNull { it.imageDataUrl }
        val full = (listOf(text.trim()).filter { it.isNotEmpty() } + blocks).joinToString("\n\n")
        return full to images
    }
}
