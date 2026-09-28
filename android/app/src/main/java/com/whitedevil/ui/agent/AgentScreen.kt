package com.whitedevil.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.UiPolish
import com.whitedevil.ui.app.AttachmentUi
import com.whitedevil.ui.chat.AgentChatScreen
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.theme.WdPalette

@Composable
fun AgentScreen(host: MainActivity) {
    var overflowOpen by remember { mutableStateOf(false) }
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.size(48.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .clickable { host.showModelPicker() }
                        .padding(vertical = 12.dp)
                        .semantics { contentDescription = "Choose model" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        UiPolish.modelLabel(host.agentSelectedModelPublic()),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Normal,
                    )
                }
                Box {
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(Icons.Outlined.MoreHoriz, null, tint = WdPalette.textSecondary)
                    }
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("System prompt") },
                            onClick = {
                                overflowOpen = false
                                host.showSystemPromptDialog()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Clear chat") },
                            onClick = {
                                overflowOpen = false
                                host.confirmClearAgentChatPublic()
                            },
                        )
                    }
                }
            }
            if (host.agentShowProgressPublic()) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(1.dp),
                    color = WdPalette.accent,
                    trackColor = Color.Transparent,
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                AgentChatScreen(
                    messages = host.chatMessagesPublic(),
                    agentThinking = host.agentThinkingPublic(),
                    scrollTrigger = host.chatScrollTriggerPublic(),
                    onToggleTool = { host.toggleToolMessage(it) },
                    onCopy = { host.copyToClipboard(it) },
                )
            }
            FloatingComposer(host, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).imePadding())
        }
    }
}

@Composable
private fun FloatingComposer(host: MainActivity, modifier: Modifier = Modifier) {
    val attachments = host.pendingAttachmentsUiPublic()
    val needsKey = !host.veniceKeyConfiguredPublic()
    Column(modifier) {
        if (needsKey) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clickable { host.showVeniceKeySheet() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.Key, null, tint = WdPalette.accent, modifier = Modifier.size(16.dp))
                Text(
                    " Add API key to chat",
                    style = MaterialTheme.typography.labelMedium,
                    color = WdPalette.accent,
                )
            }
        }
        if (attachments.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                itemsIndexed(attachments) { index, item ->
                    AttachmentChip(item) { host.removePendingAttachment(index) }
                }
            }
        }
        Surface(shape = RoundedCornerShape(26.dp), color = WdPalette.surface, shadowElevation = 0.dp) {
            Row(
                Modifier.padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { host.showAttachSheet() },
                    modifier = Modifier.size(40.dp).semantics { contentDescription = "Attach" },
                ) {
                    Icon(Icons.Outlined.Add, null, tint = WdPalette.textSecondary)
                }
                BasicTextField(
                    value = host.agentInputTextPublic(),
                    onValueChange = { host.setAgentInputText(it) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                    enabled = host.agentComposerEnabledPublic(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = WdPalette.text),
                    cursorBrush = SolidColor(WdPalette.accent),
                    maxLines = 6,
                    decorationBox = { inner ->
                        Box {
                            if (host.agentInputTextPublic().isEmpty()) {
                                Text("Ask anything", style = MaterialTheme.typography.bodyLarge, color = WdPalette.textTertiary)
                            }
                            inner()
                        }
                    },
                )
                IconButton(
                    onClick = { host.sendAgentMessage() },
                    enabled = host.agentComposerEnabledPublic(),
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (host.agentComposerEnabledPublic()) WdPalette.accent else WdPalette.surfaceHover)
                        .semantics { contentDescription = "Send" },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = WdPalette.onAccent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun AttachmentChip(item: AttachmentUi, onRemove: () -> Unit) {
    Text(
        item.name.take(20) + if (item.name.length > 20) "…" else "",
        style = MaterialTheme.typography.labelSmall,
        color = WdPalette.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(WdPalette.surface)
            .clickable { onRemove() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
