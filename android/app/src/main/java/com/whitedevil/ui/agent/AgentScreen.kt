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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.R
import com.whitedevil.UiPolish
import com.whitedevil.ui.app.AttachmentUi
import com.whitedevil.ui.chat.AgentChatScreen
import com.whitedevil.ui.components.WdComposerDock
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.theme.WdColors

@Composable
fun AgentScreen(host: MainActivity) {
    var overflowOpen by remember { mutableStateOf(false) }
    val ready = host.veniceKeyConfiguredPublic()
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Venice", color = WdColors.strong, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "On-device agent",
                            color = WdColors.muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    StatusChip(ready)
                    Box {
                        IconButton(
                            onClick = { overflowOpen = true },
                            modifier = Modifier.semantics { contentDescription = "Agent options" },
                        ) {
                            Icon(painterResource(R.drawable.ic_more), contentDescription = null, tint = WdColors.muted)
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
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${UiPolish.modelLabel(host.agentSelectedModelPublic())}  ▾",
                        color = WdColors.strong,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x281A1A1E))
                            .clickable { host.showModelPicker() }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                            .semantics { contentDescription = "Choose Venice model" },
                    )
                }
                if (host.agentShowProgressPublic()) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp)),
                        color = WdColors.accent,
                        trackColor = Color(0x221A1A1E),
                    )
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                AgentChatScreen(
                    messages = host.chatMessagesPublic(),
                    agentThinking = host.agentThinkingPublic(),
                    scrollTrigger = host.chatScrollTriggerPublic(),
                    onToggleTool = { host.toggleToolMessage(it) },
                    onCopy = { host.copyToClipboard(it) },
                )
            }
            AgentComposerDock(host, Modifier.fillMaxWidth().imePadding())
        }
    }
}

@Composable
private fun StatusChip(ready: Boolean) {
    Text(
        if (ready) "Ready" else "Setup",
        color = if (ready) WdColors.accent else Color(0xFFE85D5D),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (ready) Color(0x281A1A1E) else Color(0x28CC5555))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun AgentComposerDock(host: MainActivity, modifier: Modifier = Modifier) {
    val attachments = host.pendingAttachmentsUiPublic()
    val needsKey = !host.veniceKeyConfiguredPublic()
    WdComposerDock(modifier) {
        if (needsKey) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Add a Venice API key to start chatting",
                    color = WdColors.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { host.showVeniceKeySheet() }) {
                    Text("Add key", color = WdColors.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            HorizontalDivider(color = Color(0x14FFFFFF), thickness = 1.dp)
            Spacer(Modifier.height(6.dp))
        }
        if (attachments.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                itemsIndexed(attachments) { index, item ->
                    AttachmentChip(item) { host.removePendingAttachment(index) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(
                onClick = { host.showAttachSheet() },
                modifier = Modifier
                    .size(40.dp)
                    .semantics { contentDescription = "Attach file" },
            ) {
                Icon(painterResource(R.drawable.ic_attach), null, tint = WdColors.accent, modifier = Modifier.size(22.dp))
            }
            IconButton(
                onClick = { host.pasteFromClipboard() },
                modifier = Modifier
                    .size(40.dp)
                    .semantics { contentDescription = "Paste from clipboard" },
            ) {
                Icon(painterResource(R.drawable.ic_clipboard), null, tint = WdColors.muted, modifier = Modifier.size(22.dp))
            }
            BasicTextField(
                value = host.agentInputTextPublic(),
                onValueChange = { host.setAgentInputText(it) },
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp),
                enabled = host.agentComposerEnabledPublic(),
                textStyle = TextStyle(color = WdColors.strong, fontSize = 15.sp, lineHeight = 20.sp),
                cursorBrush = SolidColor(WdColors.accent),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (host.agentInputTextPublic().isEmpty()) {
                            Text("Message Venice…", color = WdColors.muted, fontSize = 15.sp)
                        }
                        inner()
                    }
                },
            )
            Box(
                modifier = Modifier
                    .padding(start = 4.dp, bottom = 2.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (host.agentComposerEnabledPublic()) WdColors.accent else Color(0x55CDB88F))
                    .clickable(enabled = host.agentComposerEnabledPublic()) { host.sendAgentMessage() }
                    .semantics { contentDescription = "Send message" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_send), null, tint = Color(0xFF111111), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun AttachmentChip(item: AttachmentUi, onRemove: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x281A1A1E))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (item.kind) {
                com.whitedevil.agent.Attachments.Kind.VIDEO -> "🎬"
                com.whitedevil.agent.Attachments.Kind.AUDIO -> "🎵"
                com.whitedevil.agent.Attachments.Kind.IMAGE -> "🖼"
                else -> "📄"
            },
            fontSize = 16.sp,
        )
        Column(Modifier.padding(start = 6.dp)) {
            Text(item.name, color = WdColors.strong, fontSize = 11.sp, maxLines = 1)
            Text(
                if (item.size >= 0) com.whitedevil.agent.Attachments.formatSize(item.size) else item.mime,
                color = WdColors.muted,
                fontSize = 10.sp,
            )
        }
        Text(
            "×",
            color = WdColors.muted,
            modifier = Modifier
                .padding(start = 6.dp)
                .clickable { onRemove() }
                .padding(4.dp),
        )
    }
}
