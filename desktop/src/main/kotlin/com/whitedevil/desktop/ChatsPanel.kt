package com.whitedevil.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val whenFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault())

/** Past Venice chats: search across all of them, open one, or delete it. */
@Composable
fun ChatsPanel(session: AgentSession, busy: Boolean, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var version by remember { mutableStateOf(0) }
    val shown = remember(query, version) { session.search(query) }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Forge.Panel,
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE", color = Forge.Acc, fontWeight = FontWeight.SemiBold) } },
        title = { Text("Chats (${session.list().size})", color = Forge.Fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(8.dp), colors = forgeFieldColors(), label = { Text("Search every chat", color = Forge.Dim, fontSize = 11.sp) })
                if (busy) Tip("Venice is working; wait for it to finish before switching chats.")
                Column(Modifier.fillMaxWidth().height(400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (shown.isEmpty()) Tip(if (query.isBlank()) "No saved chats yet. Your conversations are kept here automatically." else "Nothing matches \"$query\".")
                    shown.forEach { c ->
                        val open = c.id == session.currentId
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (open) Forge.Panel2 else Forge.Well)
                                .border(1.dp, if (open) Forge.Acc else Forge.Line, RoundedCornerShape(8.dp)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f).clickable(enabled = !busy) { if (session.open(c.id)) onClose() }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(c.title, color = Forge.Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(whenFmt.format(Instant.ofEpochMilli(c.updated)) + " · ${c.messageCount} messages" + if (open) " · open" else "", color = Forge.Dim, fontSize = 11.sp)
                                if (c.snippet.isNotBlank()) Text("…${c.snippet}…", color = Forge.Mut, fontSize = 11.sp, maxLines = 2)
                            }
                            SmallButton("DELETE", false) { if (!busy) { session.delete(c.id); version++ } }
                        }
                    }
                }
            }
        },
    )
}
