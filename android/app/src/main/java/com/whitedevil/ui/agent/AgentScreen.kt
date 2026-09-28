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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.R
import com.whitedevil.UiPolish
import com.whitedevil.ui.app.AttachmentUi
import com.whitedevil.ui.chat.AgentChatScreen
import com.whitedevil.ui.components.WdInlineField
import com.whitedevil.ui.components.WdInputDock
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.theme.WdPalette

@Composable
fun AgentScreen(host: MainActivity) {
    var overflowOpen by remember { mutableStateOf(false) }
    val ready = host.veniceKeyConfiguredPublic()
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Venice", style = MaterialTheme.typography.titleLarge)
                    Text(
                        UiPolish.modelLabel(host.agentSelectedModelPublic()),
                        style = MaterialTheme.typography.labelMedium,
                        color = WdPalette.accent,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable { host.showModelPicker() }
                            .semantics { contentDescription = "Choose model" },
                    )
                }
                ConnectionDot(ready)
                Spacer(Modifier.size(4.dp))
                Box {
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(painterResource(R.drawable.ic_more), null, tint = WdPalette.textSecondary)
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
            if (host.agentShowProgressPublic()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = WdPalette.accent,
                    trackColor = Color.Transparent,
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                AgentChatScreen(
                    messages = host.chatMessagesPublic(),
                    agentThinking = host.agentThinkingPublic(),
                    scrollTrigger = host.chatScrollTriggerPublic(),
                    onToggleTool = { host.toggleToolMessage(it) },
                    onCopy = { host.copyToClipboard(it) },
                )
            }
            AgentComposer(host, Modifier.fillMaxWidth().imePadding())
        }
    }
}

@Composable
private fun ConnectionDot(ready: Boolean) {
    Text(
        if (ready) "Live" else "Setup",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = if (ready) WdPalette.success else WdPalette.danger,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !ready) { /* chip is visual only when ready */ }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun AgentComposer(host: MainActivity, modifier: Modifier = Modifier) {
    val attachments = host.pendingAttachmentsUiPublic()
    val needsKey = !host.veniceKeyConfiguredPublic()
    WdInputDock(modifier) {
        if (needsKey) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Venice API key required",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { host.showVeniceKeySheet() }) {
                    Text("Add key", color = WdPalette.accent, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (attachments.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                itemsIndexed(attachments) { index, item ->
                    AttachmentChip(item) { host.removePendingAttachment(index) }
                }
            }
        }
        WdInlineField {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { host.showAttachSheet() },
                    modifier = Modifier.size(44.dp).semantics { contentDescription = "Attach" },
                ) {
                    Icon(painterResource(R.drawable.ic_attach), null, tint = WdPalette.textSecondary, modifier = Modifier.size(22.dp))
                }
                BasicTextField(
                    value = host.agentInputTextPublic(),
                    onValueChange = { host.setAgentInputText(it) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 12.dp),
                    enabled = host.agentComposerEnabledPublic(),
                    textStyle = TextStyle(color = WdPalette.text, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                    cursorBrush = SolidColor(WdPalette.accent),
                    decorationBox = { inner ->
                        Box {
                            if (host.agentInputTextPublic().isEmpty()) {
                                Text("Message", style = MaterialTheme.typography.bodyLarge, color = WdPalette.textTertiary)
                            }
                            inner()
                        }
                    },
                )
                Box(
                    Modifier
                        .padding(end = 4.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (host.agentComposerEnabledPublic()) WdPalette.accent else WdPalette.surfaceHover)
                        .clickable(enabled = host.agentComposerEnabledPublic()) { host.sendAgentMessage() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_send), null, tint = WdPalette.onAccent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun AttachmentChip(item: AttachmentUi, onRemove: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onRemove() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.name.take(18) + if (item.name.length > 18) "…" else "",
            style = MaterialTheme.typography.labelMedium,
            color = WdPalette.text,
        )
        Text(" ×", style = MaterialTheme.typography.labelMedium, color = WdPalette.textTertiary)
    }
}
