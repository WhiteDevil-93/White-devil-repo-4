package com.whitedevil.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.whitedevil.MainActivity
import com.whitedevil.ui.theme.WdDimens
import com.whitedevil.ui.theme.WdPalette

@Composable
fun AgentChatScreen(
    messages: SnapshotStateList<ChatUiMessage>,
    agentThinking: Boolean,
    scrollTrigger: Int,
    onToggleTool: (Long) -> Unit,
    onCopy: (String) -> Unit,
    includeInfoMessages: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val visible = if (includeInfoMessages) messages else messages.filter { !it.isInfo() }
    val listState = rememberLazyListState()
    LaunchedEffect(scrollTrigger, agentThinking) {
        val last = messages.size + if (agentThinking) 1 else 0
        if (last > 0) listState.animateScrollToItem((last - 1).coerceAtLeast(0))
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(visible, key = { it.id }) { msg ->
            ChatBubbleRow(msg, onToggleTool, onCopy)
        }
        if (agentThinking) {
            item(key = "typing") { TypingRow() }
        }
    }
}

@Composable
private fun TypingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ThinkingDots()
        Spacer(Modifier.size(8.dp))
        Text("Thinking", style = MaterialTheme.typography.labelMedium, color = WdPalette.textSecondary)
    }
}

@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    val a1 = transition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse), label = "d1")
    val a2 = transition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(450, delayMillis = 100, easing = LinearEasing), RepeatMode.Reverse), label = "d2")
    val a3 = transition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(450, delayMillis = 200, easing = LinearEasing), RepeatMode.Reverse), label = "d3")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(a1, a2, a3).forEach { anim ->
            Box(Modifier.size(5.dp).alpha(anim.value).background(WdPalette.accent, CircleShape))
        }
    }
}

@Composable
private fun ChatBubbleRow(msg: ChatUiMessage, onToggleTool: (Long) -> Unit, onCopy: (String) -> Unit) {
    when {
        msg.isInfo() -> InfoLine(msg)
        msg.isUser() -> UserBubble(msg, onCopy)
        msg.isTool() -> ToolBubble(msg, onToggleTool, onCopy)
        else -> AssistantBubble(msg, onCopy)
    }
}

@Composable
private fun InfoLine(msg: ChatUiMessage) {
    Text(
        msg.message,
        style = MaterialTheme.typography.bodySmall,
        color = WdPalette.textMetadata,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

@Composable
private fun UserBubble(msg: ChatUiMessage, onCopy: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            msg.message,
            style = MaterialTheme.typography.bodyMedium,
            color = WdPalette.onAccent,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(WdPalette.accent, RoundedCornerShape(2.dp))
                .clickable(enabled = msg.message.length > 24) { onCopy(msg.message) }
                .padding(horizontal = 10.dp, vertical = 7.dp),
        )
        CopyButton(msg.message, onCopy)
    }
}

@Composable
private fun AssistantBubble(msg: ChatUiMessage, onCopy: (String) -> Unit) {
    val fg = if (msg.role == MainActivity.ROLE_ERROR) WdPalette.errorText else WdPalette.text
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            msg.message,
            style = MaterialTheme.typography.bodyMedium,
            color = fg,
            modifier = Modifier.weight(1f).widthIn(max = 340.dp).padding(end = 4.dp),
        )
        CopyButton(msg.message, onCopy)
    }
}

@Composable
private fun CopyButton(text: String, onCopy: (String) -> Unit) {
    IconButton(
        onClick = { onCopy(text) },
        enabled = text.isNotBlank(),
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            Icons.Outlined.ContentCopy,
            contentDescription = "Copy message",
            tint = WdPalette.textMetadata,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ToolBubble(msg: ChatUiMessage, onToggleTool: (Long) -> Unit, onCopy: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(WdPalette.surface, RoundedCornerShape(WdDimens.controlRadius))
            .clickable { onToggleTool(msg.id) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                msg.sender,
                style = MaterialTheme.typography.labelMedium,
                color = WdPalette.accent,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (msg.toolExpanded) "−" else "+",
                style = MaterialTheme.typography.titleMedium,
                color = WdPalette.textMetadata,
            )
            CopyButton(msg.message, onCopy)
        }
        if (msg.toolExpanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                msg.message,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = WdPalette.textSecondary,
                modifier = Modifier.clickable { onCopy(msg.message) },
            )
        }
    }
}
