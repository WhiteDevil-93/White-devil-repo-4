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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whitedevil.MainActivity
import com.whitedevil.ui.theme.WdColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        if (last > 0) {
            listState.animateScrollToItem((last - 1).coerceAtLeast(0))
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.id }) { msg ->
            ChatBubbleRow(msg, onToggleTool, onCopy)
        }
        if (agentThinking) {
            item(key = "typing") {
                TypingRow()
            }
        }
    }
}

@Composable
private fun TypingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(Color(0x442A2A30), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("V", color = WdColors.strong, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.size(8.dp))
        Column(
            modifier = Modifier
                .background(Color(0x331A1A1E), RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text("Venice is thinking", color = WdColors.strong, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            ThinkingDots()
        }
    }
}

@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    val a1 = transition.animateFloat(
        0.3f,
        1f,
        infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "d1",
    )
    val a2 = transition.animateFloat(
        0.3f,
        1f,
        infiniteRepeatable(tween(600, delayMillis = 150, easing = LinearEasing), RepeatMode.Reverse),
        label = "d2",
    )
    val a3 = transition.animateFloat(
        0.3f,
        1f,
        infiniteRepeatable(tween(600, delayMillis = 300, easing = LinearEasing), RepeatMode.Reverse),
        label = "d3",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(a1, a2, a3).forEach { anim ->
            Box(
                Modifier
                    .size(8.dp)
                    .alpha(anim.value)
                    .background(WdColors.accent, CircleShape),
            )
        }
    }
}

@Composable
private fun ChatBubbleRow(
    msg: ChatUiMessage,
    onToggleTool: (Long) -> Unit,
    onCopy: (String) -> Unit,
) {
    when {
        msg.isInfo() -> InfoBubble(msg)
        msg.isUser() -> UserBubble(msg, onCopy)
        msg.isTool() -> ToolBubble(msg, onToggleTool, onCopy)
        else -> AssistantBubble(msg, onCopy)
    }
}

@Composable
private fun InfoBubble(msg: ChatUiMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color(0x443D3528), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("✦", color = WdColors.accent, fontSize = 16.sp)
        }
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x2A1E1810), RoundedCornerShape(18.dp))
                .padding(14.dp),
        ) {
            Text(msg.sender, color = WdColors.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(msg.message, color = Color(0xFFD8D2C8), fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun UserBubble(msg: ChatUiMessage, onCopy: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(Color(0x3D4A3828), RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                .padding(14.dp)
                .clickable(enabled = msg.message.length > 24) { onCopy(msg.message) },
        ) {
            BubbleHeader(msg.sender, WdColors.accent, showTime = true)
            Text(msg.message, color = WdColors.fg, fontSize = 14.sp, lineHeight = 20.sp)
        }
        Spacer(Modifier.size(8.dp))
        Avatar("Y", Color(0x554A3828), WdColors.accent)
    }
}

@Composable
private fun AssistantBubble(msg: ChatUiMessage, onCopy: (String) -> Unit) {
    val fg = when (msg.role) {
        MainActivity.ROLE_ERROR -> Color(0xFFFF6B6B)
        else -> WdColors.fg
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Avatar("V", Color(0x442A2A30), WdColors.strong)
        Spacer(Modifier.size(8.dp))
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(Color(0x331A1A1E), RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp))
                .padding(14.dp)
                .clickable(enabled = msg.message.length > 24) { onCopy(msg.message) },
        ) {
            BubbleHeader(msg.sender, fg, showTime = true)
            Text(msg.message, color = fg, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun ToolBubble(msg: ChatUiMessage, onToggleTool: (Long) -> Unit, onCopy: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(Color(0x331F2E3D), RoundedCornerShape(14.dp))
            .clickable { onToggleTool(msg.id) }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(msg.sender, color = Color(0xFF82B6E8), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                if (msg.toolExpanded) "Hide details" else "Show details",
                color = WdColors.muted,
                fontSize = 11.sp,
            )
        }
        if (msg.toolExpanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                msg.message,
                color = WdColors.muted,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onCopy(msg.message) },
            )
        }
    }
}

@Composable
private fun BubbleHeader(sender: String, color: Color, showTime: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(sender, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (showTime) {
            Text(
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                color = WdColors.muted,
                fontSize = 12.sp,
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Avatar(letter: String, bg: Color, fg: Color) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
