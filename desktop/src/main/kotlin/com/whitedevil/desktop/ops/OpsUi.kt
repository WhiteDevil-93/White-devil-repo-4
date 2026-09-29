package com.whitedevil.desktop.ops

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.whitedevil.desktop.Settings
import kotlinx.coroutines.delay

/*
 * Shared building blocks for the four ops screens (Colab, Thunder, Vast, Setup),
 * in the visual language of AgentScreen/SettingsScreen: 52dp top bar on a tonal
 * surface, content capped at 1100dp, cards on `surface`, palette from Theme.kt.
 * Compose Material 3 only — no WebView, no icons pack.
 */

/** Poll cadence for read-only status. Slow on purpose: the hub shells out to CLIs behind these routes. */
const val OPS_POLL_MS = 15_000L

enum class Tone { Ok, Warn, Bad, Neutral }

@Composable
fun Tone.color(): Color = when (this) {
    Tone.Ok -> Color(0xFF8FBF9A)
    Tone.Warn -> Color(0xFFE3B95F)
    Tone.Bad -> MaterialTheme.colorScheme.error
    Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The hub and relay clients for one screen, closed when the screen leaves composition. */
class OpsClients(val reader: OpsReader, val actor: OpsActor)

@Composable
fun rememberOpsClients(settings: Settings): OpsClients {
    val http = remember(settings.hubUrl, settings.relayUser, settings.relayPass) {
        OpsHttp(settings.hubUrl, settings.relayUser, settings.relayPass)
    }
    DisposableEffect(http) { onDispose { http.close() } }
    return remember(http) { OpsClients(OpsReader(http), OpsActor(http)) }
}

/** One read when the screen appears. Reads only; refreshes after that are the operator's. */
@Composable
fun LoadOnce(panel: PanelState<*>) {
    LaunchedEffect(panel) { panel.refresh() }
}

/**
 * Read once, then re-read every [intervalMs] while this composable is in composition.
 * Bounded three ways: the loop dies when the screen leaves; [PanelState] is
 * single-flight so requests cannot stack; and [PanelState.shouldPoll] holds it
 * back after an error until the operator presses Retry. It only ever calls a
 * panel's read function, never an action.
 */
@Composable
fun PollWhileVisible(panel: PanelState<*>, intervalMs: Long = OPS_POLL_MS) {
    LaunchedEffect(panel) {
        panel.refresh()
        while (true) {
            delay(intervalMs)
            if (panel.shouldPoll()) panel.refresh()
        }
    }
}

@Composable
fun OpsScreenFrame(
    title: String,
    subtitle: String?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                TextButton(onClick = onRefresh, enabled = !refreshing) { Text("Refresh") }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 1100.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                trailing()
            }
            content()
        }
    }
}

@Composable
fun Pill(text: String, tone: Tone) {
    val c = tone.color()
    Box(Modifier.background(c.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = c)
    }
}

@Composable
fun Tile(label: String, value: String, sub: String? = null, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral) {
    Column(
        modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = if (tone == Tone.Neutral) MaterialTheme.colorScheme.onSurface else tone.color())
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun KeyValue(key: String, value: String, mono: Boolean = false) {
    Row(verticalAlignment = Alignment.Top) {
        Text(key, Modifier.width(170.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                value,
                style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun MonoBlock(text: String) {
    SelectionContainer {
        Text(
            text,
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun Note(text: String, tone: Tone = Tone.Neutral) {
    val c = tone.color()
    Text(
        text,
        Modifier.fillMaxWidth().background(c.copy(alpha = 0.10f), RoundedCornerShape(8.dp)).padding(10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (tone == Tone.Neutral) MaterialTheme.colorScheme.onSurface else c,
    )
}

@Composable
fun LoadingCard(text: String) {
    SectionCard("Loading") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Why a request failed, in the panel where the data should have been. */
@Composable
fun ErrorCard(what: String, message: String, status: Int?, retrying: Boolean, onRetry: () -> Unit) {
    val err = MaterialTheme.colorScheme.error
    Column(
        Modifier.fillMaxWidth().background(err.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Could not load $what" + (status?.let { " (HTTP $it)" } ?: ""), style = MaterialTheme.typography.titleSmall, color = err)
        SelectionContainer { Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground) }
        Text(
            "Nothing below is current until a request succeeds. Automatic refresh is paused; press Retry.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onRetry, enabled = !retrying) { Text(if (retrying) "Retrying…" else "Retry") }
    }
}

/**
 * The one place a panel's three states are rendered. An error is shown as an
 * error card; the last good reading (if any) is kept beneath it, dimmed and
 * marked stale, and [content] is told so it can disable its actions.
 */
@Composable
fun <T> PanelView(
    panel: PanelState<T>,
    what: String,
    onRetry: () -> Unit,
    content: @Composable ColumnScope.(value: T, stale: Boolean) -> Unit,
) {
    when (val s = panel.state) {
        is OpsState.Loading -> LoadingCard("Loading $what…")
        is OpsState.Error -> {
            ErrorCard(what, s.message, s.status, panel.refreshing, onRetry)
            panel.lastGood?.let { good ->
                Note("Below: the last good reading from ${formatClock(good.atMillis)}. It is STALE and may not match reality; controls are disabled.", Tone.Warn)
                Column(Modifier.alpha(0.5f), verticalArrangement = Arrangement.spacedBy(16.dp)) { content(good.value, true) }
            }
        }
        is OpsState.Loaded -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) { content(s.value, false) }
    }
}

@Composable
fun EmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A row of [Tile]s with equal heights. */
@Composable
fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** A small dropdown: the current choice on a button, options in a menu. */
@Composable
fun Picker(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) { Text("$label: $selected  ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); open = false })
            }
        }
    }
}
