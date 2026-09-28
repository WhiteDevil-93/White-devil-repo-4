package com.whitedevil.ui.you

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whitedevil.ui.components.WdHairline
import com.whitedevil.ui.components.WdListChevron
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.components.WdTopBar
import com.whitedevil.ui.theme.WdDimens
import com.whitedevil.ui.theme.WdPalette

@Composable
fun YouHomeScreen(
    connectionSummary: String,
    veniceReady: Boolean,
    onTerminal: () -> Unit,
    onSettings: () -> Unit,
    onTestConnections: () -> Unit,
    onAddVeniceKey: () -> Unit,
) {
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            WdTopBar(title = "You")
            Text(
                compactStatus(connectionSummary),
                style = MaterialTheme.typography.labelMedium,
                color = WdPalette.textMetadata,
                modifier = Modifier.padding(horizontal = WdDimens.screenHorizontal, vertical = 2.dp),
            )
            if (!veniceReady) {
                Text(
                    "Add Venice API key",
                    style = MaterialTheme.typography.labelMedium,
                    color = WdPalette.accent,
                    modifier = Modifier
                        .padding(horizontal = WdDimens.screenHorizontal, vertical = 4.dp)
                        .clickable { onAddVeniceKey() },
                )
            }
            WdHairline(Modifier.padding(top = 6.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                NavRow("Settings", "Venice, relay, laptop", onSettings)
                WdHairline(Modifier.padding(start = WdDimens.screenHorizontal))
                NavRow("Terminal", "SSH / WSL on relay", onTerminal)
                WdHairline(Modifier.padding(top = 8.dp))
                NavRow("Test connections", "Venice & relay health", onTestConnections, muted = true)
            }
        }
    }
}

private fun compactStatus(summary: String): String =
    summary.lines()
        .filter { it.isNotBlank() }
        .joinToString(" · ") { it.trim() }

@Composable
private fun NavRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    muted: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = WdDimens.screenHorizontal, vertical = WdDimens.rowVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (muted) WdPalette.textSecondary else WdPalette.text,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = WdPalette.textMetadata,
            )
        }
        WdListChevron()
    }
}
