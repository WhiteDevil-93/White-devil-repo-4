package com.whitedevil.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.whitedevil.ui.theme.WdPalette

data class WdTabItem(val id: String, val label: String, val iconRes: Int)

private data class TabDef(val id: String, val label: String, val icon: ImageVector)

private val tabDefs = listOf(
    TabDef("agent", "Agent", Icons.AutoMirrored.Outlined.Chat),
    TabDef("hub", "Hub", Icons.Outlined.GridView),
    TabDef("you", "You", Icons.Outlined.PersonOutline),
)

/** Primary app nav — icon + label share one active treatment. */
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
        HorizontalDivider(color = WdPalette.stroke, thickness = 0.5.dp)
        NavigationBar(
            modifier = Modifier.fillMaxWidth(),
            containerColor = WdPalette.bg,
            tonalElevation = 0.dp,
        ) {
        tabDefs.forEach { tab ->
            val selected = tab.id == selectedId
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab.id) },
                icon = {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (selected) WdPalette.accent else WdPalette.textSecondary,
                    )
                },
                label = {
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) WdPalette.accent else WdPalette.textSecondary,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color.Transparent,
                    selectedIconColor = WdPalette.accent,
                    selectedTextColor = WdPalette.accent,
                    unselectedIconColor = WdPalette.textSecondary,
                    unselectedTextColor = WdPalette.textSecondary,
                ),
            )
        }
        }
    }
}
