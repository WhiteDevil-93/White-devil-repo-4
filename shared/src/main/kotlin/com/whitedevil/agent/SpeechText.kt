package com.whitedevil.agent

/** Turns a chat reply into something a text-to-speech engine can read without stumbling. */
object SpeechText {
    /** Drops code blocks and markdown punctuation; links keep their label; whitespace is collapsed. */
    fun clean(markdown: String): String {
        var s = markdown.replace("\r\n", "\n")
        s = s.replace(Regex("```[\\s\\S]*?```"), " (code block omitted) ")
        s = s.replace(Regex("`([^`]*)`"), "$1")                              // inline code: keep the words
        s = s.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), " ")                    // images
        s = s.replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")                  // [label](url) -> label
        s = s.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")                   // headings
        s = s.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")                        // bullets
        s = s.replace(Regex("(?m)^\\s*>\\s?"), "")                            // quotes
        s = s.replace(Regex("[*_~|]{1,3}"), "")                               // emphasis, strike, table bars
        s = s.replace(Regex("https?://\\S+"), " link ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** Splits [text] into pieces of at most [max] chars, preferring to break after a sentence or at a space. */
    fun chunks(text: String, max: Int = 3500): List<String> {
        require(max >= 100) { "max too small" }
        val out = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > max) {
            val window = rest.substring(0, max)
            var cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf("? "), window.lastIndexOf("! "), window.lastIndexOf("\n")) + 1
            if (cut < max / 2) cut = window.lastIndexOf(' ') + 1
            if (cut <= 0) cut = max
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
