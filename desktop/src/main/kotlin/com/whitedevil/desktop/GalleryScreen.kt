package com.whitedevil.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Gallery: a grid of native thumbnails from the hub, a larger preview, and the
 * hub's contact sheet for the selected clip.
 *
 * Thumbnails are loaded lazily: a tile starts its request when the grid composes
 * it (scrolls into view) and the request is cancelled when it scrolls away. At most
 * four run at once. Decoded bitmaps live in a bounded LRU cache ([MediaCaches]).
 * A thumbnail that fails to load shows an error tile with the reason, never a
 * blank one, and is retried only when the user presses Retry.
 */
@Composable
fun GalleryScreen(settings: Settings) {
    val client = rememberMediaClient(settings)
    val library = rememberLibrary(client)
    val nowMs by rememberNowMs()
    val thumbs = remember(client) {
        ThumbLoader(MediaCaches.thumbs, fetch = { client.thumb(it) }, decode = ::decodeToBitmap, permits = 4)
    }
    // One contact sheet at a time: they cost the hub 12-24 ffmpeg seeks each.
    val contacts = remember(client) {
        ThumbLoader(MediaCaches.contactSheets, fetch = { client.contactSheet(it) }, decode = ::decodeToBitmap, permits = 1)
    }
    var selectedKey by remember(client) { mutableStateOf<String?>(null) }
    // Held above the load state so Refresh keeps the user's search.
    var filter by remember { mutableStateOf(MediaFilter()) }
    val state = library.state

    // Refresh is an explicit press, so it also forgets remembered thumbnail failures.
    val refresh = {
        thumbs.clearFailures()
        contacts.clearFailures()
        library.reload()
    }

    Column(Modifier.fillMaxSize()) {
        MediaTopBar(
            title = "Gallery",
            subtitle = when (state) {
                is LibraryUiState.Loaded -> "${client.hubLabel} - ${state.clipCount} clips"
                else -> client.hubLabel
            },
            busy = state is LibraryUiState.Loading,
            onRefresh = refresh,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state) {
                is LibraryUiState.Loading -> LoadingPanel("Loading library from ${client.hubLabel}...")
                is LibraryUiState.Error -> ErrorPanel(state.error, onRetry = refresh)
                is LibraryUiState.Empty -> EmptyPanel(
                    "The hub answered normally and its library has no clips to show.",
                    onRefresh = refresh,
                )
                is LibraryUiState.Loaded -> Column(Modifier.fillMaxSize()) {
                    val all = remember(state) { buildGallerySections(state.groups).flatMap { it.items } }
                    val counts = remember(all, filter, nowMs) { filterCounts(all, filter, nowMs) }
                    val shownItems = remember(all, filter, nowMs) { filterItems(all, filter, nowMs) }
                    val sections = remember(shownItems, filter, nowMs) { buildSections(shownItems, filter, nowMs) }
                    val selected = remember(shownItems, selectedKey) {
                        selectedKey?.let { k -> shownItems.firstOrNull { it.key == k } }
                    }
                    MediaFilterBar(filter, { filter = it }, counts, shown = shownItems.size)
                    if (sections.isEmpty()) {
                        NoMatches(onReset = { filter = MediaFilter(sort = filter.sort, view = filter.view) })
                        return@Column
                    }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 210.dp),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (state.warnings.isNotEmpty()) {
                                item(key = "warnings", span = { GridItemSpan(maxLineSpan) }) {
                                    WarningsBanner(state.warnings)
                                }
                            }
                            sections.forEach { section ->
                                item(key = "h:${section.key}", span = { GridItemSpan(maxLineSpan) }) {
                                    SectionHeader(section, timeline = filter.view == ViewMode.Timeline)
                                }
                                items(section.items, key = { it.key }) { item ->
                                    // Composing the tile starts its load; leaving composition cancels it.
                                    ThumbTile(
                                        item = item,
                                        cacheKey = mediaCacheKey(settings.hubUrl, item.clip),
                                        loader = thumbs,
                                        nowMs = nowMs,
                                        selected = item.key == selectedKey,
                                        onClick = { selectedKey = item.key },
                                    )
                                }
                            }
                        }
                        if (selected != null) {
                            VerticalDivider()
                            PreviewPane(
                                item = selected,
                                cacheKey = mediaCacheKey(settings.hubUrl, selected.clip),
                                thumbs = thumbs,
                                contacts = contacts,
                                nowMs = nowMs,
                                onClose = { selectedKey = null },
                                modifier = Modifier.width(460.dp).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(section: ViewSection, timeline: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (timeline) {
            Text(section.title.uppercase(), color = Forge.Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
        } else {
            Text(section.title, color = Forge.Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            section.source?.let { Spacer(Modifier.width(10.dp)); Tag(it, Forge.Acc) }
        }
        Spacer(Modifier.width(10.dp))
        Text("${section.items.size} ${if (section.items.size == 1) "clip" else "clips"}", color = Forge.Dim, fontSize = 11.sp)
    }
}

@Composable
private fun ThumbTile(
    item: GalleryItem,
    cacheKey: String,
    loader: ThumbLoader<ImageBitmap>,
    nowMs: Long,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tone = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(tone.surface)
            .then(if (selected) Modifier.border(2.dp, tone.primary, shape) else Modifier)
            .clickable(onClick = onClick),
    ) {
        var attempt by remember(cacheKey) { mutableIntStateOf(0) }
        val outcome by rememberBitmapOutcome(loader, cacheKey, item.clip.name, attempt)
        BitmapBox(
            outcome = outcome,
            description = item.clip.name,
            what = "Thumbnail",
            onRetry = { loader.retry(cacheKey); attempt++ },
            compact = true,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.clip.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatMb(item.clip.mb)} - ${formatAge(item.clip.mtime, nowMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = tone.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * Starts loading when this composable enters composition and is cancelled
 * automatically when it leaves (the produceState coroutine is the request).
 */
@Composable
private fun rememberBitmapOutcome(
    loader: ThumbLoader<ImageBitmap>,
    key: String,
    name: String,
    attempt: Int,
): State<ThumbOutcome<ImageBitmap>?> = produceState<ThumbOutcome<ImageBitmap>?>(
    initialValue = loader.cached(key)?.let { ThumbOutcome.Ready(it) }
        ?: loader.failure(key)?.let { ThumbOutcome.Failed(it) },
    key, attempt,
) {
    value = null
    value = loader.load(key, name)
}

/**
 * The three states of an image slot: loading (spinner), ready (image) and failed
 * (a tile that says why, with an explicit Retry). There is no blank state.
 */
@Composable
private fun BitmapBox(
    outcome: ThumbOutcome<ImageBitmap>?,
    description: String,
    what: String,
    onRetry: () -> Unit,
    compact: Boolean,
    modifier: Modifier,
) {
    val tone = MaterialTheme.colorScheme
    val failed = outcome is ThumbOutcome.Failed
    Box(
        modifier.background(if (failed) tone.error.copy(alpha = 0.12f) else tone.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (outcome) {
            null -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            is ThumbOutcome.Ready -> Image(
                bitmap = outcome.value,
                contentDescription = description,
                contentScale = if (compact) ContentScale.Fit else ContentScale.FillWidth,
                modifier = if (compact) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            )
            is ThumbOutcome.Failed -> if (compact) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("$what failed", style = MaterialTheme.typography.labelMedium, color = tone.error)
                    Text(
                        outcome.error.summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = tone.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.textButtonColors(contentColor = tone.secondary),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                    ) { Text("Retry", style = MaterialTheme.typography.labelSmall) }
                }
            } else {
                ErrorPanel(outcome.error, onRetry = onRetry, fillScreen = false)
            }
        }
    }
}

@Composable
private fun PreviewPane(
    item: GalleryItem,
    cacheKey: String,
    thumbs: ThumbLoader<ImageBitmap>,
    contacts: ThumbLoader<ImageBitmap>,
    nowMs: Long,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val tone = MaterialTheme.colorScheme
    val clip = item.clip
    Column(
        modifier.background(tone.surface).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Preview", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }
        SelectionContainer {
            Text(clip.name, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            kindLabel(item.group.kind)?.let { Tag(it) }
            (clip.source ?: item.group.source)?.let { Tag(it, tone.secondary) }
        }
        Text(item.group.title, style = MaterialTheme.typography.bodySmall, color = tone.onSurfaceVariant)
        Text(
            listOfNotNull(
                clip.idx?.let { "#$it" },
                formatMb(clip.mb),
                "${formatAbsolute(clip.mtime)} (${formatAge(clip.mtime, nowMs)})",
            ).joinToString(" - "),
            style = MaterialTheme.typography.bodySmall,
            color = tone.onSurfaceVariant,
        )

        // Larger view of the same thumbnail (cached, so usually instant).
        var attempt by remember(cacheKey) { mutableIntStateOf(0) }
        val outcome by rememberBitmapOutcome(thumbs, cacheKey, clip.name, attempt)
        BitmapBox(
            outcome = outcome,
            description = clip.name,
            what = "Thumbnail",
            onRetry = { thumbs.retry(cacheKey); attempt++ },
            compact = false,
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).clip(RoundedCornerShape(10.dp)),
        )

        HorizontalDivider()
        ContactSheetSection(clip, cacheKey, contacts)
    }
}

/**
 * The hub builds the contact sheet on demand with 12-24 ffmpeg seeks, which can take
 * a while, so it loads on an explicit press rather than for every clip you click.
 */
@Composable
private fun ContactSheetSection(clip: MediaClip, cacheKey: String, contacts: ThumbLoader<ImageBitmap>) {
    val tone = MaterialTheme.colorScheme
    var requested by remember(cacheKey) { mutableIntStateOf(0) }
    val outcome by produceState<ThumbOutcome<ImageBitmap>?>(
        initialValue = contacts.cached(cacheKey)?.let { ThumbOutcome.Ready(it) },
        cacheKey, requested,
    ) {
        if (requested == 0) {
            value = contacts.cached(cacheKey)?.let { ThumbOutcome.Ready(it) }
        } else {
            value = null
            value = contacts.load(cacheKey, clip.name)
        }
    }

    Text("Contact sheet", style = MaterialTheme.typography.labelLarge)
    when (val o = outcome) {
        is ThumbOutcome.Ready -> Image(
            bitmap = o.value,
            contentDescription = "Contact sheet for ${clip.name}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
        )
        is ThumbOutcome.Failed -> ErrorPanel(
            o.error,
            onRetry = { contacts.retry(cacheKey); requested++ },
            fillScreen = false,
        )
        null -> if (requested == 0) {
            Text(
                "Frames sampled across the whole clip, generated on the hub on demand. The first load can take a while.",
                style = MaterialTheme.typography.bodySmall,
                color = tone.onSurfaceVariant,
            )
            Button(onClick = { requested = 1 }) { Text("Load contact sheet") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Generating on the hub...", style = MaterialTheme.typography.bodySmall, color = tone.onSurfaceVariant)
                TextButton(onClick = { requested = 0 }) { Text("Cancel") }
            }
        }
    }
}
