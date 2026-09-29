package com.whitedevil.ui.hub

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.whitedevil.ui.theme.WdPalette
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray

data class HubClipRef(val name: String, val title: String)

fun hubThumbUrl(relayBase: String, clipName: String): String =
    "${relayBase.trimEnd('/')}/api/media/thumb/${URLEncoder.encode(clipName, StandardCharsets.UTF_8.toString())}"

fun hubClipUrl(relayBase: String, clipName: String): String =
    "${relayBase.trimEnd('/')}/clips/${URLEncoder.encode(clipName, StandardCharsets.UTF_8.toString())}"

fun hubContactUrl(relayBase: String, clipName: String): String =
    "${relayBase.trimEnd('/')}/api/media/contact/${URLEncoder.encode(clipName, StandardCharsets.UTF_8.toString())}"

/** Pipeline tag for a clip — mirrors hub/media.py clip_source. */
fun hubClipPipeline(name: String, groupSource: String? = null): String {
    val n = name.lowercase()
    if (n.endsWith("_14b.mp4") || n.startsWith("thunder_")) return "thunder"
    if (n.startsWith("ltx_") || n.startsWith("ltx-") || n.contains("ltx_chain")) return "ltx"
    if (groupSource == "thunder" || groupSource == "ltx" || groupSource == "vast") return groupSource
    return "vast"
}

fun hubFlatClips(libraryJson: String?, source: String, limit: Int = 8): List<HubClipRef> {
    val arr = runCatching { JSONArray(libraryJson ?: "[]") }.getOrNull() ?: return emptyList()
    data class Row(val ref: HubClipRef, val mtime: Double, val final: Boolean)
    val rows = mutableListOf<Row>()
    for (i in 0 until arr.length()) {
        val g = arr.optJSONObject(i) ?: continue
        val title = g.optString("title")
        val groupSource = g.optString("source", "vast")
        val clips = g.optJSONArray("clips") ?: continue
        for (j in 0 until clips.length()) {
            val c = clips.optJSONObject(j) ?: continue
            val name = c.optString("name")
            if (name.isBlank()) continue
            val src = c.optString("source").ifBlank { hubClipPipeline(name, groupSource) }
            if (src != source) continue
            val idx = c.optInt("idx").takeIf { it > 0 }
            rows += Row(
                HubClipRef(name, title + (idx?.let { " · clip $it" } ?: "")),
                c.optDouble("mtime"),
                !Regex("""_c\d+\.mp4$""", RegexOption.IGNORE_CASE).containsMatchIn(name),
            )
        }
    }
    val preferred = rows.filter { it.final }.ifEmpty { rows }
    return preferred.sortedByDescending { it.mtime }.take(limit).map { it.ref }
}

fun formatAgo(epochSec: Double): String {
    val mins = ((System.currentTimeMillis() / 1000.0 - epochSec) / 60.0).toInt().coerceAtLeast(0)
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 1440 -> "${mins / 60}h ago"
        else -> "${mins / 1440}d ago"
    }
}

@Composable
fun HubSectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = WdPalette.textMetadata)
        }
    }
}

@Composable
fun HubCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .background(WdPalette.surface)
            .padding(14.dp),
    ) {
        content()
    }
}

@Composable
fun HubPill(text: String, ok: Boolean? = null, onClick: (() -> Unit)? = null) {
    val bg = when (ok) {
        true -> WdPalette.accent.copy(alpha = 0.2f)
        false -> WdPalette.stroke
        null -> WdPalette.surface
    }
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (ok == true) WdPalette.accentLight else WdPalette.textSecondary,
    )
}

@Composable
fun HubStatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = WdPalette.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun HubProgressRow(label: String, progress: Int?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                progress?.let { "$it%" } ?: "—",
                style = MaterialTheme.typography.labelMedium,
                color = WdPalette.textMetadata,
            )
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { (progress.coerceIn(0, 100) / 100f) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp),
                color = WdPalette.accent,
                trackColor = WdPalette.stroke,
            )
        }
    }
}

@Composable
fun HubRelayThumb(
    relayBase: String,
    authHeader: String,
    clipName: String,
    caption: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .width(120.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(hubThumbUrl(relayBase, clipName))
                    .addHeader("Authorization", authHeader)
                    .crossfade(true)
                    .build(),
                contentDescription = clipName,
                modifier = Modifier
                    .width(120.dp)
                    .height(68.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(WdPalette.stroke),
                contentScale = ContentScale.Crop,
            )
            if (onClick != null) {
                Text(
                    "▶",
                    color = WdPalette.onLightButton,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(WdPalette.accent.copy(alpha = 0.75f), RoundedCornerShape(2.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        caption?.takeIf { it.isNotBlank() }?.let { cap ->
            Text(
                cap,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun HubClipViewer(
    relayBase: String,
    authHeader: String,
    clips: List<HubClipRef>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    if (clips.isEmpty()) return
    var index by remember(clips, startIndex) {
        mutableIntStateOf(startIndex.coerceIn(0, clips.lastIndex))
    }
    val clip = clips[index]
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(WdPalette.bg)
                .padding(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    clip.title.ifBlank { clip.name },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Text(
                    "Close",
                    color = WdPalette.accentLight,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
            Text(
                "${index + 1} / ${clips.size}",
                style = MaterialTheme.typography.labelSmall,
                color = WdPalette.textMetadata,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(2.dp))
                    .background(WdPalette.stroke),
                contentAlignment = Alignment.Center,
            ) {
                key(clip.name) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start()
                                }
                                setOnErrorListener { _, _, _ ->
                                    true
                                }
                            }
                        },
                        update = { view ->
                            view.stopPlayback()
                            view.setVideoURI(
                                Uri.parse(hubClipUrl(relayBase, clip.name)),
                                mapOf("Authorization" to authHeader),
                            )
                            view.start()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Prev",
                    color = if (index > 0) WdPalette.accentLight else WdPalette.textMetadata,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = index > 0) { index -= 1 }
                        .padding(vertical = 10.dp),
                )
                Text(
                    "Next",
                    color = if (index < clips.lastIndex) WdPalette.accentLight else WdPalette.textMetadata,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = index < clips.lastIndex) { index += 1 }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
fun HubPrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .background(if (enabled) WdPalette.accent else WdPalette.stroke)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = WdPalette.onLightButton,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
fun HubVideoReviewSection(
    relayBase: String,
    authHeader: String,
    libraryJson: String?,
    source: String,
    title: String = "Video review",
) {
    val clips = remember(libraryJson, source) { hubFlatClips(libraryJson, source) }
    var playing by remember { mutableStateOf<Pair<List<HubClipRef>, Int>?>(null) }
    val labels = mapOf("ltx" to "LTX 2.5", "thunder" to "Thunder 14B", "vast" to "Vast / Colab Remix")
    playing?.let { (list, start) ->
        HubClipViewer(relayBase, authHeader, list, start) { playing = null }
    }
    HubCard {
        HubSectionTitle(title, labels[source] ?: source)
        if (clips.isEmpty()) {
            Text("No ${labels[source] ?: source} renders yet.", color = WdPalette.textSecondary)
            return@HubCard
        }
        val latest = clips.first()
        Text(
            "Latest · ${latest.title.ifBlank { latest.name }}",
            style = MaterialTheme.typography.labelMedium,
            color = WdPalette.textMetadata,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        AsyncImage(
            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                .data(hubContactUrl(relayBase, latest.name))
                .addHeader("Authorization", authHeader)
                .crossfade(true)
                .build(),
            contentDescription = "Contact sheet ${latest.name}",
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(WdPalette.stroke)
                .clickable { playing = clips to 0 },
            contentScale = ContentScale.Crop,
        )
        Text(
            "▶ Tap contact sheet to play · ${clips.size} recent",
            style = MaterialTheme.typography.labelSmall,
            color = WdPalette.accentLight,
            modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            clips.forEachIndexed { i, ref ->
                HubRelayThumb(
                    relayBase,
                    authHeader,
                    ref.name,
                    if (i == 0) "Latest" else ref.title.substringAfter(" · ").takeIf { it != ref.title },
                    onClick = { playing = clips to i },
                )
            }
        }
    }
}
