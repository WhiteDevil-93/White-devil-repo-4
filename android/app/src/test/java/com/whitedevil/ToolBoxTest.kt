package com.whitedevil

import com.whitedevil.agent.AccessConfig
import com.whitedevil.agent.Agent
import com.whitedevil.agent.ToolBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

class ToolBoxTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun testToolDefinitions() {
        val dir = folder.newFolder("workspace")
        val box = ToolBox(
            workspaceDir = dir,
            relayBaseUrl = "https://84-12-112-249.sslip.io",
            relayUser = "anon3",
            relayPass = "secret"
        )
        val names = box.definitions.map { it.function.name }

        assertTrue(names.contains("read_file"))
        assertTrue(names.contains("write_file"))
        assertTrue(names.contains("list_directory"))
        assertTrue(names.contains("delete_file"))
        assertTrue(names.contains("get_render_status"))
        assertTrue(names.contains("review_latest_render"))
        assertTrue(names.contains("render_assess_adjust_cycle"))
        assertTrue(names.contains("list_prompt_packs"))
        assertTrue(names.contains("run_laptop_command"))
        assertTrue(names.contains("download_civitai_lora"))
    }

    @Test
    fun testLatestRenderIncludesActualPreviewImage() {
        val server = ServerSocket(0)
        val library = """
            [
              {"title":"Older","source":"colab","clips":[{"name":"old.mp4","mtime":10}]},
              {"title":"Newest pack","source":"thunder","clips":[{"name":"latest render.mp4","mtime":20}]}
            ]
        """.trimIndent().toByteArray()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())
        val serving = thread(start = true, isDaemon = true) {
            repeat(2) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val requestPath = reader.readLine().split(" ")[1]
                    while (reader.readLine().isNotEmpty()) Unit
                    val (contentType, body) = if (requestPath == "/api/media/library") {
                        "application/json" to library
                    } else {
                        "image/jpeg" to jpeg
                    }
                    socket.getOutputStream().use { output ->
                        output.write(
                            "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(),
                        )
                        output.write(body)
                    }
                }
            }
        }
        try {
            val box = ToolBox(
                workspaceDir = folder.newFolder("latest_render"),
                relayBaseUrl = "http://127.0.0.1:${server.localPort}",
                relayUser = "",
                relayPass = "",
            )
            val result = box.executeDetailed("review_latest_render", "{}")

            assertTrue(result.text.contains("latest render.mp4"))
            assertTrue(result.text.contains("Newest pack"))
            assertEquals(1, result.imageDataUrls.size)
            assertTrue(result.imageDataUrls.single().startsWith("data:image/jpeg;base64,"))
        } finally {
            server.close()
            serving.join(1000)
        }
    }

    @Test
    fun testLaptopCommandForwardsCwdLanguageAndTimeout() {
        val server = ServerSocket(0)
        var postedBody = ""
        val serving = thread(start = true, isDaemon = true) {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                reader.readLine()
                var contentLength = 0
                while (true) {
                    val line = reader.readLine()
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toInt()
                    }
                }
                val chars = CharArray(contentLength)
                reader.read(chars)
                postedBody = String(chars)
                val response = """{"ok":true,"exit":0,"cwd":"~/projects/demo","output":"tests passed"}""".toByteArray()
                socket.getOutputStream().use { output ->
                    output.write(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                    output.write(response)
                }
            }
        }
        try {
            val box = ToolBox(
                workspaceDir = folder.newFolder("laptop_command"),
                relayBaseUrl = "http://127.0.0.1:${server.localPort}",
                relayUser = "",
                relayPass = "",
            )
            val result = box.execute(
                "run_laptop_command",
                """{"code":"pytest -q","lang":"bash","cwd":"projects/demo","timeout_seconds":120}""",
            )
            serving.join(1000)

            assertTrue(result.contains("tests passed"))
            assertTrue(postedBody.contains("\"code\":\"pytest -q\""))
            assertTrue(postedBody.contains("\"cwd\":\"projects/demo\""))
            assertTrue(postedBody.contains("\"timeout\":120"))
        } finally {
            server.close()
        }
    }

    @Test
    fun testFileOperationsAndSandboxing() {
        val dir = folder.newFolder("workspace_sandbox")
        val box = ToolBox(
            workspaceDir = dir,
            relayBaseUrl = "https://84-12-112-249.sslip.io",
            relayUser = "anon3",
            relayPass = "secret"
        )

        val writeRes = box.execute("write_file", """{"path": "notes.txt", "content": "hello from android"}""")
        assertTrue(writeRes.contains("Wrote 18 characters to notes.txt"))

        val readRes = box.execute("read_file", """{"path": "notes.txt"}""")
        assertEquals("hello from android", readRes)

        val listRes = box.execute("list_directory", """{"path": "."}""")
        assertTrue(listRes.contains("notes.txt"))

        val deleteRes = box.execute("delete_file", """{"path": "notes.txt"}""")
        assertTrue(deleteRes.contains("Deleted notes.txt"))

        // Escaping directory check
        val escapeRes = box.execute("read_file", """{"path": "../secret.txt"}""")
        assertTrue(escapeRes.contains("Error") || escapeRes.contains("escapes"))
    }





    @Test
    fun testParseSlashCommands() {
        assertTrue(Agent.parseSlash("hello") == null)
        assertTrue(Agent.parseSlash("/review") is Agent.SlashAction.Review)
        val c = Agent.parseSlash("/cycle status") as Agent.SlashAction.Cycle
        assertEquals("status", c.action)
        val c2 = Agent.parseSlash("/cycle start src=abc12345") as Agent.SlashAction.Cycle
        assertEquals("start", c2.action)
        assertEquals("abc12345", c2.src)
        assertTrue(Agent.parseSlash("/help") is Agent.SlashAction.Help)
    }

    // ---- phone storage tools + confirm gate -------------------------------------------------

    private fun boxWith(access: AccessConfig, name: String = "ws"): ToolBox = ToolBox(
        workspaceDir = folder.newFolder(name),
        relayBaseUrl = "",
        relayUser = "",
        relayPass = "",
        access = access,
    )

    @Test
    fun phoneToolsAreOnlyOfferedWhenAPhoneRootIsGiven() {
        val without = boxWith(AccessConfig(), "a").definitions.map { it.function.name }
        assertTrue(without.none { it.startsWith("phone_") })
        val with = boxWith(AccessConfig(phoneRoot = folder.newFolder("sd1")), "b").definitions.map { it.function.name }
        assertTrue(with.containsAll(listOf("phone_list", "phone_read", "phone_write", "phone_delete")))
    }

    @Test
    fun phoneReadAndListWorkAndCannotEscapeTheRoot() {
        val root = folder.newFolder("sd2")
        File(root, "notes.txt").writeText("hello")
        val box = boxWith(AccessConfig(phoneRoot = root), "c")
        assertTrue(box.execute("phone_list", "{}").contains("notes.txt"))
        assertEquals("hello", box.execute("phone_read", """{"path":"notes.txt"}"""))
        val escape = box.execute("phone_read", """{"path":"../../etc/passwd"}""")
        assertTrue(escape, escape.contains("escapes phone storage"))
    }

    @Test
    fun phoneWriteAndDeleteNeedTheUserAndDenyByDefault() {
        val root = folder.newFolder("sd3")
        // No confirmation UI at all: the new risky tools refuse.
        val noUi = boxWith(AccessConfig(phoneRoot = root), "d")
        assertTrue(noUi.execute("phone_write", """{"path":"x.txt","content":"a"}""").startsWith("Denied"))
        assertTrue(!File(root, "x.txt").exists())
        // User says no.
        val no = boxWith(AccessConfig(confirm = { _, _ -> false }, phoneRoot = root), "e")
        assertTrue(no.execute("phone_write", """{"path":"x.txt","content":"a"}""").startsWith("Denied"))
        assertTrue(!File(root, "x.txt").exists())
        // User says yes: the dialog text names the path, and the file is written then deleted.
        var asked = ""
        val yes = boxWith(AccessConfig(confirm = { t, d -> asked += "$t|$d;"; true }, phoneRoot = root), "f")
        assertTrue(yes.execute("phone_write", """{"path":"sub/x.txt","content":"abc"}""").startsWith("Wrote 3"))
        assertEquals("abc", File(root, "sub/x.txt").readText())
        assertTrue(yes.execute("phone_delete", """{"path":"sub/x.txt"}""").startsWith("Deleted"))
        assertTrue(!File(root, "sub/x.txt").exists())
        assertTrue(asked, asked.contains("sub/x.txt"))
    }

    @Test
    fun phoneDeleteRefusesTheRootAndNonEmptyFolders() {
        val root = folder.newFolder("sd4")
        File(root, "dir").mkdirs()
        File(root, "dir/f.txt").writeText("x")
        val box = boxWith(AccessConfig(confirm = { _, _ -> true }, phoneRoot = root), "g")
        assertTrue(box.execute("phone_delete", """{"path":"/"}""").contains("refusing"))
        assertTrue(box.execute("phone_delete", """{"path":"dir"}""").contains("not empty"))
        assertTrue(File(root, "dir/f.txt").exists())
    }

    @Test
    fun missingAllFilesAccessOpensSettingsAndSaysSo() {
        var opened = 0
        val box = boxWith(
            AccessConfig(phoneRoot = folder.newFolder("sd5"), phoneAccessGranted = { false }, requestPhoneAccess = { opened++ }),
            "h",
        )
        val out = box.execute("phone_list", "{}")
        assertEquals(1, opened)
        assertTrue(out, out.contains("All files access is not granted"))
    }

    @Test
    fun existingRiskyToolsAskFirstWhenThereIsAUiAndStayUngatedWithout() {
        // delete_file on the app workspace: denied when the user says no, file survives.
        val ws = folder.newFolder("ws-gate")
        File(ws, "keep.txt").writeText("x")
        val deny = ToolBox(ws, "", "", "", AccessConfig(confirm = { _, _ -> false }))
        assertTrue(deny.execute("delete_file", """{"path":"keep.txt"}""").startsWith("Denied"))
        assertTrue(File(ws, "keep.txt").exists())
        // With no UI the old behaviour is unchanged (desktop / CLI).
        val legacy = ToolBox(ws, "", "", "")
        assertTrue(legacy.execute("delete_file", """{"path":"keep.txt"}""").startsWith("Deleted"))
        // A GET hub_request is never prompted; a POST is.
        var prompts = 0
        val counting = ToolBox(folder.newFolder("ws-gate2"), "", "", "", AccessConfig(confirm = { _, _ -> prompts++; false }))
        counting.execute("hub_request", """{"method":"GET","path":"/api/status"}""")
        assertEquals(0, prompts)
        assertTrue(counting.execute("hub_request", """{"method":"POST","path":"/api/x","body":"{}"}""").startsWith("Denied"))
        assertTrue(counting.execute("queue_gpu_render", """{"cloud":"ltx","prompt":"p"}""").startsWith("Denied"))
        assertTrue(counting.execute("run_laptop_command", """{"code":"ls"}""").startsWith("Denied"))
        assertEquals(3, prompts)
    }

}
