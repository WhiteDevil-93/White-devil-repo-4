package com.whitedevil.ui.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whitedevil.ui.theme.WdDimens
import com.whitedevil.ui.theme.WdPalette

@Composable
fun AgentSetupEmptyState(onAddKey: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = WdDimens.screenHorizontal + 8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text("Connect Venice", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Integrate your intelligence layer to start forging. Securely connect your favorite models via API.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Add API key",
            style = MaterialTheme.typography.labelMedium,
            color = WdPalette.accent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onAddKey),
        )
    }
}

@Composable
fun AgentReadyEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = WdDimens.screenHorizontal + 8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text("No messages yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("Send a message below.", style = MaterialTheme.typography.bodySmall)
    }
}
