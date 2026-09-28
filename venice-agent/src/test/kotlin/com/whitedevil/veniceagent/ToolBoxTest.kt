package com.whitedevil.veniceagent

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ToolBoxTest {

    @Test
    fun testToolDefinitionsList() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test-venice-workspace-${System.currentTimeMillis()}")
        val box = ToolBox(workspaceDir = tempDir, allowShell = true)
        val names = runBlocking { box.definitions() }.map { it.function.name }

        assertTrue(names.contains("read_file"))
        assertTrue(names.contains("write_file"))
        assertTrue(names.contains("list_directory"))
        assertTrue(names.contains("run_shell_command"))
        assertTrue(names.contains("get_render_status"))
        assertTrue(names.contains("list_prompt_packs"))
        assertTrue(names.contains("run_laptop_command"))
        assertTrue(names.contains("download_civitai_lora"))
        tempDir.deleteRecursively()
    }

    @Test
    fun testFileReadWriteSandboxing(@TempDir tempDir: File) {
        val box = ToolBox(workspaceDir = tempDir, allowShell = false)

        val writeResult = runBlocking { box.execute("write_file", """{"path": "test.txt", "content": "hello venice"}""") }
        assertTrue(writeResult.contains("Wrote 12 characters to test.txt"))

        val readResult = runBlocking { box.execute("read_file", """{"path": "test.txt"}""") }
        assertEquals("hello venice", readResult)

        val outsideResult = runBlocking { box.execute("read_file", """{"path": "../secret.txt"}""") }
        assertTrue(outsideResult.contains("Error") || outsideResult.contains("Path escapes workspace"))
    }
}
