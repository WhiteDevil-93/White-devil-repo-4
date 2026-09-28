package com.whitedevil

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
}
