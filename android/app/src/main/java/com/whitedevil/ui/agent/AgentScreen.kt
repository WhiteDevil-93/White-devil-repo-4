package com.whitedevil.ui.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ExpandMore
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
import androidx.compose.ui.unit.dp
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.UiPolish
import com.whitedevil.ui.app.AttachmentUi
import com.whitedevil.ui.chat.AgentChatScreen
import com.whitedevil.ui.components.WdHairline
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.components.WdTopBar
import com.whitedevil.ui.theme.WdDimens
import com.whitedevil.ui.theme.WdPalette

private const val DEFAULT_AGENT_SUBTITLE = "On-device agent"

@Composable
fun AgentScreen(host: MainActivity) {
    var overflowOpen by remember { mutableStateOf(false) }
    val hasKey = host.veniceKeyConfiguredPublic()
    val hasConversation = host.agentHasConversationPublic()
    val status = host.agentStatusSubtitlePublic()
    val showStatus = status != DEFAULT_AGENT_SUBTITLE
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            WdTopBar(
                title = "Venice",
                subtitle = if (showStatus) status else null,
                trailing = {
                    Box {
                        IconButton(
                            onClick = { overflowOpen = true },
                            modifier = Modifier.size(WdDimens.iconTap),
                        ) {
                            Icon(Icons.Outlined.MoreHoriz, null, tint = WdPalette.textSecondary, modifier = Modifier.size(20.dp))
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
                },
            )
            if (hasKey) {
                Row(
                    Modifier
                        .padding(horizontal = WdDimens.screenHorizontal)
                        .clickable { host.showModelPicker() }
                        .semantics { contentDescription = "Choose model" }
                        .padding(bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        UiPolish.modelLabel(host.agentSelectedModelPublic()),
                        style = MaterialTheme.typography.labelMedium,
                        color = WdPalette.textMetadata,
                    )
                    Icon(
                        Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = WdPalette.textMetadata,
                    )
                }
            }
            if (host.agentShowProgressPublic()) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(1.dp),
                    color = WdPalette.accent,
                    trackColor = Color.Transparent,
                )
            }
            WdHairline()
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                when {
                    !hasKey -> AgentSetupEmptyState(
                        onAddKey = { host.showVeniceKeySheet() },
                        modifier = Modifier.padding(horizontal = WdDimens.screenHorizontal),
                    )
                    !hasConversation -> AgentReadyEmptyState(
                        modifier = Modifier.padding(horizontal = WdDimens.screenHorizontal),
                    )
                    else -> AgentChatScreen(
                        messages = host.chatMessagesPublic(),
                        agentThinking = host.agentThinkingPublic(),
                        scrollTrigger = host.chatScrollTriggerPublic(),
                        onToggleTool = { host.toggleToolMessage(it) },
                        onCopy = { host.copyToClipboard(it) },
                        includeInfoMessages = false,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = WdDimens.screenHorizontal),
                    )
                }
            }
            if (hasKey) {
                CompactComposer(
                    host,
                    Modifier
                        .fillMaxWidth()
                        .imePadding(),
                )
            }
        }
    }
}

@Composable
private fun CompactComposer(host: MainActivity, modifier: Modifier = Modifier) {
    val attachments = host.pendingAttachmentsUiPublic()
    val input = host.agentInputTextPublic()
    val canSend = host.agentComposerEnabledPublic() && input.isNotBlank()
    Column(modifier) {
        WdHairline()
        if (attachments.isNotEmpty()) {
            LazyRow(modifier = Modifier.padding(start = WdDimens.screenHorizontal, top = 6.dp, bottom = 4.dp)) {
                itemsIndexed(attachments) { index, item ->
                    AttachmentChip(item) { host.removePendingAttachment(index) }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = WdDimens.screenHorizontal - 4.dp,
                    vertical = WdDimens.composerVertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { host.showAttachSheet() },
                modifier = Modifier.size(WdDimens.iconTap).semantics { contentDescription = "Attach file" },
            ) {
                Icon(Icons.Outlined.AttachFile, null, modifier = Modifier.size(20.dp), tint = WdPalette.textSecondary)
            }
            BasicTextField(
                value = input,
                onValueChange = { host.setAgentInputText(it) },
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
                enabled = host.agentComposerEnabledPublic(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = WdPalette.text),
                cursorBrush = SolidColor(WdPalette.accent),
                maxLines = 5,
                decorationBox = { inner ->
                    Box {
                        if (input.isEmpty()) {
                            Text("Message", style = MaterialTheme.typography.bodyMedium, color = WdPalette.textMetadata)
                        }
                        inner()
                    }
                },
            )
            IconButton(
                onClick = { host.sendAgentMessage() },
                enabled = canSend,
                modifier = Modifier.size(WdDimens.iconTap).semantics { contentDescription = "Send" },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = if (canSend) WdPalette.accent else WdPalette.textMetadata,
                )
            }
        }
    }
}

@Composable
private fun AttachmentChip(item: AttachmentUi, onRemove: () -> Unit) {
    Text(
        item.name.take(20) + if (item.name.length > 20) "…" else "",
        style = MaterialTheme.typography.labelMedium,
        color = WdPalette.textSecondary,
        modifier = Modifier
            .padding(end = 6.dp)
            .clickable { onRemove() },
    )
}
