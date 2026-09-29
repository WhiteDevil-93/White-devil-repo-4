package com.whitedevil.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The Shell tab: a real terminal emulator (JediTerm) over a pty into WSL.
 *
 * Nothing here owns the shell. The session lives in [WslShell.shared], which is
 * process-scoped, because Main.kt drops this composable from composition on every tab
 * switch. Leaving the tab therefore disposes only the SwingPanel; the pty, the emulator
 * state (screen, scrollback, alternate-screen contents) and any running program all
 * survive, and coming back re-parents the same Swing component. There is deliberately no
 * DisposableEffect that closes the terminal: that would kill the shell on every switch.
 * The child is killed by a JVM shutdown hook instead (see [WslShell]).
 */
@Composable
fun TerminalScreen() {
    val shell = remember { WslShell.shared }
    val status = shell.status

    // First open starts the shell; later opens find it already running. Focus goes to the
    // terminal so typing works without a click.
    LaunchedEffect(shell) {
        shell.ensureStarted()
        shell.focusTerminal()
        // The SwingPanel attaches its host during this same composition pass, so the first
        // request can land before the terminal is showing; ask once more after it is.
        delay(150)
        shell.focusTerminal()
    }

    // Same lambda instance across recompositions, so SwingPanel does not tear down and
    // re-create its host (which would drop terminal focus) on every status change.
    val factory = remember(shell) { { shell.view } }
    val terminalBackground = remember { Color(0xFF000000L or TerminalTheme.BACKGROUND_RGB.toLong()) }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Shell", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(12.dp))
                Text(
                    when (status.phase) {
                        WslShell.Phase.Running ->
                            if (status.backend.isNotBlank()) "wsl · connected · ${status.backend}" else "wsl · connected"
                        WslShell.Phase.Starting -> "starting…"
                        else -> "not running"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (status.phase) {
                        WslShell.Phase.Running, WslShell.Phase.Starting -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.weight(1f))
                if (!status.running && status.phase != WslShell.Phase.Starting) {
                    TextButton(onClick = { shell.restart() }) { Text("Restart") }
                }
                TextButton(onClick = { shell.clear() }) { Text("Clear") }
            }
        }

        if (status.message.isNotBlank()) {
            Text(
                status.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(terminalBackground)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            SwingPanel(
                background = terminalBackground,
                factory = factory,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
