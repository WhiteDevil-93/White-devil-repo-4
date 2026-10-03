package com.whitedevil.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Picks the Venice model the agent runs on. The list comes from Venice itself (online models that can call
 * tools, which the agent needs), is searchable, and shows each model's context size. The choice is passed
 * to [onPick], which saves it, so it survives restarts. If the list can't be loaded, a model id can still be
 * typed in, so the picker never leaves you stuck on the current model.
 */
@Composable
fun VeniceModelPicker(apiKey: String, current: String, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var models by remember(apiKey) { mutableStateOf<List<VeniceModel>?>(null) }
    var problem by remember(apiKey) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }

    // Load once, the first time it is opened (and again if the key changes).
    LaunchedEffect(open, apiKey) {
        if (open && models == null) {
            when (val r = fetchVeniceModels(apiKey)) {
                is MediaResult.Ok -> { models = usableVeniceModels(r.value); problem = null }
                is MediaResult.Failure -> problem = r.error.message
            }
        }
    }

    val shape = RoundedCornerShape(8.dp)
    val currentName = models?.firstOrNull { it.id == current }?.name
    Box {
        Row(
            Modifier.clip(shape).background(Forge.Panel).border(1.dp, if (open) Forge.Acc else Forge.Line, shape)
                .then(if (enabled) Modifier.clickable { open = true } else Modifier)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(currentName ?: current, color = if (enabled) Forge.Fg else Forge.Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
            Text("▾", color = Forge.Mut, fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Forge.Panel2) {
            Column(Modifier.width(460.dp).padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val list = models
                when {
                    list != null -> {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp), colors = forgeFieldColors(),
                            placeholder = { Text("Search ${list.size} models (name or id)", color = Forge.Dim, fontSize = 13.sp) },
                        )
                        val shown = filterVeniceModels(list, query)
                        if (shown.isEmpty()) Text("No model matches “$query”.", color = Forge.Mut, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                        LazyColumn(Modifier.heightIn(max = 380.dp)) {
                            items(shown, key = { it.id }) { m ->
                                val selected = m.id == current
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (selected) Forge.AccSoft else Forge.Panel2)
                                        .clickable { open = false; query = ""; onPick(m.id) }.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(m.name, color = if (selected) Forge.Acc3 else Forge.Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(m.id, color = Forge.Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(listOfNotNull(m.contextLabel?.let { "$it ctx" }, if (m.reasoning) "reasoning" else null).joinToString(" · "), color = Forge.Mut, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    problem != null -> {
                        Text(problem!!, color = Forge.Bad, fontSize = 13.sp)
                        Text("You can still type a Venice model id:", color = Forge.Mut, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = typed, onValueChange = { typed = it }, singleLine = true, modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp), colors = forgeFieldColors(), placeholder = { Text(current, color = Forge.Dim, fontSize = 13.sp) },
                            )
                            SmallButton("USE", true) { val id = typed.trim(); if (id.isNotEmpty()) { open = false; typed = ""; onPick(id) } }
                        }
                    }
                    else -> Text("Loading Venice's models…", color = Forge.Mut, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}
