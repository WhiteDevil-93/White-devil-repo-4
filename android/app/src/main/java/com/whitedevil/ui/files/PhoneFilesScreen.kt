package com.whitedevil.ui.files

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.whitedevil.MainActivity
import com.whitedevil.attachPhoneUriPublic
import com.whitedevil.phoneFolderUriPublic
import com.whitedevil.pickPhoneFilesPublic
import com.whitedevil.pickPhoneFolderPublic
import com.whitedevil.pickPhoneMediaPublic
import com.whitedevil.requestPhoneMediaPermissionPublic
import com.whitedevil.ui.components.WdScreenBackground
import com.whitedevil.ui.components.WdSurfaceCard
import com.whitedevil.ui.theme.WdPalette

data class PhoneFileEntry(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val isDirectory: Boolean,
)

@Composable
fun PhoneFilesScreen(host: MainActivity) {
    var folderUri by remember { mutableStateOf(host.phoneFolderUriPublic()) }
    var entries by remember { mutableStateOf<List<PhoneFileEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var folderName by remember { mutableStateOf("No folder linked") }
    val folders = remember { mutableStateListOf<Pair<Uri, String>>() }

    fun reload() {
        folderUri = host.phoneFolderUriPublic()
        val tree = folderUri
        if (tree == null) {
            entries = emptyList()
            folderName = "No folder linked"
            error = null
            return
        }
        runCatching {
            var root = DocumentFile.fromTreeUri(host, tree) ?: error("Couldn't open linked folder")
            folders.forEach { (_, name) -> root = root.findFile(name) ?: error("Folder no longer available: $name") }
            folderName = root.name ?: "Phone folder"
            entries = root.listFiles()
                .filter { it.name != null }
                .sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name?.lowercase() })
                .map {
                    PhoneFileEntry(
                        uri = it.uri,
                        name = it.name ?: "file",
                        mime = it.type ?: if (it.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
                        size = it.length(),
                        isDirectory = it.isDirectory,
                    )
                }
            error = null
        }.onFailure {
            error = it.message
            entries = emptyList()
        }
    }

    // Refresh when host stores a new tree URI
    LaunchedEffect(host.phoneFolderUriPublic()?.toString()) { folders.clear(); reload() }

    WdScreenBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                "← You",
                style = MaterialTheme.typography.labelMedium,
                color = WdPalette.accent,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .clickable { host.showYouSub(MainActivity.YouSub.HOME) },
            )
            Text("Phone files", style = MaterialTheme.typography.headlineLarge)
            if (folders.isNotEmpty()) {
                Text("Linked folder / ${folders.joinToString(" / ") { it.second }}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { folders.removeAt(folders.lastIndex); reload() }) { Text("Up one folder") }
            }
            Text(
                "Browse a folder on this phone. Pick files to attach to Agent, or keep a linked folder for quick access.",
                style = MaterialTheme.typography.bodySmall,
                color = WdPalette.textSecondary,
                modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
            )

            WdSurfaceCard(Modifier.fillMaxWidth().padding(bottom = 4.dp).clickable { host.pickPhoneFolderPublic() }) {
                Text(
                    "Link a folder",
                    fontWeight = FontWeight.SemiBold,
                    color = WdPalette.accentLight,
                    modifier = Modifier.padding(14.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Pick files",
                    color = WdPalette.accentLight,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clickable { host.pickPhoneFilesPublic() }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                )
                Text(
                    "Photos / video",
                    color = WdPalette.accentLight,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clickable { host.pickPhoneMediaPublic() }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                )
                Text(
                    "Allow media access",
                    color = WdPalette.accentLight,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clickable { host.requestPhoneMediaPermissionPublic() }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                )
            }

            WdSurfaceCard(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (folderUri != null) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = WdPalette.accentLight,
                    )
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(folderName, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (folderUri != null) "${entries.size} items · tap a file to attach" else "Link a Downloads / DCIM / project folder",
                            style = MaterialTheme.typography.labelSmall,
                            color = WdPalette.textMetadata,
                        )
                    }
                }
            }

            error?.let {
                Text(it, color = WdPalette.accentLight, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            if (folderUri == null) {
                WdSurfaceCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.PhotoLibrary, null, tint = WdPalette.textMetadata)
                            Text(
                                "  Or use Pick files / Photos without linking a folder",
                                style = MaterialTheme.typography.bodySmall,
                                color = WdPalette.textSecondary,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AttachFile, null, tint = WdPalette.textMetadata)
                            Text(
                                "  Attached files appear in the Agent composer",
                                style = MaterialTheme.typography.bodySmall,
                                color = WdPalette.textSecondary,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(entries, key = { it.uri.toString() }) { entry ->
                        WdSurfaceCard(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.isDirectory) { folders.add(entry.uri to entry.name); reload() }
                                    else host.attachPhoneUriPublic(entry.uri)
                                },
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (entry.isDirectory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (entry.isDirectory) WdPalette.textMetadata else WdPalette.accentLight,
                                )
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(
                                        entry.name,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (entry.isDirectory) "Folder"
                                        else "${entry.mime} · ${formatBytes(entry.size)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = WdPalette.textMetadata,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (!entry.isDirectory) {
                                    Text("Attach", color = WdPalette.accentLight, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> String.format("%.1f MB", n / (1024.0 * 1024.0))
}
