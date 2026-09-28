package com.whitedevil.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.whitedevil.ui.theme.WdPalette

data class WdTabItem(val id: String, val label: String, val iconRes: Int)

@Composable
fun WdBottomBar(
    items: List<WdTabItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(WdPalette.bgElevated)
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val selected = item.id == selectedId
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(item.id) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    painterResource(item.iconRes),
                    contentDescription = item.label,
                    tint = if (selected) WdPalette.accent else WdPalette.textTertiary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) WdPalette.text else WdPalette.textTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
