package com.whitedevil.ui.you

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whitedevil.ui.theme.WdColors

@Composable
fun YouHomeScreen(
    connectionSummary: String,
    veniceReady: Boolean,
    onTerminal: () -> Unit,
    onSettings: () -> Unit,
    onTestConnections: () -> Unit,
    onAddVeniceKey: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF141210), Color(0xFF0B0B0C), Color(0xFF080809)),
                ),
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 40.dp),
    ) {
        Text("WHITEDEVIL", color = WdColors.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Text("You", color = WdColors.strong, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Terminal, settings, and connection health live here.",
            color = WdColors.muted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(20.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x331A1A1E), RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Text("Connection health", color = WdColors.strong, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text(connectionSummary, color = WdColors.muted, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "Test connections",
                color = WdColors.accent,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.clickable { onTestConnections() },
            )
            if (!veniceReady) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add Venice API key",
                    color = Color(0xFFE85D5D),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onAddVeniceKey() },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        YouNavRow("Terminal", "Relay SSH / WSL shell", onTerminal)
        Spacer(Modifier.height(10.dp))
        YouNavRow("Settings", "Venice, relay, and laptop credentials", onSettings)
        Spacer(Modifier.height(24.dp))
        Text(
            "WhiteDevil v9 · Native Agent + Forge Hub",
            color = WdColors.muted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun YouNavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x331A1A1E), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(title, color = WdColors.strong, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = WdColors.muted, fontSize = 13.sp)
    }
}
