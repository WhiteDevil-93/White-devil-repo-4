package com.whitedevil.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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

/**
 * Search box plus filter chips shared by Renders and Gallery. Filters are chips that combine, not
 * tabs: pick a source, a time range and a sort at once. The test pile is hidden until asked for,
 * so it stops drowning everything else.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaFilterBar(
    filter: MediaFilter,
    onChange: (MediaFilter) -> Unit,
    counts: FilterCounts,
    shown: Int,
) {
    Column(Modifier.fillMaxWidth().background(Forge.Bg).padding(horizontal = 28.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = { onChange(filter.copy(query = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Forge.Dim) },
            trailingIcon = {
                if (filter.query.isNotEmpty()) {
                    TextButton(onClick = { onChange(filter.copy(query = "")) }) { Text("CLEAR", color = Forge.Acc, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                }
            },
            placeholder = { Text("Search clips, projects, seeds, sizes…   e.g.  goon 1280x704   or   friends -test", color = Forge.Dim, fontSize = 13.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Forge.Well, unfocusedContainerColor = Forge.Well,
                focusedBorderColor = Forge.Acc, unfocusedBorderColor = Forge.Line,
                focusedTextColor = Forge.Fg, unfocusedTextColor = Forge.Fg, cursorColor = Forge.Acc,
            ),
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("All sources", filter.source == null) { onChange(filter.copy(source = null)) }
            val srcs = if (filter.source != null && counts.sources.none { it.first == filter.source }) counts.sources + (filter.source to 0) else counts.sources
            srcs.forEach { (src, n) ->
                Chip("$src  $n", filter.source == src) { onChange(filter.copy(source = if (filter.source == src) null else src)) }
            }
            Divider()
            DateRange.entries.forEach { r -> Chip(r.label, filter.range == r) { onChange(filter.copy(range = r)) } }
            Divider()
            if (counts.keepers > 0) Chip("★ Keepers  ${counts.keepers}", filter.keepersOnly) { onChange(filter.copy(keepersOnly = !filter.keepersOnly)) }
            if (counts.tests > 0) Chip("Show tests  ${counts.tests}", filter.showTests) { onChange(filter.copy(showTests = !filter.showTests)) }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val line = if (filter.isDefault && filter.sort == SortKey.Newest) "$shown clips" else "$shown of ${counts.total} clips"
            Text(line, color = Forge.Mut, fontSize = 12.sp)
            if (!filter.isDefault) {
                TextButton(onClick = { onChange(MediaFilter(sort = filter.sort, view = filter.view)) }) {
                    Text("RESET FILTERS", color = Forge.Acc, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            ViewMode.entries.forEach { m -> Chip(m.label, filter.view == m) { onChange(filter.copy(view = m)) } }
            SortMenu(filter.sort) { onChange(filter.copy(sort = it)) }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Forge.Line))
}

@Composable
private fun Divider() = Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(28.dp).background(Forge.Line))

@Composable
private fun SortMenu(current: SortKey, onPick: (SortKey) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip("Sort: ${current.label} ▾", false) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Forge.Panel2) {
            SortKey.entries.forEach { k ->
                DropdownMenuItem(
                    text = { Text(k.label, color = if (k == current) Forge.Acc3 else Forge.Fg, fontSize = 13.sp) },
                    onClick = { open = false; onPick(k) },
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(99.dp)
    Text(
        text,
        color = if (selected) Forge.Fg else Forge.Mut,
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(shape)
            .background(if (selected) Forge.AccSoft else Forge.Panel)
            .border(1.dp, if (selected) Forge.Acc2 else Forge.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
