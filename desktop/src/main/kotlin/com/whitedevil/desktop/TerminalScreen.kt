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

private val TerminalBackground = Color(0xFF0C0A09)

/**
 * The Shell tab: a JediTerm terminal emulator over the WSL pty in [session].
 *
 * JediTerm is a Swing component (it is what IntelliJ's terminal is built on), so it
 * is hosted with [SwingPanel]. Colour, cursor addressing, alternate screen and
 * resize are its job; this screen only frames it.
 */
@Composable
fun TerminalScreen(session: ShellSession) {
    // Start on first open. Idempotent, so returning to the tab does not restart it.
    LaunchedEffect(session) { session.ensureStarted() }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Shell", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (session.running) "wsl · connected" else "not running",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (session.running) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.weight(1f))
                if (!session.running) TextButton(onClick = { session.restart() }) { Text("Restart") }
                TextButton(onClick = { session.clear() }, enabled = session.widget != null) { Text("Clear") }
            }
        }

        if (session.status.isNotBlank()) {
            Text(
                session.status,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth().background(TerminalBackground)) {
            val widget = session.widget
            if (widget == null) {
                if (session.status.isBlank()) {
                    Text(
                        "Starting WSL…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                // Keyed on the widget: Restart builds a new one, and SwingPanel only
                // calls its factory when it enters composition.
                key(widget) {
                    SwingPanel(
                        modifier = Modifier.fillMaxSize(),
                        background = TerminalBackground,
                        factory = { widget },
                    )
                }
                // Keystrokes go to the Swing component, so it needs focus whenever
                // the tab is shown, not only after a click.
                LaunchedEffect(widget) { widget.terminalPanel.requestFocusInWindow() }
            }
        }
    }
}
