package com.whitedevil.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.whitedevil.ui.theme.WdPalette
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
fun hubThumbUrl(relayBase: String, clipName: String): String =
    "${relayBase.trimEnd('/')}/api/media/thumb/${URLEncoder.encode(clipName, StandardCharsets.UTF_8.toString())}"

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
) {
    Column(modifier.width(120.dp)) {
        AsyncImage(
            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                .data(hubThumbUrl(relayBase, clipName))
                .addHeader("Authorization", authHeader)
                .crossfade(true)
                .build(),
            contentDescription = clipName,
            modifier = modifier
                .width(120.dp)
                .height(68.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(WdPalette.stroke),
            contentScale = ContentScale.Crop,
        )
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
