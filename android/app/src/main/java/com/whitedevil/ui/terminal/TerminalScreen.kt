package com.whitedevil.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.R
import com.whitedevil.ui.components.WdHairline
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.components.WdSurfaceCard
import com.whitedevil.ui.theme.WdPalette

@Composable
fun TerminalScreen(host: MainActivity, showBack: Boolean) {
    var input by remember { mutableStateOf("") }
    val log = host.terminalLogPublic()
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex)
    }
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showBack) {
                    Text(
                        "← You",
                        style = MaterialTheme.typography.labelMedium,
                        color = WdPalette.accentLight,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clickable { host.showYouSub(MainActivity.YouSub.HOME) },
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("System Console", style = MaterialTheme.typography.titleMedium)
                    Text("Relay · POST /api/laptop/run", style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    "Paste",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = WdPalette.onLightButton,
                    modifier = Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .background(WdPalette.accent)
                        .clickable { host.openTerminalPasteSheetPublic() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                IconButton(onClick = { host.clearTerminalLogPublic() }) {
                    Icon(painterResource(R.drawable.ic_more), contentDescription = "Clear log", tint = WdPalette.textMetadata)
                }
            }
            WdHairline()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (log.isEmpty()) {
                    item {
                        Text(
                            "Run bash on your laptop via the relay. No WebView — commands execute through /api/laptop/run.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                items(log) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (line.startsWith("$")) WdPalette.accentLight else WdPalette.textSecondary,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .borderCompose()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = WdPalette.text,
                        fontFamily = FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(WdPalette.accent),
                    singleLine = true,
                    decorationBox = { inner ->
                        if (input.isEmpty()) {
                            Text("$ ", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = WdPalette.textMetadata)
                        }
                        inner()
                    },
                )
                Text(
                    "Run",
                    color = WdPalette.accentLight,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable(enabled = input.isNotBlank() && !host.terminalRunningPublic()) {
                            val cmd = input
                            input = ""
                            host.runTerminalCommandPublic(cmd)
                        },
                )
            }
        }
    }
    if (host.terminalPasteOpenPublic()) {
        Dialog(onDismissRequest = { host.setTerminalPasteOpen(false) }) {
            WdSurfaceCard {
                Column(Modifier.padding(20.dp)) {
                    Text("Paste script", style = MaterialTheme.typography.titleMedium)
                    BasicTextField(
                        value = host.terminalPasteTextPublic(),
                        onValueChange = { host.setTerminalPasteText(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        minLines = 4,
                    )
                    Row(Modifier.padding(top = 8.dp)) {
                        TextButton(onClick = { host.sendPasteToTerminalPublic() }) {
                            Text("Run", color = WdPalette.accentLight)
                        }
                        TextButton(onClick = { host.setTerminalPasteOpen(false) }) {
                            Text("Cancel", color = WdPalette.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.borderCompose(): Modifier =
    this then Modifier
        .clip(RoundedCornerShape(2.dp))
        .background(WdPalette.surface)
