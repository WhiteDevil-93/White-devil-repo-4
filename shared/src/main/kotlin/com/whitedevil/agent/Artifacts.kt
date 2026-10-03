package com.whitedevil.agent

/** Finds something in a reply that can be shown as a live preview (an HTML page or an SVG image). */
object Artifacts {
    private val FENCE = Regex("```([A-Za-z0-9+_-]*)[ \\t]*\\n([\\s\\S]*?)```")
    private val HTML_HINT = Regex("<!doctype html|<html", RegexOption.IGNORE_CASE)
    private const val VIEWPORT = "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"

    /** The first previewable block, as a complete HTML document, or null if the reply has none. */
    fun previewable(markdown: String): String? {
        for (m in FENCE.findAll(markdown)) {
            val lang = m.groupValues[1].lowercase()
            val body = m.groupValues[2].trim()
            if (body.isEmpty()) continue
            when {
                lang == "html" || lang == "htm" || (lang.isEmpty() && HTML_HINT.containsMatchIn(body)) ->
                    return if (HTML_HINT.containsMatchIn(body)) body else "<!doctype html>$VIEWPORT$body"
                lang == "svg" || (lang == "xml" && body.startsWith("<svg")) ->
                    return "<!doctype html>$VIEWPORT<body style=\"margin:0;background:#fff\">$body</body>"
            }
        }
        return null
    }
}
