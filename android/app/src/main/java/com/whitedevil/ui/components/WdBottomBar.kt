package com.whitedevil.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whitedevil.ui.theme.WdPalette

data class WdTabItem(val id: String, val label: String, val iconRes: Int)

private data class TabDef(val id: String, val label: String, val icon: ImageVector)

private val tabDefs = listOf(
    TabDef("agent", "Agent", Icons.Outlined.Bolt),
    TabDef("hub", "Hub", Icons.Outlined.Layers),
    TabDef("you", "You", Icons.Outlined.PersonOutline),
)

@Composable
fun WdBottomBar(
    items: List<WdTabItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        WdHairline()
        Row(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(WdPalette.bg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabDefs.forEach { tab ->
                val selected = tab.id == selectedId
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(tab.id) }
                        .semantics { role = Role.Tab }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (selected) {
                        Box(
                            Modifier
                                .width(32.dp)
                                .height(2.dp)
                                .background(WdPalette.accent),
                        )
                    } else {
                        Box(Modifier.height(2.dp))
                    }
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(22.dp),
                        tint = if (selected) WdPalette.accent else WdPalette.textMetadata,
                    )
                    Text(
                        tab.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) WdPalette.text else WdPalette.textMetadata,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
