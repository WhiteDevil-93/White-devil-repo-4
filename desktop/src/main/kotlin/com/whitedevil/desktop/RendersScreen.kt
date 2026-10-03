package com.whitedevil.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders: the hub's clip library, grouped the way the hub groups it (goon packs,
 * chains, keepers, tests), with each group's pipeline, size and age.
 *
 * Native list rendered from JSON. There is no status field in the hub's response,
 * so none is shown; what is shown is exactly what the hub sends: kind, source,
 * clip count, size and modification time.
 */
@Composable
fun RendersScreen(settings: Settings) {
    val client = rememberMediaClient(settings)
    val library = rememberLibrary(client)
    val nowMs by rememberNowMs()
    val expanded = remember(client) { mutableStateMapOf<String, Boolean>() }
    val state = library.state

    Column(Modifier.fillMaxSize()) {
        MediaTopBar(
            title = "Renders",
            subtitle = when (state) {
                is LibraryUiState.Loaded -> "${client.hubLabel} - ${state.groups.size} groups, ${state.clipCount} clips"
                else -> client.hubLabel
            },
            busy = state is LibraryUiState.Loading,
            onRefresh = library::reload,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state) {
                is LibraryUiState.Loading -> LoadingPanel("Loading renders from ${client.hubLabel}...")
                is LibraryUiState.Error -> ErrorPanel(state.error, onRetry = library::reload)
                is LibraryUiState.Empty -> EmptyPanel(
                    "The hub answered normally and its library has no renders.",
                    onRefresh = library::reload,
                )
                is LibraryUiState.Loaded -> RenderList(state, expanded, nowMs)
            }
        }
    }
}

@Composable
private fun RenderList(state: LibraryUiState.Loaded, expanded: MutableMap<String, Boolean>, nowMs: Long) {
    val sections = remember(state) { buildGallerySections(state.groups) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            // Capped and centred like the Agent screen: rows running the full width of a wide monitor are hard to scan.
            modifier = Modifier.fillMaxWidth().widthIn(max = 1100.dp).fillMaxHeight()
                .align(Alignment.TopCenter).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            if (state.warnings.isNotEmpty()) {
                item(key = "warnings") { WarningsBanner(state.warnings) }
            }
            sections.forEach { section ->
                val open = expanded[section.headerKey] == true
                item(key = section.headerKey) {
                    GroupHeader(
                        section.group,
                        open = open,
                        nowMs = nowMs,
                        onToggle = { expanded[section.headerKey] = !open },
                    )
                }
                if (open) {
                    items(section.items, key = { it.key }) { item -> ClipRow(item.group, item.clip, nowMs) }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: MediaGroup, open: Boolean, nowMs: Long, onToggle: () -> Unit) {
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
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(group.title, color = Forge.Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                kindLabel(group.kind)?.let { Tag(it) }
                group.source?.let { Tag(it, Forge.Acc) }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val total = group.totalMb?.let { " · ${formatMb(it)}" } ?: ""
            Text(
                "${group.clips.size} ${if (group.clips.size == 1) "clip" else "clips"}$total",
                color = Forge.Fg, fontSize = 13.sp,
            )
            Text("updated ${formatAge(group.updated, nowMs)}", color = Forge.Dim, fontSize = 12.sp)
            Text(if (open) "Hide clips" else "Show clips", color = Forge.Acc, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ClipRow(group: MediaGroup, clip: MediaClip, nowMs: Long) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp)
            .background(Forge.Well, RoundedCornerShape(8.dp))
            .border(1.dp, Forge.Line, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(clip.idx?.let { "#$it" } ?: "-", color = Forge.Dim, fontSize = 12.sp, modifier = Modifier.width(36.dp))
        Text(
            clip.name,
            color = Forge.Fg, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Only worth a tag when this clip came from a different pipeline than its group.
        clip.source?.takeIf { it != group.source }?.let { Tag(it, Forge.Acc) }
        Text(formatMb(clip.mb), color = Forge.Mut, fontSize = 12.sp)
        Text(formatAge(clip.mtime, nowMs), color = Forge.Dim, fontSize = 12.sp, modifier = Modifier.width(96.dp))
    }
}
