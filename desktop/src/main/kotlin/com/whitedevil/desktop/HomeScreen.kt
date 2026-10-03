package com.whitedevil.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Home, laid out like the Forge Hub design: welcome line, a row of stat tiles, then the latest
 * clips. Every number is computed from the hub's library response. The design's "render queue"
 * and "update available" cards are not shown because this app has no source for them, and a
 * placeholder figure on a dashboard reads as a fact.
 */
@Composable
fun HomeScreen(state: LibraryUiState, nowMs: Long, onOpen: (Screen) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp)) {
        Text("Welcome back", color = Forge.Fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("Here's what the render farm has been up to.", color = Forge.Mut, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))

        when (state) {
            is LibraryUiState.Loaded -> Loaded(state, nowMs, onOpen)
            is LibraryUiState.Error -> Notice("Couldn't load the library", state.error.message, Forge.Bad)
            else -> Notice("Loading the library…", null, Forge.Warn)
        }
    }
}

@Composable
private fun Loaded(state: LibraryUiState.Loaded, nowMs: Long, onOpen: (Screen) -> Unit) {
    val clips = state.groups.flatMap { g -> g.clips.map { g to it } }
        .sortedByDescending { it.second.mtime ?: 0.0 }
    val newest = clips.firstOrNull()
    val totalMb = state.groups.sumOf { it.totalMb ?: it.clips.sumOf { c -> c.mb ?: 0.0 } }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Tile("NEWEST CLIP", newest?.let { formatAge(it.second.mtime, nowMs) } ?: "none", newest?.second?.name.orEmpty(), Modifier.weight(1f))
        Tile("CLIP COUNT", "%,d".format(java.util.Locale.US, state.clipCount), "across ${state.groups.size} groups", Modifier.weight(1f))
        Tile("LIBRARY SIZE", if (totalMb >= 1024) "%.1f GB".format(java.util.Locale.US, totalMb / 1024) else "%.0f MB".format(java.util.Locale.US, totalMb), "on the hub", Modifier.weight(1f))
    }

    Spacer(Modifier.height(28.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Latest clips", color = Forge.Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("Open Gallery →", color = Forge.Acc, fontSize = 13.sp, modifier = Modifier.clickable { onOpen(Screen.Gallery) })
    }
    Spacer(Modifier.height(14.dp))
    if (clips.isEmpty()) {
        Notice("No clips yet", "Finished renders will show up here.", Forge.Mut)
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            clips.take(5).forEach { (_, c) -> ClipCard(c.name, formatAge(c.mtime, nowMs), Modifier.weight(1f)) }
            repeat(5 - clips.take(5).size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, sub: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(Forge.Panel)
            .border(1.dp, Forge.Line, RoundedCornerShape(10.dp)).padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(label, color = Forge.Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(10.dp))
        Text(value, color = Forge.Fg, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Text(sub, color = Forge.Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ClipCard(name: String, age: String, modifier: Modifier) {
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp)).background(Forge.Panel2)
                .border(1.dp, Forge.Line, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.PlayArrow, null, tint = Forge.Acc3, modifier = Modifier.height(36.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(name, color = Forge.Fg, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(age, color = Forge.Dim, fontSize = 12.sp)
    }
}

@Composable
private fun Notice(title: String, detail: String?, color: Color) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Forge.Panel)
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp)).padding(20.dp),
    ) {
        Text(title, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(detail, color = Forge.Mut, fontSize = 13.sp)
        }
    }
}
