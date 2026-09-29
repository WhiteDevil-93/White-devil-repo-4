package com.whitedevil.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Maximum characters of scrollback kept; beyond this the oldest is dropped. */
private const val SCROLLBACK_CHARS = 200_000

@Composable
fun TerminalScreen() {
    var output by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val history = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableStateOf(-1) }

    val terminal = remember {
        WslTerminal(
            onOutput = { chunk ->
                // Trim from the front so a long-running build cannot grow this
                // without bound and eventually exhaust the heap.
                val next = output + chunk
                output = if (next.length > SCROLLBACK_CHARS) next.takeLast(SCROLLBACK_CHARS) else next
            },
            onExit = { code ->
                running = false
                status = "Shell exited (code $code)."
            },
        )
    }

    fun start() {
        if (!WslTerminal.isWslAvailable()) {
            status = "wsl.exe not available on this machine."
            return
        }
        terminal.start()
            .onSuccess { running = true; status = "" }
            .onFailure { status = "Could not start the shell: ${it.message}" }
    }

    // Start on first open, and make sure the child process dies with the screen
    // rather than outliving it as an orphan.
    LaunchedEffect(Unit) { start() }
    DisposableEffect(Unit) { onDispose { terminal.close() } }
    LaunchedEffect(output) { scroll.animateScrollTo(scroll.maxValue) }

    fun send() {
        val line = command
        if (!running) { status = "Shell is not running."; return }
        terminal.write(line + "\n").onFailure { status = it.message ?: "write failed" }
        if (line.isNotBlank()) { history.add(line); historyIndex = -1 }
        command = ""
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Shell", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (running) "wsl · connected" else "not running",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (running) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.weight(1f))
                if (!running) TextButton(onClick = { start() }) { Text("Restart") }
                TextButton(onClick = { output = "" }) { Text("Clear") }
            }
        }

        if (status.isNotBlank()) {
            Text(
                status,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(Color(0xFF0C0A09))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = output.ifEmpty { "Starting WSL…" },
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    color = Color(0xFFD8D2C8),
                ),
                modifier = Modifier.verticalScroll(scroll).fillMaxWidth(),
            )
        }

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$", style = TextStyle(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.secondary))
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = running,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
                )
                Spacer(Modifier.width(12.dp))
                Button(onClick = ::send, enabled = running) { Text("Run") }
            }
        }
    }
}
