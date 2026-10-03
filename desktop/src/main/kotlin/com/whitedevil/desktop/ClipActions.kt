package com.whitedevil.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A clip name comes from the hub, so it is data: it must never be allowed to name a path outside the
 * folder it is saved in. Separators and control characters are replaced and `.`/`..` are refused.
 */
fun safeFileName(name: String): String? {
    val cleaned = name.map { c -> if (c == '/' || c == '\\' || c == ':' || c.code < 32 || c in "<>\"|?*") '_' else c }
        .joinToString("").trim().trimEnd('.', ' ')
    return cleaned.takeIf { it.isNotEmpty() && cleaned != "." && cleaned != ".." }?.take(180)
}

/** Where played clips are cached and where Save puts them. */
object ClipFolders {
    val cache: Path get() = Paths.get(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "ForgeHub", "clips")
    val downloads: Path get() = Paths.get(System.getProperty("user.home"), "Downloads", "ForgeHub")
}

/**
 * Play and Save for a hub clip. Play downloads into a cache (once) and opens the file in the
 * default video player; Save copies it to Downloads\ForgeHub and reveals it. [status] holds a
 * one-line message per clip name while it is working or after it failed, so the buttons can say so.
 */
class ClipActions(private val client: MediaClient, private val scope: CoroutineScope) {
    val status = mutableStateMapOf<String, String>()

    fun play(name: String) = scope.launch {
        val file = fetchTo(name, ClipFolders.cache) ?: return@launch
        open(name) { Desktop.getDesktop().open(file.toFile()) }
    }

    fun save(name: String) = scope.launch {
        val file = fetchTo(name, ClipFolders.downloads) ?: return@launch
        open(name) { ProcessBuilder("explorer.exe", "/select,${file.toAbsolutePath()}").start() }
        status[name] = "Saved to ${ClipFolders.downloads}"
    }

    private suspend fun fetchTo(name: String, dir: Path): Path? {
        val safe = safeFileName(name) ?: run { status[name] = "This clip's name can't be used as a file name."; return null }
        val dest = dir.resolve(safe)
        if (withContext(Dispatchers.IO) { Files.isRegularFile(dest) && Files.size(dest) > 0 }) {
            status.remove(name)
            return dest
        }
        status[name] = "Downloading…"
        return when (val r = client.downloadClip(name, dest)) {
            is MediaResult.Ok -> { status.remove(name); dest }
            is MediaResult.Failure -> { status[name] = "${r.error.title}: ${r.error.message}"; null }
        }
    }

    private suspend fun open(name: String, action: () -> Unit) {
        try {
            withContext(Dispatchers.IO) { action() }
        } catch (e: Exception) {
            status[name] = "Couldn't open it: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}

@Composable
fun rememberClipActions(client: MediaClient): ClipActions {
    val scope = rememberCoroutineScope()
    return remember(client) { ClipActions(client, scope) }
}

/** PLAY / SAVE buttons for one clip, with its status line when there is one. */
@Composable
fun ClipButtons(actions: ClipActions, name: String, modifier: Modifier = Modifier) {
    val msg = actions.status[name]
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (msg != null) {
            Text(msg, color = if (msg.startsWith("Downloading") || msg.startsWith("Saved")) Forge.Mut else Forge.Bad, fontSize = 11.sp, maxLines = 2, modifier = Modifier.width(190.dp))
        }
        SmallButton("PLAY", primary = true) { actions.play(name) }
        SmallButton("SAVE", primary = false) { actions.save(name) }
    }
}

@Composable
private fun SmallButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text, color = if (primary) androidx.compose.ui.graphics.Color.White else Forge.Mut,
        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
        modifier = Modifier.clip(shape)
            .background(if (primary) Forge.Acc2 else Forge.Panel2)
            .border(1.dp, if (primary) Forge.Acc2 else Forge.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
