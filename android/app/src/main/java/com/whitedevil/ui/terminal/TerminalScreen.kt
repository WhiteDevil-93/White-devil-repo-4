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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.whitedevil.*
import com.whitedevil.MainActivity
import com.whitedevil.R
import com.whitedevil.ui.components.WdGlassCard
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.theme.WdColors

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
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showBack) {
                    Text(
                        "← You",
                        color = WdColors.accent,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clickable { host.showYouSub(MainActivity.YouSub.HOME) },
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("Terminal", color = WdColors.strong, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Relay SSH / WSL", color = WdColors.muted, fontSize = 12.sp)
                }
                Text(
                    "Paste",
                    color = Color(0xFF111111),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(WdColors.accent)
                        .clickable { host.openTerminalPasteSheetPublic() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
                IconButton(onClick = { menuOpen = true }) {
                    Icon(painterResource(R.drawable.ic_more), contentDescription = "Terminal options", tint = WdColors.muted)
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
                    color = WdColors.accent,
                    trackColor = Color(0x331A1A1E),
                )
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
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
            WdGlassCard {
                Column(Modifier.padding(16.dp)) {
                    Text("Terminal Paste Safety", color = WdColors.strong, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "Review before sending to the shell.",
                        color = WdColors.muted,
                        fontSize = 12.sp,
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
                            Text("Send to Shell", color = WdColors.accent)
                        }
                        TextButton(onClick = { host.setTerminalPasteOpen(false) }, modifier = Modifier.weight(1f)) {
                            Text("Cancel", color = WdColors.muted)
                        }
                    }
                }
            }
        }
    }
}
