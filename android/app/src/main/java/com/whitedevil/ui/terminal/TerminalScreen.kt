package com.whitedevil.ui.terminal

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.R
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.components.WdSurfaceCard
import com.whitedevil.ui.theme.WdPalette

@Composable
fun TerminalScreen(host: MainActivity, showBack: Boolean) {
    var menuOpen by remember { mutableStateOf(false) }
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showBack) {
                    Text(
                        "← You",
                        style = MaterialTheme.typography.labelMedium,
                        color = WdPalette.accent,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clickable { host.showYouSub(MainActivity.YouSub.HOME) },
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("Terminal", style = MaterialTheme.typography.titleLarge)
                    Text("Relay shell", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Paste",
                    style = MaterialTheme.typography.labelMedium,
                    color = WdPalette.onAccent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(WdPalette.accent)
                        .clickable { host.openTerminalPasteSheetPublic() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                IconButton(onClick = { menuOpen = true }) {
                    Icon(painterResource(R.drawable.ic_more), contentDescription = "Options", tint = WdPalette.textSecondary)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Scroll to top") }, onClick = {
                        menuOpen = false
                        host.scrollTerminal("top")
                    })
                    DropdownMenuItem(text = { Text("Scroll to bottom") }, onClick = {
                        menuOpen = false
                        host.scrollTerminal("bottom")
                    })
                    DropdownMenuItem(text = { Text("Reload") }, onClick = {
                        menuOpen = false
                        host.terminalWebViewPublic()?.reload()
                    })
                }
            }
            val termProgress = host.terminalLoadProgressPublic()
            if (termProgress in 0.01f..0.99f) {
                LinearProgressIndicator(
                    progress = { termProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = WdPalette.accent,
                    trackColor = Color.Transparent,
                )
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                factory = { ctx -> host.ensureTerminalWebViewMounted(ctx) },
                update = { frame ->
                    frame.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                },
            )
        }
    }
    if (host.terminalPasteOpenPublic()) {
        Dialog(onDismissRequest = { host.setTerminalPasteOpen(false) }) {
            WdSurfaceCard {
                Column(Modifier.padding(20.dp)) {
                    Text("Paste into shell", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Review before sending.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    OutlinedTextField(
                        value = host.terminalPasteTextPublic(),
                        onValueChange = { host.setTerminalPasteText(it) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    Row(Modifier.padding(top = 12.dp)) {
                        TextButton(onClick = { host.sendPasteToTerminalPublic() }, modifier = Modifier.weight(1f)) {
                            Text("Send", color = WdPalette.accent)
                        }
                        TextButton(onClick = { host.setTerminalPasteOpen(false) }, modifier = Modifier.weight(1f)) {
                            Text("Cancel", color = WdPalette.textSecondary)
                        }
                    }
                }
            }
        }
    }
}
