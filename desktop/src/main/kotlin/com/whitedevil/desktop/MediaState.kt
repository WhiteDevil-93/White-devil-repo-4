package com.whitedevil.desktop

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the library panel is showing. Three different things that must never be
 * confused: still loading, failed (with a reason), and genuinely empty.
 * A failure rendered as "no renders" is the bug this type exists to prevent, so
 * [Empty] is only ever produced from a successful, fully readable, empty answer.
 */
sealed interface LibraryUiState {
    data object Loading : LibraryUiState

    data class Error(val error: MediaError) : LibraryUiState

    /** The hub answered successfully and has no clips. */
    data object Empty : LibraryUiState

    data class Loaded(val groups: List<MediaGroup>, val warnings: List<String>) : LibraryUiState {
        val clipCount: Int get() = groups.sumOf { it.clips.size }
    }
}

/** Single place where a library result becomes UI state. */
fun libraryStateFrom(result: MediaResult<ParsedLibrary>): LibraryUiState = when (result) {
    is MediaResult.Failure -> LibraryUiState.Error(result.error)
    is MediaResult.Ok -> {
        val lib = result.value
        when {
            lib.clipCount > 0 -> LibraryUiState.Loaded(lib.groups, lib.warnings)
            // Entries existed but none could be read: that is a parse failure, not an empty library.
            lib.warnings.isNotEmpty() -> LibraryUiState.Error(
                MediaError(
                    MediaErrorKind.BadResponse,
                    "The hub's library response could not be read.",
                    null,
                    lib.warnings.joinToString("; "),
                ),
            )
            else -> LibraryUiState.Empty
        }
    }
}

/** One clip in the gallery grid, with a key that is unique even if the hub repeats a name. */
data class GalleryItem(val key: String, val group: MediaGroup, val clip: MediaClip)

data class GallerySection(val headerKey: String, val group: MediaGroup, val items: List<GalleryItem>)

/**
 * Lazy lists crash on duplicate keys, and nothing guarantees the hub never repeats
 * a group id or clip name, so keys carry an occurrence counter.
 */
fun buildGallerySections(groups: List<MediaGroup>): List<GallerySection> {
    val seen = HashMap<String, Int>()
    fun unique(base: String): String {
        val n = seen.merge(base, 1, Int::plus) ?: 1
        return if (n == 1) base else "$base#$n"
    }
    return groups.map { g ->
        val gk = unique("g\u0000${g.id}")
        GallerySection(
            headerKey = gk,
            group = g,
            items = g.clips.map { c -> GalleryItem(unique("$gk\u0000c\u0000${c.name}"), g, c) },
        )
    }
}

// ---- formatting (pure, unit-tested) ----

/**
 * "5m ago" style age of a timestamp taken on the hub's clock. Null (the hub did not
 * send one) is "unknown", never a fabricated epoch-zero age.
 */
fun formatAge(epochSeconds: Double?, nowMs: Long): String {
    if (epochSeconds == null || epochSeconds.isNaN() || epochSeconds.isInfinite()) return "age unknown"
    val deltaSec = (nowMs / 1000.0) - epochSeconds
    if (deltaSec < -300) return "timestamp in the future (clock skew?)"
    val s = deltaSec.coerceAtLeast(0.0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${(s / 60).toLong()}m ago"
        s < 86_400 -> "${(s / 3600).toLong()}h ago"
        s < 86_400 * 30 -> "${(s / 86_400).toLong()}d ago"
        s < 86_400 * 365 -> "${(s / (86_400 * 30)).toLong()}mo ago"
        else -> "${(s / (86_400 * 365)).toLong()}y ago"
    }
}

fun formatAbsolute(epochSeconds: Double?, zone: ZoneId = ZoneId.systemDefault()): String {
    if (epochSeconds == null || epochSeconds.isNaN() || epochSeconds.isInfinite()) return "unknown time"
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(zone)
        .format(Instant.ofEpochMilli((epochSeconds * 1000).toLong()))
}

fun formatMb(mb: Double?): String = when {
    mb == null || mb.isNaN() -> "size unknown"
    mb >= 1000 -> String.format(Locale.ROOT, "%.1f GB", mb / 1000)
    else -> String.format(Locale.ROOT, "%.1f MB", mb)
}

/** Known kinds get a proper label; anything else the hub sends is shown as it came. */
fun kindLabel(kind: String?): String? = when (kind) {
    null, "" -> null
    "pack" -> "Pack"
    "chain" -> "Chain"
    "keeper" -> "Keeper"
    "test" -> "Test"
    else -> kind
}
