package com.whitedevil.ui.agent

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Tune
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
import com.whitedevil.ui.components.WdHairline
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.theme.WdDimens
import com.whitedevil.ui.theme.WdPalette

@Composable
fun AgentScreen(host: MainActivity) {
    var overflowOpen by remember { mutableStateOf(false) }
    val hasKey = host.veniceKeyConfiguredPublic()
    val hasConversation = host.agentHasConversationPublic()
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (!hasKey) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "AGENT CONFIG: VENICE",
                        style = MaterialTheme.typography.labelLarge,
                        color = WdPalette.text,
                        fontWeight = FontWeight.Bold,
                    )
                }
                WdHairline()
            } else {
                AgentChatHeader(
                    modelLabel = UiPolish.modelLabel(host.agentSelectedModelPublic()),
                    onOverflow = { overflowOpen = true },
                    overflowOpen = overflowOpen,
                    onDismissOverflow = { overflowOpen = false },
                    host = host,
                )
            }
            if (host.agentShowProgressPublic()) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = WdPalette.accent,
                    trackColor = Color.Transparent,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !hasKey -> AgentSetupEmptyState(
                        onAddKey = { host.showVeniceKeySheet() },
                        onPickModel = { host.showModelPicker() },
                        modelLabel = UiPolish.modelLabel(host.agentSelectedModelPublic()),
                    )
                    !hasConversation -> AgentReadyEmptyState(Modifier.align(Alignment.TopStart))
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
                        onSpeak = { host.speakText(it) },
                        onPreview = { host.previewArtifact(it) },
                    )
                }
            }
            if (hasKey) {
                DeepSpaceComposer(host, Modifier.fillMaxWidth().imePadding())
            }
        }
    }
}

@Composable
private fun AgentChatHeader(
    modelLabel: String,
    onOverflow: () -> Unit,
    overflowOpen: Boolean,
    onDismissOverflow: () -> Unit,
    host: MainActivity,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Venice", style = MaterialTheme.typography.titleLarge)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable { host.showModelPicker() }
                    .semantics { contentDescription = "Change agent model" },
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(WdPalette.accent),
                )
                Text(
                    " $modelLabel • ACTIVE",
                    style = MaterialTheme.typography.labelLarge,
                    color = WdPalette.textMetadata,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Text(
                    " ⌄",
                    style = MaterialTheme.typography.labelLarge,
                    color = WdPalette.textMetadata,
                )
            }
        }
        Box {
            IconButton(
                onClick = onOverflow,
                modifier = Modifier
                    .size(40.dp)
                    .border(1.dp, WdPalette.stroke, RoundedCornerShape(2.dp)),
            ) {
                Icon(Icons.Outlined.Tune, null, tint = WdPalette.textSecondary, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = overflowOpen, onDismissRequest = onDismissOverflow) {
                DropdownMenuItem(text = { Text("New chat") }, onClick = {
                    onDismissOverflow()
                    host.startNewChat()
                })
                DropdownMenuItem(text = { Text("Chats") }, onClick = {
                    onDismissOverflow()
                    host.openChats()
                })
                DropdownMenuItem(text = { Text("Change model") }, onClick = {
                    onDismissOverflow()
                    host.showModelPicker()
                })
                DropdownMenuItem(text = { Text("Paste from clipboard") }, onClick = {
                    onDismissOverflow()
                    host.pasteFromClipboard()
                })
                DropdownMenuItem(text = { Text("System prompt") }, onClick = {
                    onDismissOverflow()
                    host.showSystemPromptDialog()
                })
                DropdownMenuItem(text = { Text("Clear chat") }, onClick = {
                    onDismissOverflow()
                    host.confirmClearAgentChatPublic()
                })
            }
        }
    }
    WdHairline()
}

@Composable
private fun DeepSpaceComposer(host: MainActivity, modifier: Modifier = Modifier) {
    val attachments = host.pendingAttachmentsUiPublic()
    val input = host.agentInputTextPublic()
    val canSend = host.agentComposerEnabledPublic() && (input.isNotBlank() || attachments.isNotEmpty())
    val isBusy = !host.agentComposerEnabledPublic()
    Column(
        modifier
            .background(WdPalette.bg)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (attachments.isNotEmpty()) {
            LazyRow(modifier = Modifier.padding(bottom = 8.dp)) {
                itemsIndexed(attachments) { index, item ->
                    Text(
                        item.name.take(20),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clickable { host.removePendingAttachment(index) },
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, WdPalette.stroke, RoundedCornerShape(2.dp))
                .background(WdPalette.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var shotwriterOpen by remember { mutableStateOf(false) }
            ShotwriterDialog(
                expanded = shotwriterOpen,
                onDismissRequest = { shotwriterOpen = false },
                onRewrite = { model, res, ratio, len ->
                    val currentText = host.agentInputTextPublic()
                    val configStr = listOf(model.name, res, ratio, len).filter { it.isNotBlank() }.joinToString(", ")
                    host.setAgentInputText("[Shotwriter: $configStr]\n$currentText")
                }
            )

            IconButton(
                onClick = { host.showAttachSheet() },
                modifier = Modifier.size(WdDimens.iconTap).semantics { contentDescription = "Attach file" },
            ) {
                Icon(Icons.Outlined.AttachFile, null, tint = WdPalette.textMetadata, modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = { shotwriterOpen = true },
                modifier = Modifier.size(WdDimens.iconTap).semantics { contentDescription = "Shotwriter" },
            ) {
                Icon(androidx.compose.material.icons.Icons.Outlined.AutoAwesome, null, tint = WdPalette.textMetadata, modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = { host.startVoiceInput() },
                enabled = host.agentComposerEnabledPublic(),
                modifier = Modifier.size(WdDimens.iconTap).semantics { contentDescription = "Speak your message" },
            ) {
                Icon(Icons.Outlined.Mic, null, tint = WdPalette.textMetadata, modifier = Modifier.size(18.dp))
            }
            BasicTextField(
                value = input,
                onValueChange = { host.setAgentInputText(it) },
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
                enabled = host.agentComposerEnabledPublic(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = WdPalette.text),
                cursorBrush = SolidColor(WdPalette.accent),
                maxLines = 5,
                decorationBox = { inner ->
                    Box {
                        if (input.isEmpty()) {
                            Text("Command Venice...", style = MaterialTheme.typography.bodyMedium, color = WdPalette.textMetadata)
                        }
                        inner()
                    }
                },
            )
            if (isBusy) {
                IconButton(
                    onClick = { host.stopAgentChat() },
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(WdPalette.accentLight)
                        .semantics { contentDescription = "Stop" },
                ) {
                    androidx.compose.material3.Text(
                        "■",
                        color = WdPalette.onLightButton,
                        modifier = Modifier.padding(bottom = 2.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            } else {
                IconButton(
                    onClick = { host.sendAgentMessage() },
                    enabled = canSend,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (canSend) WdPalette.accent else WdPalette.stroke)
                        .semantics { contentDescription = "Send" },
                ) {
                    Icon(
                        Icons.Outlined.KeyboardArrowUp,
                        null,
                        tint = WdPalette.onAccent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
