package com.whitedevil.veniceagent

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ToolBoxTest {

    @Test
    fun testToolDefinitionsList(@TempDir tempDir: File) {
        val box = ToolBox(workspaceDir = tempDir, allowShell = true, relayUser = "user", relayPass = "pass")
        val names = runBlocking { box.definitions() }.map { it.function.name }

        assertTrue(names.contains("read_file"))
        assertTrue(names.contains("write_file"))
        assertTrue(names.contains("list_directory"))
        assertTrue(names.contains("run_shell_command"))
        assertTrue(names.contains("get_render_status"))
        assertTrue(names.contains("list_prompt_packs"))
        assertTrue(names.contains("run_laptop_command"))
        assertTrue(names.contains("download_civitai_lora"))
    }

    @Test
    fun testRelayToolsHiddenWithoutCredentials(@TempDir tempDir: File) {
        val box = ToolBox(workspaceDir = tempDir, allowShell = true, relayUser = "", relayPass = "")
        val names = runBlocking { box.definitions() }.map { it.function.name }

        // Every one of these would just fail with a configuration error if called, so a default
        // installation without relay credentials shouldn't advertise them at all.
        assertTrue(names.contains("read_file"), "non-relay tools must still be listed: $names")
        assertTrue("get_render_status" !in names, "unexpected: $names")
        assertTrue("list_prompt_packs" !in names, "unexpected: $names")
        assertTrue("run_laptop_command" !in names, "unexpected: $names")
        assertTrue("download_civitai_lora" !in names, "unexpected: $names")
        assertEquals(false, box.relayConfigured)
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

    @Test
    fun testDownloadCivitaiLoraRequiresModelId(@TempDir tempDir: File) {
        val box = ToolBox(workspaceDir = tempDir, allowShell = false)

        val result = runBlocking { box.execute("download_civitai_lora", """{"slug": "demo"}""") }

        assertEquals("Error: 'model_id' argument is required.", result)
    }

    @Test
    fun testRelayToolsRequireCredentials(@TempDir tempDir: File) {
        val box = ToolBox(
            workspaceDir = tempDir,
            allowShell = false,
            relayBaseUrl = "https://example.invalid",
            relayUser = "",
            relayPass = "",
        )

        val result = runBlocking { box.execute("get_render_status", "{}") }

        assertTrue(result.contains("RELAY_USER and RELAY_PASS"))
    }
}
