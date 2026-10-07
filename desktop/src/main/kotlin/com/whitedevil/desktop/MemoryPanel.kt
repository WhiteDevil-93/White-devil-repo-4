package com.whitedevil.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * What Venice remembers across chats and devices: the Hub's persistent memory. Venice reads it at the start of every
 * message and can add to it with its remember tool. Here you can read it, add a note or a preference, and delete a note.
 * Shown as the workspace's Memory tab, and as a dialog from /memory.
 */
@Composable
fun MemoryContent(settings: Settings, modifier: Modifier = Modifier) {
    val client = remember(settings.hubUrl, settings.relayUser, settings.relayPass) { HubMemoryClient(settings.hubUrl, settings.relayUser, settings.relayPass) }
    DisposableEffect(client) { onDispose { client.close() } }
    val scope = rememberCoroutineScope()
    var mem by remember { mutableStateOf<HubMemory?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var prefKey by remember { mutableStateOf("") }
    var prefValue by remember { mutableStateOf("") }

    fun adopt(r: MediaResult<HubMemory>, onOk: () -> Unit = {}) = when (r) {
        is MediaResult.Ok -> { mem = r.value; problem = null; onOk() }
        is MediaResult.Failure -> problem = r.error.message
    }
    LaunchedEffect(client) { adopt(client.get()) }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Tip("Shared with the phone and the web chat. Venice reads this at the start of every message, and can add to it by itself with its remember tool.")
        problem?.let { Tip(it, Forge.Bad) }
        val m = mem
        if (m == null && problem == null) Tip("Loading…")
        if (m != null) {
            Label("Preferences")
            if (m.preferences.isEmpty()) Tip("None yet.")
            m.preferences.forEach { (k, v) -> Text("$k:  $v", color = Forge.Fg, fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(prefKey, { prefKey = it }, Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(8.dp), colors = forgeFieldColors(), label = { Text("Name", color = Forge.Dim, fontSize = 11.sp) })
                OutlinedTextField(prefValue, { prefValue = it }, Modifier.weight(1.5f), singleLine = true, shape = RoundedCornerShape(8.dp), colors = forgeFieldColors(), label = { Text("Value", color = Forge.Dim, fontSize = 11.sp) })
                SmallButton("SAVE", true) { scope.launch { adopt(client.setPreference(prefKey, prefValue)) { prefKey = ""; prefValue = "" } } }
            }
            Label("Notes (${m.notes.size})")
            if (m.notes.isEmpty()) Tip("None yet.")
            m.notes.asReversed().forEach { n ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Forge.Well).border(1.dp, Forge.Line, RoundedCornerShape(8.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(n.text, color = Forge.Fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    SmallButton("✕", false) { scope.launch { adopt(client.replaceNotes(m.notes - n)) } }
                }
            }
            OutlinedTextField(
                note, { note = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, shape = RoundedCornerShape(8.dp), colors = forgeFieldColors(),
                label = { Text("Add a note", color = Forge.Dim, fontSize = 11.sp) },
            )
            SmallButton("SAVE NOTE", true) { scope.launch { adopt(client.addNote(note)) { note = "" } } }
        }
    }
}

@Composable
fun MemoryPanel(settings: Settings, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Forge.Panel,
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE", color = Forge.Acc, fontWeight = FontWeight.SemiBold) } },
        title = { Text("Venice's memory", color = Forge.Fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = { MemoryContent(settings, Modifier.width(560.dp).heightIn(max = 520.dp)) },
    )
}
