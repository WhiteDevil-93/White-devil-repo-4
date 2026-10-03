package com.whitedevil.ui.agenttools

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whitedevil.MainActivity
import com.whitedevil.agent.ConversationMeta
import com.whitedevil.agent.McpDiscovery
import com.whitedevil.agent.McpServerConfig
import com.whitedevil.agent.Project
import com.whitedevil.agent.Skill
import com.whitedevil.ui.components.WdHairline
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.components.WdSurfaceCard
import com.whitedevil.ui.components.WdTopBar
import com.whitedevil.ui.theme.WdPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun ToolsPage(
    title: String,
    subtitle: String?,
    host: MainActivity,
    content: @Composable ColumnScope.() -> Unit,
) {
    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            WdTopBar(
                title = title,
                subtitle = subtitle,
                trailing = { TextButton(onClick = { host.showYouSub(MainActivity.YouSub.HOME) }) { Text("Back") } },
            )
            WdHairline()
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun Row2(title: String, sub: String?, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    WdSurfaceCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.labelMedium, color = WdPalette.textMetadata, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            trailing()
        }
    }
}

@Composable
private fun Confirm(title: String, text: String, ok: String, onOk: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onOk(); onDismiss() }) { Text(ok) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, lines: Int = 1, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        modifier = modifier.fillMaxWidth(), minLines = lines, maxLines = if (lines == 1) 1 else 12,
    )
}

// ------------------------------------------------------------------------------------ chats

@Composable
fun ChatsScreen(host: MainActivity) {
    val store = host.workspace.conversations
    var rev by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<ConversationMeta?>(null) }
    var deleting by remember { mutableStateOf<ConversationMeta?>(null) }
    var moving by remember { mutableStateOf<ConversationMeta?>(null) }
    val projectNames = remember(rev) { host.workspace.projects.list().associate { it.id to it.name } }
    val items = remember(rev) { store.list() }
    val hits = remember(rev, query) { if (query.isBlank()) emptyList() else store.search(query) }
    val currentId = remember(rev) { store.currentId() }

    ToolsPage("Chats", "${items.size} saved", host) {
        Button(onClick = { host.startNewChat() }, modifier = Modifier.fillMaxWidth()) { Text("New chat") }
        Field("Search chats", query, { query = it })
        if (query.isNotBlank()) {
            if (hits.isEmpty()) Text("No matches.", color = WdPalette.textMetadata)
            hits.forEach { h -> Row2(h.meta.title, h.snippet, onClick = { host.openConversation(h.meta.id) }) }
        } else {
            if (items.isEmpty()) Text("No chats yet. Start one from the Agent tab.", color = WdPalette.textMetadata)
            items.forEach { c ->
                var menu by remember { mutableStateOf(false) }
                Row2(
                    title = (if (c.pinned) "★ " else "") + c.title + (if (c.id == currentId) "  (open)" else ""),
                    sub = "${c.messageCount} messages · " + DateUtils.getRelativeTimeSpanString(c.updatedAt) +
                        (projectNames[c.projectId]?.let { " · $it" } ?: ""),
                    onClick = { host.openConversation(c.id) },
                    trailing = {
                        Column {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = WdPalette.textSecondary) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = c })
                                DropdownMenuItem(text = { Text(if (c.pinned) "Unpin" else "Pin") }, onClick = { menu = false; store.pin(c.id, !c.pinned); rev++ })
                                DropdownMenuItem(text = { Text("Move to project") }, onClick = { menu = false; moving = c })
                                DropdownMenuItem(text = { Text("Export (share)") }, onClick = {
                                    menu = false
                                    val md = store.exportMarkdown(c.id)
                                    host.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, c.title).putExtra(Intent.EXTRA_TEXT, md),
                                            "Export chat",
                                        ),
                                    )
                                })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleting = c })
                            }
                        }
                    },
                )
            }
        }
    }
    renaming?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = { Field("Title", name, { name = it }) },
            confirmButton = { TextButton(onClick = { store.rename(c.id, name); renaming = null; rev++ }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { c ->
        Confirm("Delete chat?", "\"${c.title}\" will be removed from this phone.", "Delete", { store.delete(c.id); rev++ }, { deleting = null })
    }
    moving?.let { c ->
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("Move to project") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { store.setProject(c.id, null); moving = null; rev++ }) { Text(if (c.projectId == null) "• No project" else "No project") }
                    projectNames.forEach { (pid, name) ->
                        TextButton(onClick = { store.setProject(c.id, pid); moving = null; rev++ }) { Text(if (c.projectId == pid) "• $name" else name) }
                    }
                    if (projectNames.isEmpty()) Text("No projects yet. Create one in You > Projects.", style = MaterialTheme.typography.labelMedium)
                }
            },
            confirmButton = { TextButton(onClick = { moving = null }) { Text("Close") } },
        )
    }
}

// ------------------------------------------------------------------------------------ projects

@Composable
fun ProjectsScreen(host: MainActivity) {
    val ws = host.workspace
    var rev by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Project?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Project?>(null) }
    val projects = remember(rev) { ws.projects.list() }
    val chats = remember(rev) { ws.conversations.list() }

    ToolsPage("Projects", "${projects.size} projects", host) {
        Text(
            "A project holds standing instructions that apply to every chat inside it. Start a chat from here, " +
                "or move an existing chat into a project from Chats.",
            style = MaterialTheme.typography.labelMedium, color = WdPalette.textMetadata,
        )
        Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("New project") }
        if (projects.isEmpty()) Text("No projects yet.", color = WdPalette.textMetadata)
        projects.forEach { p ->
            val n = chats.count { it.projectId == p.id }
            Row2(
                p.name,
                "$n chats" + (if (p.instructions.isNotBlank()) "\n" + p.instructions.take(160) else ""),
                onClick = { editing = p },
                trailing = { TextButton(onClick = { host.startNewChat(p.id) }) { Text("New chat") } },
            )
        }
    }
    if (creating || editing != null) {
        val base = editing
        var name by remember(base?.id) { mutableStateOf(base?.name.orEmpty()) }
        var instr by remember(base?.id) { mutableStateOf(base?.instructions.orEmpty()) }
        var err by remember(base?.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (base == null) "New project" else "Edit project") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Name", name, { name = it })
                    Field("Instructions for every chat in this project", instr, { instr = it }, lines = 6)
                    err?.let { Text(it, color = WdPalette.accentLight, style = MaterialTheme.typography.labelMedium) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { if (base == null) ws.projects.create(name, instr) else ws.projects.update(base.id, name, instr) }
                        .onSuccess { creating = false; editing = null; rev++ }
                        .onFailure { err = it.message }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (base != null) TextButton(onClick = { deleting = base; editing = null }) { Text("Delete") }
                    TextButton(onClick = { creating = false; editing = null }) { Text("Cancel") }
                }
            },
        )
    }
    deleting?.let { p ->
        Confirm("Delete project?", "\"${p.name}\" and its instructions will be removed. Its chats are kept, outside any project.", "Delete",
            { ws.deleteProject(p.id); rev++ }, { deleting = null })
    }
}

// ------------------------------------------------------------------------------------ memory

@Composable
fun MemoryScreen(host: MainActivity) {
    val store = host.workspace.memory
    var rev by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf("") }
    var clearing by remember { mutableStateOf(false) }
    val items = remember(rev) { store.list().asReversed() }

    ToolsPage("Memory", "${items.size} remembered", host) {
        Text(
            "Facts the agent keeps across chats. It saves one when you ask it to remember something. " +
                "They are sent to the model with every message, so keep this list short and free of secrets.",
            style = MaterialTheme.typography.labelMedium, color = WdPalette.textMetadata,
        )
        Field("Add a fact", draft, { draft = it })
        Button(onClick = { store.add(draft, "user"); draft = ""; rev++ }, enabled = draft.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Remember") }
        if (items.isEmpty()) Text("Nothing remembered yet.", color = WdPalette.textMetadata)
        items.forEach { e ->
            Row2(e.text, (if (e.source == "user") "added by you" else "saved by the agent") + " · " + DateUtils.getRelativeTimeSpanString(e.createdAt),
                trailing = { TextButton(onClick = { store.forget(e.id); rev++ }) { Text("Forget") } })
        }
        if (items.isNotEmpty()) TextButton(onClick = { clearing = true }) { Text("Forget everything") }
    }
    if (clearing) Confirm("Forget everything?", "All remembered facts will be deleted.", "Delete", { store.clear(); rev++ }, { clearing = false })
}

// ------------------------------------------------------------------------------------ skills

@Composable
fun SkillsScreen(host: MainActivity) {
    val store = host.workspace.skills
    var rev by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Skill?>(null) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Skill?>(null) }
    val items = remember(rev) { store.list() }

    ToolsPage("Skills", "${items.count { it.enabled }} of ${items.size} on", host) {
        Text(
            "A skill is a set of instructions the agent loads only when a request matches it. " +
                "The agent sees each skill's name and description; the full text is read on demand.",
            style = MaterialTheme.typography.labelMedium, color = WdPalette.textMetadata,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creating = true }) { Text("New skill") }
            TextButton(onClick = { importing = true }) { Text("Import from text") }
        }
        if (items.isEmpty()) Text("No skills yet.", color = WdPalette.textMetadata)
        items.forEach { s ->
            Row2(s.name, s.description, onClick = { editing = s }, trailing = {
                Switch(checked = s.enabled, onCheckedChange = { store.setEnabled(s.name, it); rev++ })
            })
        }
    }
    if (creating || editing != null) {
        val base = editing
        var name by remember(base?.name) { mutableStateOf(base?.name.orEmpty()) }
        var desc by remember(base?.name) { mutableStateOf(base?.description.orEmpty()) }
        var body by remember(base?.name) { mutableStateOf(base?.body.orEmpty()) }
        var err by remember(base?.name) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (base == null) "New skill" else "Edit skill") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (base == null) Field("Name (a-z, 0-9, dashes)", name, { name = it })
                    Field("When to use it (one line)", desc, { desc = it })
                    Field("Instructions", body, { body = it }, lines = 6)
                    err?.let { Text(it, color = WdPalette.accentLight, style = MaterialTheme.typography.labelMedium) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { store.save(base?.name ?: name.trim(), desc, body) }
                        .onSuccess { creating = false; editing = null; rev++ }
                        .onFailure { err = it.message }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (base != null) TextButton(onClick = { deleting = base; editing = null }) { Text("Delete") }
                    TextButton(onClick = { creating = false; editing = null }) { Text("Cancel") }
                }
            },
        )
    }
    if (importing) {
        var text by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("Import skill") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste a skill file: front matter with name and description between --- lines, then the instructions.", style = MaterialTheme.typography.labelMedium)
                    Field("Skill text", text, { text = it }, lines = 8)
                    err?.let { Text(it, color = WdPalette.accentLight, style = MaterialTheme.typography.labelMedium) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (store.importText(text) != null) { importing = false; rev++ } else err = "Could not read that: it needs name and description front matter and a non-empty body."
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Cancel") } },
        )
    }
    deleting?.let { s -> Confirm("Delete skill?", "\"${s.name}\" will be removed.", "Delete", { store.delete(s.name); rev++ }, { deleting = null }) }
}

// ------------------------------------------------------------------------------------ connectors (MCP)

@Composable
fun ConnectorsScreen(host: MainActivity) {
    val ws = host.workspace
    val scope = rememberCoroutineScope()
    var rev by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<McpServerConfig?>(null) }
    var status by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val items = remember(rev) { ws.mcp.list() }

    fun test(s: McpServerConfig) {
        status = status + (s.id to "Connecting...")
        scope.launch {
            val d: McpDiscovery = withContext(Dispatchers.IO) { com.whitedevil.agent.McpRegistry.discover(listOf(s.copy(enabled = true))).first() }
            status = status + (s.id to (d.error?.let { "Failed: $it" } ?: "Connected: ${d.serverInfo}, ${d.tools.size} tools"))
        }
    }

    ToolsPage("Connectors", "${items.count { it.enabled }} MCP servers on", host) {
        Text(
            "Connect remote MCP servers (HTTP) to give the agent their tools. The agent asks you before each call " +
                "unless you switch on 'skip approval' for that server. Their output is treated as untrusted data.",
            style = MaterialTheme.typography.labelMedium, color = WdPalette.textMetadata,
        )
        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Add MCP server") }
        if (items.isEmpty()) Text("No servers yet.", color = WdPalette.textMetadata)
        items.forEach { s ->
            Row2(s.name, s.url + (status[s.id]?.let { "\n$it" } ?: "") + (if (s.autoApprove) "\nSkips approval" else ""), trailing = {
                Column(horizontalAlignment = Alignment.End) {
                    Switch(checked = s.enabled, onCheckedChange = { ws.mcp.update(s.copy(enabled = it)); ws.invalidateMcp(); rev++ })
                    Row {
                        TextButton(onClick = { test(s) }) { Text("Test") }
                        TextButton(onClick = { deleting = s }) { Text("Remove") }
                    }
                }
            })
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var token by remember { mutableStateOf("") }
        var skip by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add MCP server") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Name", name, { name = it })
                    Field("URL (https://host/mcp)", url, { url = it })
                    Field("Access token (optional)", token, { token = it })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = skip, onCheckedChange = { skip = it })
                        Text("  Skip approval for this server", style = MaterialTheme.typography.labelMedium)
                    }
                    err?.let { Text(it, color = WdPalette.accentLight, style = MaterialTheme.typography.labelMedium) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val headers = if (token.isBlank()) emptyMap() else mapOf("Authorization" to (if (token.trim().startsWith("Bearer ", true)) token.trim() else "Bearer ${token.trim()}"))
                    runCatching { ws.mcp.add(name, url, headers, skip) }
                        .onSuccess { ws.invalidateMcp(); adding = false; rev++; test(it) }
                        .onFailure { err = it.message }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
    deleting?.let { s -> Confirm("Remove server?", "\"${s.name}\" and its saved token will be deleted from this phone.", "Remove", { ws.mcp.remove(s.id); ws.invalidateMcp(); rev++ }, { deleting = null }) }
}
