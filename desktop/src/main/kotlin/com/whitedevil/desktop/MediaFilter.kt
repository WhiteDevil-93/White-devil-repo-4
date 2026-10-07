package com.whitedevil.desktop

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Search, filter, sort and group for the Renders and Gallery screens. Pure functions: the screens
 * only render what comes out, so the rules here are unit-tested without a window.
 *
 * Only fields the hub really sends are used: clip name, size, mtime, source, and the group's title
 * and kind. The filenames carry resolution, frame count and seed (`..._1280x704_f81_s28.mp4`), so
 * searching the name finds those too.
 */
enum class DateRange(val label: String, val days: Int?) {
    Any("Any time", null), Today("Today", 0), Week("7 days", 7), Month("30 days", 30),
}

enum class SortKey(val label: String) { Newest("Newest"), Oldest("Oldest"), Largest("Largest"), Name("Name") }

enum class ViewMode(val label: String) { Timeline("By date"), Projects("By project") }

data class MediaFilter(
    val query: String = "",
    val source: String? = null,
    val range: DateRange = DateRange.Any,
    val keepersOnly: Boolean = false,
    /** The hub's "Tests & experiments" pile is hidden unless asked for; a search always reaches it. */
    val showTests: Boolean = false,
    val sort: SortKey = SortKey.Newest,
    val view: ViewMode = ViewMode.Timeline,
) {
    val searching: Boolean get() = query.isNotBlank()
    val isDefault: Boolean
        get() = copy(sort = SortKey.Newest, view = ViewMode.Timeline) == MediaFilter()
}

/** One headed block of results: a day bucket in the timeline, or a project in the project view. */
data class ViewSection(val key: String, val title: String, val source: String?, val items: List<GalleryItem>)

data class FilterCounts(val total: Int, val sources: List<Pair<String, Int>>, val tests: Int, val keepers: Int)

private fun isTest(i: GalleryItem) = i.group.kind == "test"
private fun isKeeper(i: GalleryItem) = i.group.kind == "keeper"

/** Terms are ANDed; a leading `-` excludes. Matches clip name, project title, kind and source. */
internal fun matchesQuery(item: GalleryItem, query: String): Boolean {
    if (query.isBlank()) return true
    val hay = buildString {
        append(item.clip.name).append(' ').append(item.group.title).append(' ')
        append(kindLabel(item.group.kind) ?: "").append(' ')
        append(item.clip.source ?: item.group.source ?: "")
    }.lowercase(Locale.ROOT)
    return query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }.all { term ->
        if (term.startsWith("-") && term.length > 1) term.substring(1) !in hay else term in hay
    }
}

private fun sourceOf(i: GalleryItem) = i.clip.source ?: i.group.source

internal fun inRange(mtime: Double?, range: DateRange, nowMs: Long, zone: ZoneId): Boolean {
    val days = range.days ?: return true
    if (mtime == null || mtime.isNaN()) return false // an undated clip cannot be "from today"
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val d = Instant.ofEpochMilli((mtime * 1000).toLong()).atZone(zone).toLocalDate()
    return !d.isAfter(today) && !d.isBefore(today.minusDays(days.toLong()))
}

fun filterItems(all: List<GalleryItem>, f: MediaFilter, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<GalleryItem> {
    val kept = all.filter { i ->
        (f.showTests || f.searching || !isTest(i)) &&
            (!f.keepersOnly || isKeeper(i)) &&
            (f.source == null || sourceOf(i) == f.source) &&
            inRange(i.clip.mtime, f.range, nowMs, zone) &&
            matchesQuery(i, f.query)
    }
    // Null times and sizes sort last in either direction rather than posing as zero.
    return when (f.sort) {
        SortKey.Newest -> kept.sortedWith(compareByDescending<GalleryItem> { it.clip.mtime != null }.thenByDescending { it.clip.mtime ?: 0.0 })
        SortKey.Oldest -> kept.sortedWith(compareByDescending<GalleryItem> { it.clip.mtime != null }.thenBy { it.clip.mtime ?: 0.0 })
        SortKey.Largest -> kept.sortedWith(compareByDescending<GalleryItem> { it.clip.mb != null }.thenByDescending { it.clip.mb ?: 0.0 })
        SortKey.Name -> kept.sortedBy { it.clip.name.lowercase(Locale.ROOT) }
    }
}

/**
 * Chip counts. The source counts are "faceted": they count what is reachable under every OTHER active
 * filter (tests hidden, date range, search), and ignore the source selection itself. Counting the raw
 * library made a chip read "ltx 682" while selecting it showed nothing, because every ltx clip was in
 * the hidden test pile.
 */
fun filterCounts(all: List<GalleryItem>, f: MediaFilter = MediaFilter(), nowMs: Long = 0L, zone: ZoneId = ZoneId.systemDefault()): FilterCounts = FilterCounts(
    total = all.size,
    sources = filterItems(all, f.copy(source = null), nowMs, zone).mapNotNull { sourceOf(it) }
        .groupingBy { it }.eachCount().toList().sortedByDescending { it.second },
    tests = all.count(::isTest),
    keepers = all.count(::isKeeper),
)

private val MONTH_FMT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

/** Day bucket for a timestamp: Today, Yesterday, This week, This month, then one bucket per month. */
internal fun bucketOf(mtime: Double?, nowMs: Long, zone: ZoneId): String {
    if (mtime == null || mtime.isNaN()) return "Undated"
    val today: LocalDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val d: LocalDate = Instant.ofEpochMilli((mtime * 1000).toLong()).atZone(zone).toLocalDate()
    return when {
        d.isAfter(today) -> "Today" // small clock skew between hub and laptop
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        !d.isBefore(today.minusDays(6)) -> "This week"
        d.year == today.year && d.month == today.month -> "This month"
        else -> d.format(MONTH_FMT)
    }
}

fun buildSections(items: List<GalleryItem>, f: MediaFilter, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<ViewSection> {
    if (items.isEmpty()) return emptyList()
    return when (f.view) {
        ViewMode.Projects -> {
            // Keep the order the sort produced for projects' first appearance.
            val order = LinkedHashMap<String, MutableList<GalleryItem>>()
            items.forEach { order.getOrPut(it.group.id + "\u0000" + it.group.title) { mutableListOf() }.add(it) }
            order.entries.mapIndexed { n, (_, list) -> ViewSection("p$n:${list.first().group.id}", list.first().group.title, list.first().group.source, list) }
        }
        ViewMode.Timeline ->
            // Only a time sort makes day buckets meaningful; size/name sorts are one flat list.
            if (f.sort == SortKey.Newest || f.sort == SortKey.Oldest) {
                val order = LinkedHashMap<String, MutableList<GalleryItem>>()
                items.forEach { order.getOrPut(bucketOf(it.clip.mtime, nowMs, zone)) { mutableListOf() }.add(it) }
                order.entries.map { (title, list) -> ViewSection("t:$title", title, null, list) }
            } else {
                listOf(ViewSection("t:all", "Sorted by ${f.sort.label.lowercase(Locale.ROOT)}", null, items))
            }
    }
}
