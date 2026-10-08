package com.whitedevil.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * dialog). Without it, mutating tools refuse to run.
 */
class AccessConfig(
    val confirm: ((title: String, detail: String) -> Boolean)? = null,
    /** Root of the phone's shared storage (e.g. /storage/emulated/0). Null = phone tools not offered. */
    val phoneRoot: File? = null,
    val phoneAccessGranted: () -> Boolean = { true },
    /** Opens the system screen where the user grants "All files access". */
    val requestPhoneAccess: (() -> Unit)? = null,
    /** Folder on the laptop (WSL path) that holds the repo checkouts / worktrees. */
    val gitRepoBase: String = "/mnt/a/New folder (4)",
    val defaultRepo: String = "white-devil-repo-4",
)

/**
 * Typed git on the laptop's repos (no raw shell, no force, no `add -A`) and read/write on the phone's
 * shared storage, with a confirmation for anything that sends or changes data.
 */
internal class AccessTools(
    private val cfg: AccessConfig,
    private val runOnLaptop: (code: String) -> String = { "Error: git tools are not connected to a laptop here." },
) {
    private val json = Json { ignoreUnknownKeys = true }

    val definitions: List<ToolDefinition> = buildList {
        add(tool("git_status", "git status of a repo on the laptop (short form with branch).", opt = listOf(REPO)))
        add(
            tool(
                "git_diff", "git diff of a repo on the laptop (truncated to 20 KB). Use staged=true for the index.",
                opt = listOf(REPO, "path" to "Optional single file path inside the repo.", "staged" to "\"true\" to diff staged changes."),
            ),
        )
        add(tool("git_log", "Recent commits (one line each).", opt = listOf(REPO, "count" to "How many, 1-50. Default 10.")))
        add(
            tool(
                "git_commit",
                "Stage ONLY the listed files and commit them. Paths are required: no '.', no wildcards (concurrent sessions share these repos).",
                required = listOf("message" to "Commit message.", "paths" to "JSON array string of repo-relative file paths, e.g. [\"a.kt\",\"b/c.py\"]."),
                opt = listOf(REPO),
            ),
        )
        add(tool("git_push", "Push the CURRENT branch to origin. Asks the user first. Never force, never main/master.", opt = listOf(REPO)))
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
                "git_status" -> git(args) { "git --no-pager status --short --branch" }
                "git_diff" -> git(args) { diffCommand(args) }
                "git_log" -> git(args) {
                    val n = (args.str("count")?.toIntOrNull() ?: 10).coerceIn(1, 50)
                    "git --no-pager log --oneline --decorate -n $n"
                }
                "git_commit" -> commit(args)
                "git_push" -> push(args)
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

    /** A missing confirmation UI is a denial, never an implicit grant. */
    fun approve(title: String, detail: String): Boolean {
        val c = cfg.confirm ?: return false
        return try {
            c(title, detail.take(1500))
        } catch (e: Exception) {
            false
        }
    }

    // ---- git on the laptop ------------------------------------------------------------------

    private fun repoName(args: JsonObject): String? {
        val name = args.str("repo")?.takeIf { it.isNotBlank() } ?: cfg.defaultRepo
        return name.takeIf { SAFE_NAME.matches(it) && !it.startsWith(".") }
    }

    private fun git(args: JsonObject, build: () -> String?): String {
        val repo = repoName(args) ?: return BAD_REPO
        val cmd = build() ?: return "Error: that path is not allowed."
        return runOnLaptop("${enter(repo)}\n$cmd 2>&1 | head -c 20000")
    }

    internal fun enter(repo: String): String {
        val dir = sq(cfg.gitRepoBase.trimEnd('/') + "/" + repo)
        return "cd $dir 2>/dev/null && test -e .git || { echo 'Error: not a git repo: $repo'; exit 2; }"
    }

    private fun diffCommand(args: JsonObject): String? {
        val staged = args.str("staged").equals("true", ignoreCase = true)
        val path = args.str("path")?.takeIf { it.isNotBlank() }?.let { checkedPath(it) ?: return null }
        return buildString {
            append("git --no-pager diff --no-color")
            if (staged) append(" --cached")
            if (path != null) append(" -- ").append(sq(path))
        }
    }

    private fun commit(args: JsonObject): String {
        val repo = repoName(args) ?: return BAD_REPO
        val message = args.str("message")?.trim().orEmpty()
        if (message.isEmpty()) return "Error: 'message' is required."
        val raw = args["paths"]?.let { it as? JsonArray ?: (it as? JsonPrimitive)?.content?.let { s -> parseJsonOrNull(s) } }
        val paths = pathList(raw)
            ?: return "Error: 'paths' must be a non-empty JSON array of explicit repo-relative file paths (no '.', no wildcards)."
        if (!approve("Commit to Git", "Stage ${paths.joinToString(", ")} and commit in '$repo' with message: $message")) {
            return "Denied: the user did not approve the commit (or no confirmation UI is available)."
        }
        val code = "${enter(repo)}\ngit add -- ${paths.joinToString(" ") { sq(it) }} && git commit -m ${sq(message)} 2>&1 | head -c 20000"
        return runOnLaptop(code)
    }

    private fun push(args: JsonObject): String {
        val repo = repoName(args) ?: return BAD_REPO
        if (!approve("Push to GitHub", "Push the current branch of '$repo' to origin.\n(main, master and detached HEAD are refused; no force.)")) {
            return "Denied: the user did not approve the push (or no confirmation UI is available)."
        }
        val code = "${enter(repo)}\n" +
            "b=\$(git rev-parse --abbrev-ref HEAD)\n" +
            "case \"\$b\" in main|master|HEAD) echo \"Error: refusing to push '\$b'\"; exit 3;; esac\n" +
            "git push -u origin \"\$b\" 2>&1 | head -c 20000"
        return runOnLaptop(code)
    }

    private fun parseJsonOrNull(s: String): JsonElement? = try { json.parseToJsonElement(s) } catch (e: Exception) { null }

    /** Explicit repo-relative paths only. Null if empty or any entry is unsafe. */
    internal fun pathList(el: JsonElement?): List<String>? {
        val arr = el as? JsonArray ?: return null
        val out = arr.mapNotNull { (it as? JsonPrimitive)?.content }.map { checkedPath(it) ?: return null }
        return out.takeIf { it.isNotEmpty() && it.size == arr.size }
    }

    internal fun checkedPath(p: String): String? {
        val t = p.trim()
        if (t.isEmpty() || t == "." || t.startsWith("-") || t.startsWith("/") || t.startsWith("~")) return null
        if (t.any { it in "*?[]\\\n\r\u0000" }) return null
        if (t.split('/').any { it == ".." }) return null
        return t
    }

    // ---- phone storage ----------------------------------------------------------------------

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
        if (!approve("Write file on phone", "$path\n${body.length} characters, $state.")) {
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
        if (!approve("Delete on phone", "$path (${if (f.isDirectory) "empty folder" else "${f.length()} B file"})")) {
            return "Denied: the user did not approve the delete (or no confirmation UI is available)."
        }
        return if (f.delete()) "Deleted $path" else "Failed to delete $path"
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private companion object {
        val REPO = "repo" to "Checkout folder name under the repos folder (e.g. wd-ui). Defaults to the main repo."
        val SAFE_NAME = Regex("^[A-Za-z0-9._-]+$")
        const val BAD_REPO = "Error: 'repo' must be a plain folder name (letters, digits, . _ -)."

        /** POSIX single-quote. */
        fun sq(v: String): String = "'" + v.replace("'", "'\\''") + "'"
    }

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
