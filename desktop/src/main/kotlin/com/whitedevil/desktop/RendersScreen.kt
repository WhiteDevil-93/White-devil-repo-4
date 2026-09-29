package com.whitedevil.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** One line of the flattened list: a group header, or a clip under an expanded group. */
private sealed interface RenderRow {
    val key: String

    data class Header(val group: MediaGroup, val expanded: Boolean) : RenderRow {
        override val key get() = "g:${group.id}"
    }

    data class Clip(val group: MediaGroup, val clip: MediaClip) : RenderRow {
        override val key get() = "c:${group.id}:${clip.name}"
    }
}

/**
 * Renders: every finished render on the hub, grouped into packs, chains, keepers and
 * tests, with how many clips each holds and how long ago each last changed.
 *
 * "Status" here is what the hub reports — the pipeline (Vast / LTX / Thunder) and
 * kind of each group. `/api/media/library` lists finished files only; it says nothing
 * about queued or running jobs, so none are shown.
 */
@Composable
fun RendersScreen(media: MediaData) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(media) { media.refresh() }

    var source by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    val groups = media.groups
    val now = remember(media.loadedAtMs, media.error) { System.currentTimeMillis() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Renders",
            summary = groups?.let { g -> "${g.sumOf { it.count }} clips · ${g.size} collections" } ?: "",
            loading = media.loading,
            onRefresh = { scope.launch { media.refresh() } },
        )

        media.error?.let { ErrorBanner("Could not load the render library.", detail = listOfNotNull(it, staleNote(media, now)).joinToString(" "), onRetry = { scope.launch { media.refresh() } }) }

        when {
            groups == null -> if (media.error == null) CenteredNote(if (media.loading) "Loading renders…" else "Nothing loaded yet.")
            groups.isEmpty() -> if (media.error == null) CenteredNote("The hub reports no renders yet.")
            else -> {
                val inSource = groups.filter { source == null || it.source == source }
                Filters(
                    source = source, onSource = { source = it; kind = null },
                    kinds = listOf(null, "pack", "chain", "keeper", "test").filter { k -> k == null || inSource.any { it.kind == k } },
                    kind = kind, onKind = { kind = it },
                    countOf = { k -> inSource.count { k == null || it.kind == k } },
                    query = query, onQuery = { query = it },
                )
                val shown = inSource.filter { (kind == null || it.kind == kind) && (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true)) }
                if (shown.isEmpty()) {
                    CenteredNote("No collection matches these filters (${groups.size} on the hub).")
                } else {
                    val rows = remember(shown, expanded) {
                        buildList {
                            for (g in shown) {
                                val open = g.id in expanded
                                add(RenderRow.Header(g, open))
                                // Newest clip first, as the web screen does.
                                if (open) g.clips.sortedByDescending { it.mtime }.forEach { add(RenderRow.Clip(g, it)) }
                            }
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                        items(rows, key = { it.key }) { row ->
                            when (row) {
                                is RenderRow.Header -> GroupHeader(row, now) {
                                    expanded = if (row.group.id in expanded) expanded - row.group.id else expanded + row.group.id
                                }
                                is RenderRow.Clip -> ClipRow(row.clip, now)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Filters(
    source: String?, onSource: (String?) -> Unit,
    kinds: List<String?>, kind: String?, onKind: (String?) -> Unit,
    countOf: (String?) -> Int,
    query: String, onQuery: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SOURCE_FILTERS.forEach { (id, label) ->
                FilterChip(selected = source == id, onClick = { onSource(id) }, label = { Text(label) })
            }
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                query, onQuery, singleLine = true, placeholder = { Text("Search collections") },
                modifier = Modifier.width(260.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            kinds.forEach { k ->
                FilterChip(
                    selected = kind == k,
                    onClick = { onKind(k) },
                    label = { Text("${k?.let(::kindLabel) ?: "All"} ${countOf(k)}") },
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(row: RenderRow.Header, nowMs: Long, onToggle: () -> Unit) {
    val g = row.group
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            Modifier.clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(g.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${sourceLabel(g.source)} · ${kindLabel(g.kind)} · ${g.count} clip${if (g.count == 1) "" else "s"} · updated ${formatAge(nowMs, g.updated)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(if (row.expanded) "Hide" else "Show", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ClipRow(c: MediaClip, nowMs: Long) {
    Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(c.idx?.let { "Clip $it" } ?: "—", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.width(72.dp))
        Text(
            c.name,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text("${c.mb} MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
        Text(formatAge(nowMs, c.mtime), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
    }
}
