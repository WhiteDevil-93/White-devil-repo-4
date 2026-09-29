package com.whitedevil.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp

/** The repository as the screens use it: decoded to Compose bitmaps. */
typealias MediaData = MediaRepository<ImageBitmap>

/** Decodes JPEG bytes with Skia. Throws on data that is not an image; the repository turns that into a visible failure. */
fun decodeBitmap(bytes: ByteArray): Decoded<ImageBitmap> {
    val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
    try {
        val bitmap = image.toComposeImageBitmap()
        // 4 bytes a pixel is what the decoded bitmap costs, whatever the JPEG weighed.
        return Decoded(bitmap, bitmap.width.toLong() * bitmap.height * 4)
    } finally {
        image.close()
    }
}

fun sourceLabel(source: String): String = when (source) {
    "thunder" -> "Thunder 14B"
    "ltx" -> "LTX 2.5"
    else -> "Vast"
}

fun kindLabel(kind: String): String = when (kind) {
    "pack" -> "Pack"
    "chain" -> "Chain"
    "keeper" -> "Keepers"
    "test" -> "Tests"
    else -> kind.replaceFirstChar { it.uppercase() }
}

/** Filter values for the source chips; null means "all". */
val SOURCE_FILTERS: List<Pair<String?, String>> = listOf(null to "All", "vast" to "Vast", "ltx" to "LTX", "thunder" to "Thunder")

/**
 * A failure, shown where the data would have been. Never replaced by an empty
 * grid: an empty grid reads as "no renders", which is a different fact.
 */
@Composable
fun ErrorBanner(message: String, detail: String? = null, onRetry: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onRetry != null) OutlinedButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

/** Centered neutral message, for states that are not failures ("no renders yet", "nothing matches"). */
@Composable
fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ScreenHeader(title: String, summary: String, loading: Boolean, onRefresh: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(12.dp))
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            TextButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") }
        }
    }
}

/** The "showing older data" note that goes with a refresh failure when a previous list is still on screen. */
fun staleNote(media: MediaData, nowMs: Long): String? =
    media.loadedAtMs?.let { "Showing the list from ${formatAge(nowMs, it / 1000.0)}." }
