package com.whitedevil.desktop

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * The small slice of Markdown a chat model actually emits, rendered inline.
 *
 * Not a Markdown implementation — no links, tables or nesting. Models reply with
 * **bold**, `code`, *italics* and "- " bullets, and showing those as literal
 * asterisks makes every answer look broken. Anything it does not recognise is
 * passed through unchanged rather than swallowed, so no text is ever lost to a
 * parsing miss.
 */
fun renderMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    source.lineSequence().forEachIndexed { index, rawLine ->
        if (index > 0) append('\n')

        var line = rawLine
        // Headings: drop the hashes, keep the text bold.
        val heading = Regex("""^(#{1,6})\s+(.*)$""").matchEntire(line.trim())
        if (heading != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(heading.groupValues[2]) }
            return@forEachIndexed
        }
        // Bullets: a real bullet glyph reads better than a hyphen.
        val bullet = Regex("""^(\s*)[-*+]\s+(.*)$""").matchEntire(line)
        if (bullet != null) {
            append(bullet.groupValues[1])
            append("• ")
            appendInline(bullet.groupValues[2])
            return@forEachIndexed
        }
        appendInline(line)
    }
}

/** Inline spans within one line: `code`, **bold**, *italic*. */
private fun AnnotatedString.Builder.appendInline(text: String) {
    // Ordered longest-delimiter-first so ** is not consumed by the * rule.
    val pattern = Regex("""`([^`]+)`|\*\*([^*]+)\*\*|__([^_]+)__|\*([^*]+)\*|_([^_]+)_""")
    var cursor = 0
    for (m in pattern.findAll(text)) {
        if (m.range.first > cursor) append(text.substring(cursor, m.range.first))
        val code = m.groupValues[1]
        val bold = m.groupValues[2].ifEmpty { m.groupValues[3] }
        val italic = m.groupValues[4].ifEmpty { m.groupValues[5] }
        when {
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
        }
        cursor = m.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}
