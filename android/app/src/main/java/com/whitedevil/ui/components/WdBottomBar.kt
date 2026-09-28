package com.whitedevil.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whitedevil.ui.theme.WdPalette

data class WdTabItem(val id: String, val label: String, val iconRes: Int)

private data class TabDef(val id: String, val label: String, val icon: ImageVector)

private val tabDefs = listOf(
    TabDef("agent", "Agent", Icons.AutoMirrored.Outlined.Chat),
    TabDef("hub", "Hub", Icons.Outlined.GridView),
    TabDef("you", "You", Icons.Outlined.PersonOutline),
)

@Composable
fun WdBottomBar(
    items: List<WdTabItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(WdPalette.surface)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabDefs.forEach { tab ->
            val selected = tab.id == selectedId
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (selected) WdPalette.surfaceHover else WdPalette.surface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(tab.id) }
                    .padding(horizontal = 22.dp, vertical = 10.dp)
                    .semantics { contentDescription = tab.label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    tab.icon,
                    contentDescription = null,
                    tint = if (selected) WdPalette.accent else WdPalette.textTertiary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
