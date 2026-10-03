package com.whitedevil.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders: the hub's clip library as a searchable timeline (newest first, grouped by day), or by
 * project. Search, source/date chips and sorting come from [MediaFilter]; the hub's pile of test
 * renders is hidden until asked for or searched.
 *
 * There is no status field in the hub's response, so none is shown; what is shown is exactly what
 * the hub sends: name, project, source, size and modification time.
 */
@Composable
fun RendersScreen(settings: Settings) {
    val client = rememberMediaClient(settings)
    val library = rememberLibrary(client)
    val nowMs by rememberNowMs()
    val expanded = remember(client) { mutableStateMapOf<String, Boolean>() }
    val actions = rememberClipActions(client)
    // Held here, above the load state, so a Refresh does not throw the user's search away.
    var filter by remember { mutableStateOf(MediaFilter()) }
    val state = library.state

    Column(Modifier.fillMaxSize()) {
        MediaTopBar(
            title = "Renders",
            subtitle = when (state) {
                is LibraryUiState.Loaded -> "${client.hubLabel} - ${state.groups.size} projects, ${state.clipCount} clips"
                else -> client.hubLabel
            },
            busy = state is LibraryUiState.Loading,
            onRefresh = library::reload,
        )
        when (state) {
            is LibraryUiState.Loading -> LoadingPanel("Loading renders from ${client.hubLabel}...")
            is LibraryUiState.Error -> ErrorPanel(state.error, onRetry = library::reload)
            is LibraryUiState.Empty -> EmptyPanel(
                "The hub answered normally and its library has no renders.",
                onRefresh = library::reload,
            )
            is LibraryUiState.Loaded -> RenderList(state, filter, { filter = it }, expanded, nowMs, actions)
        }
    }
}

@Composable
private fun RenderList(
    state: LibraryUiState.Loaded,
    filter: MediaFilter,
    onFilter: (MediaFilter) -> Unit,
    expanded: MutableMap<String, Boolean>,
    nowMs: Long,
    actions: ClipActions,
) {
    val all = remember(state) { buildGallerySections(state.groups).flatMap { it.items } }
    val counts = remember(all, filter, nowMs) { filterCounts(all, filter, nowMs) }
    val shownItems = remember(all, filter, nowMs) { filterItems(all, filter, nowMs) }
    val sections = remember(shownItems, filter, nowMs) { buildSections(shownItems, filter, nowMs) }

    MediaFilterBar(filter, onFilter, counts, shown = shownItems.size)
    Box(Modifier.fillMaxSize()) {
        if (sections.isEmpty()) {
            NoMatches(onReset = { onFilter(MediaFilter(sort = filter.sort, view = filter.view)) })
            return@Box
        }
        LazyColumn(
            // Capped and centred: rows running the full width of a wide monitor are hard to scan.
            modifier = Modifier.fillMaxWidth().widthIn(max = 1100.dp).fillMaxHeight()
                .align(Alignment.TopCenter).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            if (state.warnings.isNotEmpty()) {
                item(key = "warnings") { WarningsBanner(state.warnings) }
            }
            sections.forEach { section ->
                if (filter.view == ViewMode.Projects) {
                    // Searching opens everything it found; otherwise projects start collapsed.
                    val open = expanded[section.key] ?: filter.searching
                    item(key = "h:${section.key}") {
                        ProjectHeader(section, open) { expanded[section.key] = !open }
                    }
                    if (open) items(section.items, key = { it.key }) { ClipRow(it.group, it.clip, nowMs, showProject = false, actions = actions) }
                } else {
                    item(key = "h:${section.key}") { DayHeader(section.title, section.items.size) }
                    items(section.items, key = { it.key }) { ClipRow(it.group, it.clip, nowMs, showProject = true, actions = actions) }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), color = Forge.Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
        Spacer(Modifier.width(10.dp))
        Text("$count", color = Forge.Dim, fontSize = 11.sp)
    }
}

@Composable
internal fun NoMatches(onReset: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No clips match", color = Forge.Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Try fewer words, a wider date range, or turn on Show unsorted.", color = Forge.Mut, fontSize = 13.sp)
        TextButton(onClick = onReset) { Text("RESET FILTERS", color = Forge.Acc, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ProjectHeader(section: ViewSection, open: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(Forge.Panel)
            .border(1.dp, if (open) Forge.Acc2 else Forge.Line, shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(section.title, color = Forge.Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        section.source?.let { Tag(it, Forge.Acc); Spacer(Modifier.width(10.dp)) }
        val total = section.items.map { it.clip.mb }.let { l -> if (l.any { it == null }) null else l.sumOf { it ?: 0.0 } }
        Text(
            "${section.items.size} ${if (section.items.size == 1) "clip" else "clips"}" + (total?.let { " · ${formatMb(it)}" } ?: ""),
            color = Forge.Mut, fontSize = 13.sp,
        )
        Spacer(Modifier.width(14.dp))
        Text(if (open) "Hide" else "Show", color = Forge.Acc, fontSize = 12.sp)
    }
}

@Composable
private fun ClipRow(group: MediaGroup, clip: MediaClip, nowMs: Long, showProject: Boolean, actions: ClipActions) {
    Row(
        Modifier.fillMaxWidth()
            .background(Forge.Well, RoundedCornerShape(8.dp))
            .border(1.dp, Forge.Line, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(prettyClipName(clip.name), color = Forge.Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // the raw file name stays visible (and searchable) in small type under the readable title
            Text((if (showProject) group.title + "  ·  " else "") + clip.name, color = Forge.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Only worth a tag when this clip came from a different pipeline than its group.
        (clip.source ?: group.source)?.let { Tag(it, Forge.Acc) }
        Text(formatMb(clip.mb), color = Forge.Mut, fontSize = 12.sp)
        Text(formatAge(clip.mtime, nowMs), color = Forge.Dim, fontSize = 12.sp, modifier = Modifier.width(96.dp))
        ClipButtons(actions, clip.name)
    }
}
