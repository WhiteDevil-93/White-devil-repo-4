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

/** The hub's own marker (`source`) or the file name the LTX builder writes (ltx_...). */
fun isLtxClip(c: MediaClip): Boolean {
    val n = c.name.lowercase()
    return c.source == "ltx" || n.startsWith("ltx_") || n.startsWith("ltx-") || "ltx_chain" in n
}

/**
 * The hub files every clip that isn't part of a pack, chain or keeper set under one group, "Tests &
 * experiments". Your LTX renders land there too (710 of its 869 clips), so with tests hidden by default
 * they all vanished. LTX renders are real renders: they get their own "LTX 2.5 renders" project, and the
 * test group keeps only what is actually a test.
 */
fun splitLtx(groups: List<MediaGroup>): List<MediaGroup> = groups.flatMap { g ->
    if (g.kind != "test") return@flatMap listOf(g)
    val (ltx, rest) = g.clips.partition(::isLtxClip)
    if (ltx.isEmpty()) return@flatMap listOf(g)
    fun newest(cs: List<MediaClip>) = cs.mapNotNull { it.mtime }.maxOrNull()
    listOfNotNull(
        MediaGroup(id = "ltx-renders", title = "LTX 2.5 renders", kind = "ltx", source = "ltx", updated = newest(ltx), clips = ltx),
        // The hub labelled the whole catch-all group "ltx" because most of it is LTX; what is left is not, so it
        // keeps a source only if its own clips agree on one. Otherwise "ltx" would still match the real tests.
        rest.takeIf { it.isNotEmpty() }?.let { g.copy(clips = it, updated = newest(it) ?: g.updated, source = it.mapNotNull { c -> c.source }.distinct().singleOrNull()) },
    )
}

/**
 * Lazy lists crash on duplicate keys, and nothing guarantees the hub never repeats
 * a group id or clip name, so keys carry an occurrence counter.
 */
fun buildGallerySections(groups: List<MediaGroup>): List<GallerySection> {
    // Group titles are renamed for display here, the one place both Renders and Gallery build their sections.
    val groups = splitLtx(groups).map { g -> prettyGroupTitle(g.title, g.kind).let { t -> if (t == g.title) g else g.copy(title = t) } }
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
    "test" -> "Unsorted"
    "ltx" -> "LTX"
    else -> kind
}
