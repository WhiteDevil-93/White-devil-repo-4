package com.whitedevil.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class WorkspaceTab(val label: String) { Pipeline("Pipeline"), Workspace("Files"), Shell("Remote shell"), Memory("Memory") }

/**
 * The left pane of the Venice screen (the UX Pilot "Venice Agent" design): what the studio is doing and what the agent
 * has done. Pipeline shows the hub's live state, the newest renders and a terminal transcript of every tool Venice ran
 * in this chat; Workspace lists the agent's laptop folder; Remote shell links to the real shell; Memory is the Hub's.
 */
@Composable
fun VeniceWorkspace(
    settings: Settings,
    lines: List<ChatLine>,
    busy: Boolean,
    library: LibraryUiState?,
    media: MediaClient?,
    onOpen: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(WorkspaceTab.Pipeline) }
    Box(modifier.background(Forge.Well)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(40.dp).background(Forge.Bg).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                WorkspaceTab.entries.forEach { t ->
                    val on = t == tab
                    Column(Modifier.fillMaxHeight().width(IntrinsicSize.Max).clickable { tab = t }.padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center) {
                        Spacer(Modifier.weight(1f))
                        Text(t.label.uppercase(), color = if (on) Forge.Acc else Forge.Mut, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) Forge.Acc else Color.Transparent))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Forge.Line))
            when (tab) {
                WorkspaceTab.Pipeline -> Pipeline(settings, lines, busy, library, media, onOpen)
                WorkspaceTab.Workspace -> WorkspaceFiles(busy)
                WorkspaceTab.Shell -> RemoteShell(lines, onOpen)
                WorkspaceTab.Memory -> MemoryContent(settings, Modifier.fillMaxSize().padding(24.dp))
            }
        }
        AgentIndicator(lines, busy, Modifier.align(Alignment.BottomStart).padding(24.dp))
    }
}

@Composable
private fun Pipeline(settings: Settings, lines: List<ChatLine>, busy: Boolean, library: LibraryUiState?, media: MediaClient?, onOpen: (Screen) -> Unit) {
    // Refreshed every 30 s while shown, and again whenever the agent finishes a run.
    val sitrep by produceState<HubSitrep?>(null, settings.hubUrl, settings.relayUser, settings.relayPass, busy) {
        while (true) {
            value = withContext(Dispatchers.IO) { SitrepClient(settings.hubUrl, settings.relayUser, settings.relayPass).use { it.load() } }
            delay(30_000)
        }
    }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).padding(bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("HUB SITREP", color = Forge.Acc3, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.4.sp, modifier = Modifier.weight(1f))
                Text(
                    sitrep?.let { "Last hub pulse: ${((now - it.fetchedAtMs) / 1000).coerceAtLeast(0)}s ago" } ?: "Asking the hub…",
                    color = if (sitrep == null) Forge.Dim else Forge.Ok, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                (sitrep?.tiles ?: listOf("Colab", "Thunder", "LTX queue", "Vast credit").map { TileState(it, "…", "", Tone.Quiet) })
                    .forEach { SitrepTile(it, Modifier.weight(1f)) }
            }
        }

        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("LATEST RENDERS", color = Forge.Mut, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, modifier = Modifier.weight(1f))
                Text("View all", color = Forge.Acc, fontSize = 11.sp, modifier = Modifier.clickable { onOpen(Screen.Renders) })
            }
            Spacer(Modifier.height(16.dp))
            val clips = (library as? LibraryUiState.Loaded)?.groups?.flatMap { it.clips }?.sortedByDescending { it.mtime ?: 0.0 }?.take(4).orEmpty()
            if (clips.isEmpty()) Tip(if (library is LibraryUiState.Loaded) "No renders yet." else "Loading the library…")
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                clips.forEach { c -> Frame(c.name, formatAge(c.mtime, now), media, Modifier.weight(1f)) }
                repeat(4 - clips.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Terminal("venice tools · this chat", terminalLines(lines), busy, Modifier.fillMaxWidth().height(300.dp))
    }
}

@Composable
private fun SitrepTile(t: TileState, modifier: Modifier) {
    val dot = when (t.tone) { Tone.Ok -> Forge.Ok; Tone.Warn -> Forge.Warn; Tone.Bad -> Forge.Bad; Tone.Quiet -> Forge.Control }
    val valueColor = when (t.tone) { Tone.Warn -> Forge.Warn; Tone.Bad -> Forge.Bad; else -> Forge.Fg }
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Forge.Panel).border(1.dp, Forge.Line, RoundedCornerShape(12.dp)).padding(16.dp)) {
        Text(t.label.uppercase(), color = Forge.Mut, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(t.value, color = valueColor, fontSize = 20.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
        if (t.sub.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(t.sub, color = Forge.Dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Frame(name: String, age: String, media: MediaClient?, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, name, media) {
        value = media?.let { m -> withContext(Dispatchers.IO) { (m.thumb(name) as? MediaResult.Ok)?.let { runCatching { decodeToBitmap(it.value) }.getOrNull() } } }
    }
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Forge.Panel2).border(1.dp, Forge.Line, RoundedCornerShape(8.dp))) {
        bmp?.let { Image(it, contentDescription = prettyClipName(name), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Text(
            prettyClipName(name) + " · " + age, color = Color.White, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

@Composable
internal fun Terminal(title: String, rows: List<TermLine>, busy: Boolean, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(rows.size) { if (rows.isNotEmpty()) state.scrollToItem(rows.lastIndex) }
    Column(modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Forge.Line, RoundedCornerShape(12.dp)).background(Color.Black)) {
        Row(Modifier.fillMaxWidth().height(32.dp).background(Forge.Panel).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Terminal, null, tint = Forge.Mut, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(8.dp))
            Text(title.uppercase(), color = Forge.Mut, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            listOf(Forge.Bad, Forge.Warn, Forge.Ok).forEach { Box(Modifier.padding(start = 6.dp).size(8.dp).clip(CircleShape).background(it)) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), state = state) {
            if (rows.isEmpty()) item { Text("# Nothing has run in this chat yet.", color = Forge.Mut, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            items(rows) { r ->
                Text(
                    r.text, fontSize = 11.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace,
                    color = when (r.kind) { TermKind.Cmd -> Forge.Ok; TermKind.Err -> Forge.Bad; TermKind.Out -> Forge.Dim },
                )
            }
            if (busy) item { Text("_", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
private fun AgentIndicator(lines: List<ChatLine>, busy: Boolean, modifier: Modifier) {
    val goal = currentGoal(lines, busy)
    val done = goal?.steps?.count { it.state != StepState.Running } ?: 0
    val total = goal?.steps?.size ?: 0
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(Forge.Bg.copy(alpha = 0.85f)).border(1.dp, Forge.Acc.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.SmartToy, null, tint = if (busy) Forge.Acc else Forge.Control, modifier = Modifier.size(16.dp))
        Text(if (busy) "AGENT ACTIVE" else "AGENT IDLE", color = Forge.Acc3, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.6.sp)
        Box(Modifier.width(1.dp).height(16.dp).background(Forge.Line))
        Text(if (total == 0) "NO STEPS YET" else "STEPS $done/$total", color = Forge.Mut, fontSize = 9.sp)
        if (busy) LinearProgressIndicator(Modifier.width(96.dp).height(4.dp), color = Forge.Acc, trackColor = Forge.Line)
        else LinearProgressIndicator({ if (total == 0) 0f else done / total.toFloat() }, Modifier.width(96.dp).height(4.dp), color = Forge.Acc, trackColor = Forge.Line)
    }
}

@Composable
private fun WorkspaceFiles(busy: Boolean) {
    val dir = Settings.workspaceDir
    val files by produceState<List<File>>(emptyList(), busy) {
        value = withContext(Dispatchers.IO) { dir.listFiles().orEmpty().sortedByDescending { it.lastModified() } }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AGENT WORKSPACE", color = Forge.Acc3, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.4.sp, modifier = Modifier.weight(1f))
            SmallButton("OPEN FOLDER", false) { runCatching { dir.mkdirs(); java.awt.Desktop.getDesktop().open(dir) } }
        }
        Tip("Venice reads and writes files only inside ${dir.absolutePath}.")
        if (files.isEmpty()) Tip("Empty for now.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(files) { f ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Forge.Panel).border(1.dp, Forge.Line, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.Folder, null, tint = if (f.isDirectory) Forge.Acc3 else Forge.Control, modifier = Modifier.size(14.dp))
                    Text(f.name, color = Forge.Fg, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (f.isDirectory) "folder" else sizeLabel(f.length()), color = Forge.Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

internal fun sizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(Locale.US, bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(Locale.US, bytes / (1L shl 20).toDouble())
    bytes >= 1024 -> "%.0f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun RemoteShell(lines: List<ChatLine>, onOpen: (Screen) -> Unit) {
    val laptop = lines.filterIndexed { i, l ->
        (l.role == ROLE_TOOL_CALL && l.title.endsWith("run_laptop_command")) ||
            (l.role == ROLE_TOOL_OUT && lines.getOrNull(i - 1)?.title?.endsWith("run_laptop_command") == true)
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LAPTOP COMMANDS", color = Forge.Acc3, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.4.sp, modifier = Modifier.weight(1f))
            SmallButton("OPEN SHELL", true) { onOpen(Screen.Terminal) }
        }
        Tip("Commands Venice ran on this laptop in this chat. The live, interactive terminal is the Shell screen.")
        Terminal("run_laptop_command · this chat", terminalLines(laptop, outLines = 12), false, Modifier.fillMaxWidth().weight(1f))
    }
}
