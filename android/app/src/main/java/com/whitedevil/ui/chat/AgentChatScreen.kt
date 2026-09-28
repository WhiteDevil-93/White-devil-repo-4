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
import com.whitedevil.ui.theme.WdPalette

@Composable
fun AgentChatScreen(
    messages: SnapshotStateList<ChatUiMessage>,
    agentThinking: Boolean,
    scrollTrigger: Int,
    onToggleTool: (Long) -> Unit,
    onCopy: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(scrollTrigger, agentThinking) {
        val last = messages.size + if (agentThinking) 1 else 0
        if (last > 0) listState.animateScrollToItem((last - 1).coerceAtLeast(0))
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(messages, key = { it.id }) { msg ->
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
        Text("Thinking", style = MaterialTheme.typography.bodySmall, color = WdPalette.textTertiary)
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
        color = WdPalette.textTertiary,
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
            style = MaterialTheme.typography.bodyLarge,
            color = WdPalette.text,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(WdPalette.userBubble, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .clickable(enabled = msg.message.length > 24) { onCopy(msg.message) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun AssistantBubble(msg: ChatUiMessage, onCopy: (String) -> Unit) {
    val fg = if (msg.role == MainActivity.ROLE_ERROR) WdPalette.errorText else WdPalette.text
    Text(
        msg.message,
        style = MaterialTheme.typography.bodyLarge,
        color = fg,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 340.dp)
            .clickable(enabled = msg.message.length > 24) { onCopy(msg.message) }
            .padding(end = 8.dp),
    )
}

@Composable
private fun ToolBubble(msg: ChatUiMessage, onToggleTool: (Long) -> Unit, onCopy: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(WdPalette.surface, RoundedCornerShape(12.dp))
            .clickable { onToggleTool(msg.id) }
            .padding(12.dp),
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
                color = WdPalette.textTertiary,
            )
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
