package com.whitedevil.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** What a tile or the preview is showing. A failure is its own state, never a blank. */
private sealed interface ImageState {
    data object Loading : ImageState
    data class Ready(val bitmap: ImageBitmap) : ImageState
    data class Failed(val message: String) : ImageState
}

/**
 * Gallery: the newest clips across every collection as a thumbnail grid, with a
 * larger preview beside it.
 *
 * Thumbnails load only as their tile scrolls into view (a lazy grid composes just the
 * visible tiles, and leaving composition cancels an in-flight request), decode to
 * bitmaps held in a size-bounded cache, and a failure is drawn in the tile rather than
 * left as a blank one.
 */
@Composable
fun GalleryScreen(media: MediaData) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(media) { media.refresh() }

    var source by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<GalleryClip?>(null) }

    val groups = media.groups
    val now = remember(media.loadedAtMs, media.error) { System.currentTimeMillis() }
    val all = remember(groups) { media.allClips() }
    val shown = remember(all, source, query) {
        all.filter { (source == null || it.source == source) && (query.isBlank() || it.clip.name.contains(query.trim(), true) || it.groupTitle.contains(query.trim(), true)) }
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ScreenHeader(
                title = "Gallery",
                summary = if (groups != null) "${shown.size} of ${all.size} clips" else "",
                loading = media.loading,
                onRefresh = { scope.launch { media.refresh() } },
            )

            media.error?.let {
                ErrorBanner("Could not load the render library.", detail = listOfNotNull(it, staleNote(media, now)).joinToString(" "), onRetry = { scope.launch { media.refresh() } })
            }

            when {
                groups == null -> if (media.error == null) CenteredNote(if (media.loading) "Loading renders…" else "Nothing loaded yet.")
                all.isEmpty() -> if (media.error == null) CenteredNote("The hub reports no renders yet.")
                else -> {
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SOURCE_FILTERS.forEach { (id, label) ->
                            FilterChip(selected = source == id, onClick = { source = id }, label = { Text(label) })
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search clips") }, modifier = Modifier.width(260.dp))
                    }
                    if (shown.isEmpty()) {
                        CenteredNote("No clip matches these filters (${all.size} on the hub).")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 180.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(shown, key = { it.clip.name }) { item ->
                                ThumbTile(media, item, now, selected = selected?.clip?.name == item.clip.name) { selected = item }
                            }
                        }
                    }
                }
            }
        }

        selected?.let { item ->
            PreviewPanel(media, item, now, onClose = { selected = null })
        }
    }
}

@Composable
private fun ThumbTile(media: MediaData, item: GalleryClip, nowMs: Long, selected: Boolean, onClick: () -> Unit) {
    val name = item.clip.name
    // Keyed on media too: when settings change a new repository replaces the old one,
    // and a tile must not keep a result (or a failure) that belongs to the old hub.
    var attempt by remember(media, name) { mutableStateOf(0) }
    var state by remember(media, name) {
        mutableStateOf<ImageState>(media.cachedThumb(name)?.let { ImageState.Ready(it) } ?: ImageState.Loading)
    }

    // Runs when the tile enters composition (scrolls into view) and is cancelled
    // when it leaves, so scrolling past a tile abandons its request.
    LaunchedEffect(media, name, attempt) {
        if (state is ImageState.Ready) return@LaunchedEffect
        state = ImageState.Loading
        state = media.thumb(name).fold(
            onSuccess = { ImageState.Ready(it) },
            onFailure = { ImageState.Failed(it.message ?: "Could not load the thumbnail.") },
        )
    }

    Column(
        Modifier.clip(RoundedCornerShape(8.dp))
            .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable {
                if (state is ImageState.Failed) attempt++ // a click on a failed tile retries it
                onClick()
            },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF0C0A09)), contentAlignment = Alignment.Center) {
            when (val s = state) {
                is ImageState.Ready -> Image(s.bitmap, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                ImageState.Loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                is ImageState.Failed -> Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Thumbnail failed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("Click to retry", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(item.groupTitle, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(item.clip.idx?.let { "Clip $it" }, formatAge(nowMs, item.clip.mtime)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The larger view: the hub's contact sheet, a grid of frames sampled across the clip. */
@Composable
private fun PreviewPanel(media: MediaData, item: GalleryClip, nowMs: Long, onClose: () -> Unit) {
    val name = item.clip.name
    var attempt by remember(media, name) { mutableStateOf(0) }
    var state by remember(media, name) { mutableStateOf<ImageState>(ImageState.Loading) }

    LaunchedEffect(media, name, attempt) {
        state = ImageState.Loading
        state = media.contact(name).fold(
            onSuccess = { ImageState.Ready(it) },
            onFailure = { ImageState.Failed(it.message ?: "Could not load the contact sheet.") },
        )
    }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.width(520.dp).fillMaxHeight()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Preview", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(item.groupTitle, style = MaterialTheme.typography.titleSmall)
                Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${sourceLabel(item.source)} · ${item.clip.mb} MB · ${formatAge(nowMs, item.clip.mtime)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (val s = state) {
                    is ImageState.Ready -> Image(s.bitmap, contentDescription = "Contact sheet for $name", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)))
                    ImageState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Building the contact sheet on the hub — the first time can take a minute.", style = MaterialTheme.typography.bodySmall)
                    }
                    is ImageState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Could not load the preview.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { attempt++ }) { Text("Retry") }
                    }
                }
            }
        }
    }
}
