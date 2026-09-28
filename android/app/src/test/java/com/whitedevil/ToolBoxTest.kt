package com.whitedevil

import com.whitedevil.agent.ToolBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
        assertTrue(names.contains("list_prompt_packs"))
        assertTrue(names.contains("run_laptop_command"))
        assertTrue(names.contains("download_civitai_lora"))
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
