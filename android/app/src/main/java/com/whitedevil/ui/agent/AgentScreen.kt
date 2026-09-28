package com.whitedevil.ui.agent

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.whitedevil.ui.components.WdScreenTitle
import com.whitedevil.ui.theme.WdPalette

@Composable
fun AgentScreen(host: MainActivity) {
    var overflowOpen by remember { mutableStateOf(false) }
    val hasKey = host.veniceKeyConfiguredPublic()
    val hasConversation = host.agentHasConversationPublic()
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    WdScreenTitle(
                        title = "Venice",
                        subtitle = host.agentStatusSubtitlePublic(),
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Outlined.MoreHoriz, null, tint = WdPalette.textSecondary)
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            DropdownMenuItem(text = { Text("Paste from clipboard") }, onClick = {
                                overflowOpen = false
                                host.pasteFromClipboard()
                            })
                            DropdownMenuItem(text = { Text("System prompt") }, onClick = {
                                overflowOpen = false
                                host.showSystemPromptDialog()
                            })
                            DropdownMenuItem(text = { Text("Clear chat") }, onClick = {
                                overflowOpen = false
                                host.confirmClearAgentChatPublic()
                            })
                        }
                    }
                }
                Text(
                    UiPolish.modelLabel(host.agentSelectedModelPublic()) + "  ▾",
                    style = MaterialTheme.typography.labelMedium,
                    color = WdPalette.accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable { host.showModelPicker() }
                        .semantics { contentDescription = "Choose model" },
                )
            }
            if (host.agentShowProgressPublic()) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = WdPalette.accent,
                    trackColor = Color.Transparent,
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 48.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                when {
                    !hasKey -> AgentSetupEmptyState(onAddKey = { host.showVeniceKeySheet() })
                    !hasConversation -> AgentReadyEmptyState()
                    else -> AgentChatScreen(
                        messages = host.chatMessagesPublic(),
                        agentThinking = host.agentThinkingPublic(),
                        scrollTrigger = host.chatScrollTriggerPublic(),
                        onToggleTool = { host.toggleToolMessage(it) },
                        onCopy = { host.copyToClipboard(it) },
                        includeInfoMessages = false,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                    )
                }
            }
            CompactComposer(
                host,
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .imePadding(),
            )
        }
    }
}

@Composable
private fun CompactComposer(host: MainActivity, modifier: Modifier = Modifier) {
    val attachments = host.pendingAttachmentsUiPublic()
    Column(modifier) {
        if (attachments.isNotEmpty()) {
            LazyRow(modifier = Modifier.padding(bottom = 8.dp)) {
                itemsIndexed(attachments) { index, item ->
                    AttachmentChip(item) { host.removePendingAttachment(index) }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, WdPalette.stroke, RoundedCornerShape(22.dp))
                .padding(start = 2.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { host.showAttachSheet() },
                modifier = Modifier.size(44.dp).semantics { contentDescription = "Attach file" },
            ) {
                Icon(Icons.Outlined.AttachFile, null, tint = WdPalette.textSecondary)
            }
            BasicTextField(
                value = host.agentInputTextPublic(),
                onValueChange = { host.setAgentInputText(it) },
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
                enabled = host.agentComposerEnabledPublic(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = WdPalette.text),
                cursorBrush = SolidColor(WdPalette.accent),
                maxLines = 6,
                decorationBox = { inner ->
                    Box {
                        if (host.agentInputTextPublic().isEmpty()) {
                            Text("Message Venice", style = MaterialTheme.typography.bodyLarge, color = WdPalette.textSecondary)
                        }
                        inner()
                    }
                },
            )
            IconButton(
                onClick = { host.sendAgentMessage() },
                enabled = host.agentComposerEnabledPublic(),
                modifier = Modifier.semantics { contentDescription = "Send" },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    null,
                    tint = if (host.agentComposerEnabledPublic()) WdPalette.accent else WdPalette.textMetadata,
                )
            }
        }
    }
}

@Composable
private fun AttachmentChip(item: AttachmentUi, onRemove: () -> Unit) {
    Text(
        item.name.take(24) + if (item.name.length > 24) "…" else "",
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .padding(end = 8.dp)
            .clickable { onRemove() },
    )
}
