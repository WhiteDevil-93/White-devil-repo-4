package com.whitedevil.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.whitedevil.ui.components.WdHairline
import com.whitedevil.ui.theme.WdPalette

private const val SETUP_HERO =
    "https://storage.googleapis.com/uxpilot-auth.appspot.com/gen_97e323f467_a5214c75950ce572.png"

@Composable
fun AgentSetupEmptyState(
    onAddKey: () -> Unit,
    onPickModel: () -> Unit,
    modelLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .border(width = 0.dp, color = Color.Transparent),
        ) {
            AsyncImage(
                model = SETUP_HERO,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, WdPalette.bg))),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                Text("STEP 01", style = MaterialTheme.typography.labelLarge, color = WdPalette.accentLight)
                Text("Add your Venice API key", style = MaterialTheme.typography.titleLarge)
            }
        }
        WdHairline()
        Column(Modifier.padding(20.dp)) {
            Text(
                "Your key lets this app send goals to Venice. Add a key, test the connection in Settings, then send your first goal. Venice usage may incur charges; never share your key in chat.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(20.dp))
            ConfigBlock {
                ConfigRow("Provider", "Venice")
                ConfigRow("API key", "Add Venice API key", onClick = onAddKey)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPickModel)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Model", style = MaterialTheme.typography.labelLarge)
                        Text(modelLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text("⌄", color = WdPalette.textMetadata)
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onAddKey,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(2.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = WdPalette.onLightButton,
                ),
            ) {
                Text("Add API key", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun AgentReadyEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        Text("Send your first goal", style = MaterialTheme.typography.titleMedium, color = WdPalette.accentLight)
        Spacer(Modifier.height(6.dp))
        Text("Key configured, not yet verified. Test connections in You → Settings, then describe what you want to accomplish below.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ConfigBlock(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, WdPalette.stroke, RoundedCornerShape(2.dp))
            .clip(RoundedCornerShape(2.dp)),
    ) {
        content()
    }
}

@Composable
private fun ConfigRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(WdPalette.surface)
            .padding(14.dp),
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
    WdHairline()
}
