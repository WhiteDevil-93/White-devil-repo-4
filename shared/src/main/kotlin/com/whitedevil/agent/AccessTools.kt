package com.whitedevil.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * What the host app lends the agent. Everything defaults to "no extra power", so desktop and the
 * CLI compile and behave exactly as before.
 *
 * [confirm] is called on a worker thread and must block until the user answers (Android shows a
 * dialog). Without it, the NEW risky tools (phone_write, phone_delete) refuse to run, while the
 * older tools keep their previous ungated behaviour.
 */
class AccessConfig(
    val confirm: ((title: String, detail: String) -> Boolean)? = null,
    /** Root of the phone's shared storage (e.g. /storage/emulated/0). Null = phone tools not offered. */
    val phoneRoot: File? = null,
    val phoneAccessGranted: () -> Boolean = { true },
    /** Opens the system screen where the user grants "All files access". */
    val requestPhoneAccess: (() -> Unit)? = null,
)

/** Read/write on the phone's shared storage, with a confirmation for anything that changes data. */
internal class AccessTools(private val cfg: AccessConfig) {
    private val json = Json { ignoreUnknownKeys = true }

    val definitions: List<ToolDefinition> = buildList {
        if (cfg.phoneRoot != null) {
            add(tool("phone_list", "List a folder on the phone's shared storage (needs All files access).", opt = listOf("path" to "Folder, relative to shared storage. Default \"/\".")))
            add(
                tool(
                    "phone_read", "Read a text file from the phone's shared storage (max 200 KB).",
                    required = listOf("path" to "File path relative to shared storage."),
                ),
            )
            add(
                tool(
                    "phone_write", "Create or overwrite a file on the phone's shared storage. Asks the user first.",
                    required = listOf("path" to "File path relative to shared storage.", "content" to "Text to write (max 1 MB)."),
                ),
            )
            add(
                tool(
                    "phone_delete", "Delete one file or one EMPTY folder on the phone's shared storage. Asks the user first.",
                    required = listOf("path" to "Path relative to shared storage."),
                ),
            )
        }
    }

    /** Null when [name] is not one of ours. */
    fun execute(name: String, argumentsJson: String): String? {
        if (definitions.none { it.function.name == name }) return null
        val args = try {
            json.parseToJsonElement(argumentsJson) as? JsonObject ?: JsonObject(emptyMap())
        } catch (e: Exception) {
            return "Error: arguments are not valid JSON."
        }
        return try {
            when (name) {
                "phone_list" -> phone { phoneList(args.str("path") ?: "/") }
                "phone_read" -> phone { phoneRead(args.str("path")) }
                "phone_write" -> phone { phoneWrite(args.str("path"), args.str("content")) }
                "phone_delete" -> phone { phoneDelete(args.str("path")) }
                else -> null
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /** True if the user (or the absence of a UI, for legacy tools) allows it. */
    fun approve(title: String, detail: String, requireUi: Boolean): Boolean {
        val c = cfg.confirm ?: return !requireUi
        return try {
            c(title, detail.take(1500))
        } catch (e: Exception) {
            false
        }
    }

    private fun phone(block: () -> String): String {
        val root = cfg.phoneRoot ?: return "Error: phone storage is not available here."
        if (!cfg.phoneAccessGranted()) {
            cfg.requestPhoneAccess?.invoke()
            return "Error: All files access is not granted for this app. I opened the settings screen. " +
                "Ask the user to switch it on for WhiteDevil, then retry."
        }
        require(root.exists()) { "phone storage root is missing" }
        return block()
    }

    private fun phoneFile(path: String): File {
        val root = cfg.phoneRoot!!.canonicalFile
        val target = File(root, path.trimStart('/')).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "Path '$path' escapes phone storage"
        }
        return target
    }

    private fun phoneList(path: String): String {
        val dir = phoneFile(path)
        if (!dir.isDirectory) return "Error: not a folder: $path"
        val items = dir.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
        if (items.isEmpty()) return "(empty folder)"
        val shown = items.take(300).joinToString("\n") { if (it.isDirectory) "${it.name}/" else "${it.name}  (${it.length()} B)" }
        return if (items.size > 300) "$shown\n... ${items.size - 300} more" else shown
    }

    private fun phoneRead(path: String?): String {
        if (path.isNullOrBlank()) return "Error: 'path' is required."
        val f = phoneFile(path)
        if (!f.isFile) return "Error: file not found: $path"
        val bytes = f.inputStream().use { it.readNBytes(200_000) }
        if (bytes.any { it == 0.toByte() }) return "Binary file (${f.length()} B), not shown."
        val text = String(bytes, Charsets.UTF_8)
        return if (f.length() > bytes.size) "$text\n[truncated: ${f.length()} B total]" else text
    }

    private fun phoneWrite(path: String?, content: String?): String {
        if (path.isNullOrBlank()) return "Error: 'path' is required."
        val body = content ?: ""
        if (body.length > 1_000_000) return "Error: content is over 1 MB."
        val f = phoneFile(path)
        val state = if (f.exists()) "OVERWRITES existing file (${f.length()} B)" else "new file"
        if (!approve("Write file on phone", "$path\n${body.length} characters, $state.", requireUi = true)) {
            return "Denied: the user did not approve the write (or no confirmation UI is available)."
        }
        if (f.isDirectory) return "Error: that path is a folder."
        f.parentFile?.mkdirs()
        f.writeText(body)
        return "Wrote ${body.length} characters to $path"
    }

    private fun phoneDelete(path: String?): String {
        if (path.isNullOrBlank()) return "Error: 'path' is required."
        val f = phoneFile(path)
        if (f.path == cfg.phoneRoot!!.canonicalFile.path) return "Error: refusing to delete the storage root."
        if (!f.exists()) return "Error: not found: $path"
        if (f.isDirectory && (f.list()?.isNotEmpty() == true)) return "Error: folder is not empty; delete its files first."
        if (!approve("Delete on phone", "$path (${if (f.isDirectory) "empty folder" else "${f.length()} B file"})", requireUi = true)) {
            return "Denied: the user did not approve the delete (or no confirmation UI is available)."
        }
        return if (f.delete()) "Deleted $path" else "Failed to delete $path"
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun tool(
        name: String,
        description: String,
        required: List<Pair<String, String>> = emptyList(),
        opt: List<Pair<String, String>> = emptyList(),
    ) = ToolDefinition(
        function = ToolFunctionSpec(
            name = name,
            description = description,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    (required + opt).forEach { (n, d) ->
                        putJsonObject(n) {
                            put("type", "string")
                            put("description", d)
                        }
                    }
                }
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it.first)) } })
            },
        ),
    )
}
