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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.components.WdSurfaceCard
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text("You", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Terminal, settings, health",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
            WdSurfaceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Connections", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    Text(connectionSummary, style = MaterialTheme.typography.bodySmall, color = WdPalette.textSecondary)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Run test",
                        style = MaterialTheme.typography.labelMedium,
                        color = WdPalette.accent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onTestConnections() },
                    )
                    if (!veniceReady) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Add Venice API key →",
                            style = MaterialTheme.typography.labelMedium,
                            color = WdPalette.accent,
                            modifier = Modifier.clickable { onAddVeniceKey() },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            NavRow("Terminal", "SSH / WSL via relay", onTerminal)
            Spacer(Modifier.height(8.dp))
            NavRow("Settings", "Keys and relay credentials", onSettings)
        }
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    WdSurfaceCard(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = WdPalette.textTertiary)
        }
    }
}
