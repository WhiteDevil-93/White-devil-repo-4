package com.whitedevil.desktop

/**
 * Readable titles for clips. The runners on the GPU boxes write names like
 * `smoke_bestfriends-night-v3_c01_wanbot.mp4` or `ltx_chain_duration-10-seconds_1fc9ca3b01a7.mp4`: a
 * `smoke_` prefix, a runner tag, a job id. The hub's grouping matches on those names (smoke_goon_p*,
 * smoke_..._wanbot), so the files keep them; the app shows `bestfriends night v3 · clip 1` and keeps the
 * raw name underneath, where search still matches it.
 */
fun prettyClipName(raw: String): String {
    var s = raw.trim()
    s = s.replace(Regex("(?i)\\.(mp4|mov|webm|mkv|gif)$"), "")
    s = s.replace(Regex("(?i)^smoke[_-]"), "").replace(Regex("(?i)^ltx[_-]chain[_-]"), "").replace(Regex("(?i)^ltx[_-]"), "")
    val words = mutableListOf<String>()
    val detail = mutableListOf<String>()
    for (t in s.split('_').filter { it.isNotEmpty() }) {
        val low = t.lowercase()
        when {
            low == "wanbot" || low == "14b" -> Unit                                  // which runner wrote it
            Regex("c\\d{1,3}").matches(low) -> detail += "clip ${low.drop(1).toInt()}"
            Regex("\\d{2,3}f").matches(low) -> low.dropLast(1).toIntOrNull()?.let { detail += LTX_LENGTH_LABEL[it] ?: "${it} frames" }
            Regex("[0-9a-f]{12}").matches(low) && low.any { it.isDigit() } && low.any { it in 'a'..'f' } -> Unit   // a job id
            low == "2x" -> detail += "sharpened 2×"
            Regex("f\\d{2,3}|s\\d{1,3}").matches(low) -> Unit                         // steps and frames baked into pack names
            Regex("\\d{3,4}x\\d{3,4}").matches(low) -> detail += t.replace('x', '×')
            Regex("(?i)seed\\d+").matches(t) -> detail += "seed ${t.drop(4)}"
            else -> words += t.replace('-', ' ')
        }
    }
    val title = words.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    val out = (listOf(title).filter { it.isNotEmpty() } + detail).joinToString(" · ")
    return out.ifEmpty { raw }
}

/** The hub calls its catch-all group "Tests & experiments". What is left in it after LTX renders are split out is unsorted. */
fun prettyGroupTitle(title: String, kind: String?): String = if (kind == "test") "Unsorted renders" else title
