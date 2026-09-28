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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.R
import com.whitedevil.UiPolish
import com.whitedevil.ui.app.AttachmentUi
import com.whitedevil.ui.chat.AgentChatScreen
import com.whitedevil.ui.components.WdGlassBar
import com.whitedevil.ui.components.WdGlassCard
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.theme.WdColors

@Composable
fun AgentScreen(host: MainActivity) {
    var overflowOpen by remember { mutableStateOf(false) }
    val ready = host.veniceKeyConfiguredPublic()
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .imePadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Venice", color = WdColors.strong, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    if (ready) "Ready" else "Setup",
                    color = if (ready) WdColors.accent else Color(0xFFE85D5D),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (ready) Color(0x331A1A1E) else Color(0x33CC5555))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                Spacer(Modifier.size(8.dp))
                Box {
                    IconButton(onClick = { overflowOpen = true }, modifier = Modifier.semantics { contentDescription = "Agent options" }) {
                        Icon(painterResource(R.drawable.ic_more), contentDescription = null, tint = WdColors.muted)
                    }
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
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
            Spacer(Modifier.height(8.dp))
            Text(
                "${UiPolish.modelLabel(host.agentSelectedModelPublic())}  ▾",
                color = WdColors.strong,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x331A1A1E))
                    .clickable { host.showModelPicker() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .semantics { contentDescription = "Choose Venice model" },
            )
            if (host.agentShowProgressPublic()) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = WdColors.accent)
            }
            Box(Modifier.weight(1f)) {
                AgentChatScreen(
                    messages = host.chatMessagesPublic(),
                    agentThinking = host.agentThinkingPublic(),
                    scrollTrigger = host.chatScrollTriggerPublic(),
                    onToggleTool = { host.toggleToolMessage(it) },
                    onCopy = { host.copyToClipboard(it) },
                )
            }
            AgentComposer(host)
        }
    }
}

@Composable
private fun AgentComposer(host: MainActivity) {
    val attachments = host.pendingAttachmentsUiPublic()
    if (!host.veniceKeyConfiguredPublic()) {
        WdGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            corner = 14.dp,
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Venice API key required to chat", color = WdColors.strong, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(
                    "Add key",
                    color = Color(0xFF111111),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(WdColors.accent)
                        .clickable { host.showVeniceKeySheet() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
    if (attachments.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            itemsIndexed(attachments) { index, item ->
                AttachmentChip(item) { host.removePendingAttachment(index) }
            }
        }
    }
    WdGlassBar {
        Row(
            Modifier.padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { host.showAttachSheet() }, modifier = Modifier.semantics { contentDescription = "Attach file" }) {
                Icon(painterResource(R.drawable.ic_attach), null, tint = WdColors.accent)
            }
            IconButton(onClick = { host.pasteFromClipboard() }, modifier = Modifier.semantics { contentDescription = "Paste from clipboard" }) {
                Icon(painterResource(R.drawable.ic_clipboard), null, tint = WdColors.muted)
            }
            OutlinedTextField(
                value = host.agentInputTextPublic(),
                onValueChange = { host.setAgentInputText(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Venice…", color = WdColors.muted) },
                minLines = 1,
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = WdColors.strong,
                    unfocusedTextColor = WdColors.strong,
                    focusedBorderColor = Color(0x1FFFFFFF),
                    unfocusedBorderColor = Color(0x1FFFFFFF),
                    focusedContainerColor = Color(0x28000000),
                    unfocusedContainerColor = Color(0x28000000),
                ),
                shape = RoundedCornerShape(16.dp),
                enabled = host.agentComposerEnabledPublic(),
            )
            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(WdColors.accent)
                    .clickable(enabled = host.agentComposerEnabledPublic()) { host.sendAgentMessage() }
                    .semantics { contentDescription = "Send message" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_send), null, tint = Color(0xFF111111))
            }
        }
    }
}

@Composable
private fun AttachmentChip(item: AttachmentUi, onRemove: () -> Unit) {
    WdGlassCard(corner = 14.dp) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (item.kind) {
                    com.whitedevil.agent.Attachments.Kind.VIDEO -> "🎬"
                    com.whitedevil.agent.Attachments.Kind.AUDIO -> "🎵"
                    com.whitedevil.agent.Attachments.Kind.IMAGE -> "🖼"
                    else -> "📄"
                },
                fontSize = 18.sp,
            )
            Column(Modifier.padding(start = 8.dp)) {
                Text(item.name, color = WdColors.strong, fontSize = 12.sp, maxLines = 1)
                Text(
                    if (item.size >= 0) com.whitedevil.agent.Attachments.formatSize(item.size) else item.mime,
                    color = WdColors.muted,
                    fontSize = 11.sp,
                )
            }
            Text(
                "×",
                color = WdColors.muted,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable { onRemove() }
                    .padding(4.dp),
            )
        }
    }
}
