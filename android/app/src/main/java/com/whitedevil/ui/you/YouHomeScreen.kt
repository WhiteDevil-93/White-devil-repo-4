package com.whitedevil.ui.you

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whitedevil.ui.components.WdScreenBackground
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
            Text("You", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Control centre",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
            Text("ACCOUNT", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            Text(
                connectionSummary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            if (!veniceReady) {
                Text(
                    "Add Venice API key →",
                    style = MaterialTheme.typography.labelMedium,
                    color = WdPalette.accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                        .clickable { onAddVeniceKey() },
                )
            }
            Spacer(Modifier.height(20.dp))
            Text("CONNECTIONS", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            Surface(shape = RoundedCornerShape(10.dp), color = WdPalette.surface) {
                Column(Modifier.fillMaxWidth()) {
                    GroupRow("Settings", subtitle = "Venice, relay, laptop credentials", emphasized = true, onClick = onSettings)
                    HorizontalDivider(color = WdPalette.stroke)
                    GroupRow("Terminal", subtitle = "Relay SSH / WSL shell", onClick = onTerminal)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("DIAGNOSTICS", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            Surface(shape = RoundedCornerShape(10.dp), color = WdPalette.surface) {
                GroupRow("Test connections", subtitle = "Verify Venice and relay reachability", onClick = onTestConnections)
            }
        }
    }
}

@Composable
private fun GroupRow(
    title: String,
    subtitle: String? = null,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Text("›", style = MaterialTheme.typography.bodyLarge, color = WdPalette.textMetadata)
    }
}
