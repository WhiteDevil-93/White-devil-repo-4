package com.whitedevil.ui.you

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.whitedevil.BuildConfig
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.whitedevil.ui.components.WdHairline
import com.whitedevil.ui.components.WdListChevron
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.theme.WdPalette

private const val AVATAR = "https://storage.googleapis.com/uxpilot-auth.appspot.com/avatars/avatar-2.jpg"

@Composable
fun YouHomeScreen(
    connectionSummary: String,
    veniceReady: Boolean,
    onTerminal: () -> Unit,
    onSettings: () -> Unit,
    onPhoneFiles: () -> Unit,
    onTestConnections: () -> Unit,
    onAddVeniceKey: () -> Unit,
) {
    var docsOpen by remember { mutableStateOf(false) }
    if (docsOpen) AlertDialog(
        onDismissRequest = { docsOpen = false },
        title = { Text("WhiteDevil guide") },
        text = { Text("Agent: add a Venice API key, test connections in Settings, then send a goal. Stop ends the current run without clearing history.\n\nHub: create and monitor work. Check source, workload and cost before starting a render.\n\nPhone files: link a folder, open subfolders, then attach files to a goal.\n\nSettings: configure connections, agent and security. Configured does not mean online.") },
        confirmButton = { TextButton(onClick = { docsOpen = false }) { Text("Close") } },
    )
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Box(Modifier.size(88.dp).border(2.dp, WdPalette.accent, CircleShape), contentAlignment = Alignment.Center) {
                        Text("WD", style = MaterialTheme.typography.titleLarge)
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(WdPalette.bg)
                            .padding(2.dp),
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(WdPalette.textMetadata),
                        )
                    }
                }
                Spacer(Modifier.size(16.dp))
                Text("This device", style = MaterialTheme.typography.titleLarge)
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .border(1.dp, WdPalette.stroke, RoundedCornerShape(2.dp))
                        .background(WdPalette.surface)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("Local profile • no account tier", style = MaterialTheme.typography.labelLarge, color = WdPalette.accentLight)
                }
                if (!veniceReady) {
                    Text(
                        "Add Venice API key",
                        style = MaterialTheme.typography.labelMedium,
                        color = WdPalette.accentLight,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clickable { onAddVeniceKey() },
                    )
                } else {
                    Text(
                        compactStatus(connectionSummary),
                        style = MaterialTheme.typography.labelMedium,
                        color = WdPalette.textMetadata,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
                    )
                }
            }
            WdHairline()
            ProfileRow(Icons.Outlined.Settings, "Settings", "Connection, agent & security", onSettings)
            WdHairline(Modifier.padding(start = 16.dp))
            ProfileRow(Icons.Outlined.Folder, "Phone files", "Browse & attach from this device", onPhoneFiles)
            WdHairline(Modifier.padding(start = 16.dp))
            ProfileRow(Icons.Outlined.Terminal, "System Console", "Log analysis & CLI", onTerminal)
            WdHairline(Modifier.padding(start = 16.dp))
            ProfileRow(Icons.Outlined.Shield, "Test connections", "Check current service reachability", onTestConnections)
            WdHairline(Modifier.padding(start = 16.dp))
            ProfileRow(Icons.AutoMirrored.Outlined.MenuBook, "WhiteDevil guide", "Setup & workflow help", { docsOpen = true })
            WdHairline()
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("VERSION ${BuildConfig.VERSION_NAME} • BUILD ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.labelLarge, color = WdPalette.textMetadata)
                Text(
                    "Status is verified only by connection tests",
                    style = MaterialTheme.typography.labelLarge,
                    color = WdPalette.textMetadata,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private fun compactStatus(summary: String): String =
    summary.lines().filter { it.isNotBlank() }.joinToString(" · ") { it.trim() }

@Composable
private fun ProfileRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    subtitleColor: Color = WdPalette.textMetadata,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .border(1.dp, WdPalette.stroke, RoundedCornerShape(2.dp))
                .background(WdPalette.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = WdPalette.textMetadata, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle.uppercase(), style = MaterialTheme.typography.labelLarge, color = subtitleColor)
        }
        WdListChevron()
    }
}
