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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    onChats: () -> Unit,
    onProjects: () -> Unit,
    onMemory: () -> Unit,
    onSkills: () -> Unit,
    onConnectors: () -> Unit,
    onTestConnections: () -> Unit,
    onAddVeniceKey: () -> Unit,
) {
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    AsyncImage(
                        model = AVATAR,
                        contentDescription = null,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .border(2.dp, WdPalette.accent, CircleShape),
                        contentScale = ContentScale.Crop,
                    )
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
                                .background(WdPalette.success),
                        )
                    }
                }
                Spacer(Modifier.size(16.dp))
                Text("User_7294", style = MaterialTheme.typography.titleLarge)
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .border(1.dp, WdPalette.stroke, RoundedCornerShape(2.dp))
                        .background(WdPalette.surface)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("DEVELOPER TIER", style = MaterialTheme.typography.labelLarge, color = WdPalette.accentLight)
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
            ProfileRow(Icons.Outlined.Forum, "Chats", "History, search, export", onChats)
            WdHairline(Modifier.padding(start = 16.dp))
            ProfileRow(Icons.Outlined.Folder, "Projects", "Standing instructions per topic", onProjects)
            WdHairline(Modifier.padding(start = 16.dp))
            ProfileRow(Icons.Outlined.Psychology, "Memory", "What the agent remembers", onMemory)
            WdHairline(Modifier.padding(start = 16.dp))
            ProfileRow(Icons.Outlined.AutoAwesome, "Skills", "Instruction packs on demand", onSkills)
            WdHairline(Modifier.padding(start = 16.dp))
            ProfileRow(Icons.Outlined.Extension, "Connectors", "MCP servers & tools", onConnectors)
            WdHairline(Modifier.padding(start = 16.dp))
            ProfileRow(Icons.Outlined.Settings, "General Settings", "UI, Language, Regions", onSettings)
            WdHairline(Modifier.padding(start = 16.dp))
            ProfileRow(Icons.Outlined.Folder, "Phone files", "Browse & attach from this device", onPhoneFiles)
            WdHairline(Modifier.padding(start = 16.dp))
            ProfileRow(Icons.Outlined.Terminal, "System Console", "Log analysis & CLI", onTerminal)
            WdHairline(Modifier.padding(start = 16.dp))
            ProfileRow(Icons.Outlined.Shield, "Security Protocol", "All nodes active", onTestConnections, subtitleColor = WdPalette.success.copy(alpha = 0.7f))
            WdHairline(Modifier.padding(start = 16.dp))
            ProfileRow(Icons.AutoMirrored.Outlined.MenuBook, "WhiteDevil Docs", "API & SDK reference", onSettings)
            WdHairline()
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("VERSION 9.0 • BUILD 92", style = MaterialTheme.typography.labelLarge, color = WdPalette.textMetadata.copy(alpha = 0.5f))
                Text(
                    "FORGE PROTOCOL ACTIVATED",
                    style = MaterialTheme.typography.labelLarge,
                    color = WdPalette.textMetadata.copy(alpha = 0.35f),
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
