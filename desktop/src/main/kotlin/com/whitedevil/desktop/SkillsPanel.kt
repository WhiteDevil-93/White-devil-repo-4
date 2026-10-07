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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whitedevil.desktop.skills.Skill
import com.whitedevil.desktop.skills.SkillStore

/** Browse, edit, add and delete the playbooks Venice can load. Files live in %LOCALAPPDATA%\WhiteDevil\skills. */
@Composable
fun SkillsPanel(store: SkillStore, onClose: () -> Unit) {
    var skills by remember { mutableStateOf(store.list()) }
    var filter by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    fun pick(s: Skill?) { name = s?.name.orEmpty(); desc = s?.description.orEmpty(); body = s?.body.orEmpty(); note = null }
    fun reload() { skills = store.list() }
    val shown = skills.filter { filter.isBlank() || it.name.contains(filter, true) || it.description.contains(filter, true) }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Forge.Panel,
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE", color = Forge.Acc, fontWeight = FontWeight.SemiBold) } },
        title = { Text("Venice skills (${skills.size})", color = Forge.Fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Row(Modifier.width(900.dp).height(480.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(8.dp), colors = forgeFieldColors(), label = { Text("Search skills", color = Forge.Dim, fontSize = 11.sp) })
                    Column(Modifier.fillMaxWidth().height(340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        shown.forEach { s ->
                            val on = s.name == name
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) Forge.Panel2 else Forge.Well)
                                    .border(1.dp, if (on) Forge.Acc else Forge.Line, RoundedCornerShape(8.dp)).clickable { pick(s) }.padding(8.dp),
                            ) {
                                Text(s.name, color = Forge.Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(s.description, color = Forge.Dim, fontSize = 11.sp, maxLines = 2)
                            }
                        }
                    }
                    SmallButton("NEW SKILL", false) { pick(null) }
                }
                Column(Modifier.width(580.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(8.dp), colors = forgeFieldColors(), label = { Text("Name", color = Forge.Dim, fontSize = 11.sp) })
                    OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), maxLines = 3, shape = RoundedCornerShape(8.dp), colors = forgeFieldColors(), label = { Text("What it is for, and when to use it", color = Forge.Dim, fontSize = 11.sp) })
                    OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth().height(250.dp), shape = RoundedCornerShape(8.dp), colors = forgeFieldColors(), label = { Text("Instructions", color = Forge.Dim, fontSize = 11.sp) })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("SAVE", true) { note = store.save(name, desc, body) ?: "Saved '${SkillStore.clean(name)}'."; reload() }
                        SmallButton("DELETE", false) { note = if (store.delete(name)) "Deleted." else "Nothing to delete."; reload(); pick(null) }
                        SmallButton("RESTORE DEFAULTS", false) { val n = store.resetDefaults(); note = "Restored $n bundled skills (your own are untouched)."; reload() }
                    }
                    note?.let { Tip(it) }
                }
            }
        },
    )
}
