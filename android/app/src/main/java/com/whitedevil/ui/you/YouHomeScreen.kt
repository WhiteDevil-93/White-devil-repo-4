package com.whitedevil.ui.you

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.whitedevil.ui.components.WdGroupedList
import com.whitedevil.ui.components.WdListChevron
import com.whitedevil.ui.components.WdListDivider
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.components.WdScreenTitle
import com.whitedevil.ui.components.WdSectionLabel
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
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            WdScreenTitle(
                title = "You",
                subtitle = "Control centre",
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            WdSectionLabel("ACCOUNT")
            StatusBlock(connectionSummary, veniceReady, onAddVeniceKey)
            Spacer(Modifier.height(22.dp))
            WdSectionLabel("CONNECTIONS")
            WdGroupedList {
                GroupRow("Settings", subtitle = "Venice, relay, laptop credentials", onClick = onSettings)
                WdListDivider()
                GroupRow("Terminal", subtitle = "Relay SSH / WSL shell", onClick = onTerminal)
            }
            Spacer(Modifier.height(22.dp))
            WdSectionLabel("DIAGNOSTICS")
            WdGroupedList {
                GroupRow("Test connections", subtitle = "Verify Venice and relay reachability", onClick = onTestConnections)
            }
        }
    }
}

@Composable
private fun StatusBlock(summary: String, veniceReady: Boolean, onAddKey: () -> Unit) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        summary.lines().filter { it.isNotBlank() }.forEach { line ->
            val trimmed = line.trim()
            val ready = when {
                trimmed.contains("not configured", ignoreCase = true) -> false
                trimmed.contains("configured", ignoreCase = true) -> true
                trimmed.contains("reachable", ignoreCase = true) -> true
                else -> null
            }
            StatusLine(trimmed, ready)
        }
        if (!veniceReady) {
            Text(
                "Add Venice API key",
                style = MaterialTheme.typography.labelMedium,
                color = WdPalette.accent,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clickable { onAddKey() },
            )
        }
    }
}

@Composable
private fun StatusLine(text: String, ready: Boolean?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ready != null) {
            Box(
                Modifier
                    .padding(end = 10.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (ready) WdPalette.accent else WdPalette.textMetadata),
            )
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = WdPalette.textSecondary)
    }
}

@Composable
private fun GroupRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
        }
        WdListChevron()
    }
}
