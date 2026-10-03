package com.whitedevil.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whitedevil.desktop.mcp.McpHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** MCP connectors for Venice: edit the server list (Claude-Desktop format), save, and see which servers started. */
@Composable
fun ConnectorsPanel(host: McpHost, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(host.configText()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun apply() = scope.launch {
        busy = true
        status = withContext(Dispatchers.IO) {
            host.saveConfig(text) ?: run {
                val s = host.status()
                if (s.isEmpty()) "Saved. No server started (check the command, or that npx/uvx is installed)."
                else "Saved. Running: " + s.joinToString(", ") { (n, c) -> "$n ($c tools)" }
            }
        }
        busy = false
    }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Forge.Panel,
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE", color = Forge.Acc, fontWeight = FontWeight.SemiBold) } },
        title = { Text("Connectors (MCP)", color = Forge.Fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(600.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Tip("MCP servers give Venice extra tools (files, web fetch, GitHub, databases…). Same format as Claude Desktop. Servers start the first time you send a message.")
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 8, maxLines = 16, shape = RoundedCornerShape(8.dp), colors = forgeFieldColors())
                SmallButton(if (busy) "STARTING…" else "SAVE AND START", !busy) { apply() }
                status?.let { Tip(it, if (it.startsWith("That is not")) Forge.Bad else Forge.Mut) }
            }
        },
    )
}
