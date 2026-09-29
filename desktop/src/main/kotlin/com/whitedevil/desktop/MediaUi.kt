package com.whitedevil.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One [MediaClient] per set of credentials, closed when the screen leaves composition. */
@Composable
fun rememberMediaClient(settings: Settings): MediaClient {
    val client = remember(settings.hubUrl, settings.relayUser, settings.relayPass) {
        MediaClient(settings.hubUrl, settings.relayUser, settings.relayPass)
    }
    DisposableEffect(client) { onDispose { client.close() } }
    return client
}

/** Owns the library request and its [LibraryUiState]. */
@Stable
class LibraryHolder(private val scope: CoroutineScope, private val client: MediaClient) {
    var state: LibraryUiState by mutableStateOf(LibraryUiState.Loading)
        private set

    private var job: Job? = null

    fun reload() {
        job?.cancel()
        state = LibraryUiState.Loading
        job = scope.launch {
            state = try {
                libraryStateFrom(client.library())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything unforeseen still has to be visible rather than crash the window or hide as "empty".
                LibraryUiState.Error(
                    MediaError(
                        MediaErrorKind.BadResponse,
                        "Unexpected error while loading the library.",
                        null,
                        e.message ?: e.javaClass.simpleName,
                    ),
                )
            }
        }
    }
}

/** Loads the library once when the screen opens; later loads happen only on an explicit button press. */
@Composable
fun rememberLibrary(client: MediaClient): LibraryHolder {
    val scope = rememberCoroutineScope()
    val holder = remember(client) { LibraryHolder(scope, client) }
    LaunchedEffect(holder) { holder.reload() }
    return holder
}

/** Wall clock that ticks so "5m ago" labels stay honest while the screen is open. */
@Composable
fun rememberNowMs(intervalMs: Long = 30_000): State<Long> =
    produceState(System.currentTimeMillis()) {
        while (true) {
            delay(intervalMs)
            value = System.currentTimeMillis()
        }
    }

@Composable
fun MediaTopBar(title: String, subtitle: String, busy: Boolean, onRefresh: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(12.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
            }
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
    }
}

@Composable
fun LoadingPanel(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A failed request, in full: HTTP status (or kind of failure), what to do about it,
 * anything the hub said, and a Retry button. Retrying is always an explicit press.
 */
@Composable
fun ErrorPanel(error: MediaError, onRetry: () -> Unit, fillScreen: Boolean = true) {
    val tone = MaterialTheme.colorScheme
    val card: @Composable () -> Unit = {
        SelectionContainer {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .background(tone.error.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(error.title, style = MaterialTheme.typography.titleSmall, color = tone.error)
                Text(error.message, style = MaterialTheme.typography.bodyMedium, color = tone.onBackground)
                error.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = tone.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
    if (fillScreen) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { card() }
    } else {
        card()
    }
}

/** The hub answered successfully and there is genuinely nothing to show. */
@Composable
fun EmptyPanel(text: String, onRefresh: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Nothing here", style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(
                onClick = onRefresh,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
            ) { Text("Refresh") }
        }
    }
}

/** Entries the hub sent that could not be read; shown above the data rather than dropped. */
@Composable
fun WarningsBanner(warnings: List<String>) {
    val tone = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().background(tone.error.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Some entries in the hub's response could not be read and are not shown:",
            style = MaterialTheme.typography.labelMedium,
            color = tone.error,
        )
        warnings.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = tone.onSurfaceVariant)
        }
    }
}

@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
