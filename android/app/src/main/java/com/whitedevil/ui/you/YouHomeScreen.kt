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
            Text("You", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
            Text("CONNECTIONS", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = WdPalette.surface) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        connectionSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = WdPalette.textSecondary,
                        modifier = Modifier.padding(16.dp),
                    )
                    HorizontalDivider(color = WdPalette.stroke)
                    GroupRow("Test connections", onClick = onTestConnections)
                    if (!veniceReady) {
                        HorizontalDivider(color = WdPalette.stroke)
                        GroupRow("Add Venice API key", accent = true, onClick = onAddVeniceKey)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("TOOLS", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = WdPalette.surface) {
                Column {
                    GroupRow("Terminal", subtitle = "Relay shell", onClick = onTerminal)
                    HorizontalDivider(color = WdPalette.stroke)
                    GroupRow("Settings", subtitle = "Credentials", onClick = onSettings)
                }
            }
        }
    }
}

@Composable
private fun GroupRow(title: String, subtitle: String? = null, accent: Boolean = false, onClick: () -> Unit) {
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
                style = MaterialTheme.typography.bodyLarge,
                color = if (accent) WdPalette.accent else WdPalette.text,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text("›", style = MaterialTheme.typography.bodyLarge, color = WdPalette.textTertiary)
    }
}
