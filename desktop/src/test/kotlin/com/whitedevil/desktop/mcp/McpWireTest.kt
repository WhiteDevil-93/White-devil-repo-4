package com.whitedevil.desktop.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the client really writes to a server. Real MCP servers ignore anything that is not exact JSON-RPC 2.0. */
class McpWireTest {
    private val py: String = listOf("python3", "python").firstOrNull { runCatching { ProcessBuilder(it, "--version").start().waitFor() == 0 }.getOrDefault(false) } ?: "python3"

    @Test fun `the initialize request is complete json-rpc 2 0`(@TempDir dir: File) = runBlocking {
        val script = File(javaClass.classLoader.getResource("mcp/record_stdin.py")!!.toURI()).absolutePath
        val log = File(dir, "wire.txt")
        val client = McpStdioClient("rec", McpServerConfig(command = py, args = listOf(script, log.absolutePath)), requestTimeoutMillis = 1_500)
        try {
            runCatching { client.definitions() }   // times out: the recorder never answers
            delay(300)
            val first = Json.parseToJsonElement(log.readLines().first { it.isNotBlank() }) as JsonObject
            println("WIRE: " + log.readText().trim())
            assertEquals("2.0", (first["jsonrpc"] as? JsonPrimitive)?.content, "jsonrpc must be on the wire: $first")
            assertEquals("initialize", (first["method"] as JsonPrimitive).content)
            assertTrue(first["id"] != null && first["params"] is JsonObject, "$first")
            assertEquals("2024-11-05", ((first["params"] as JsonObject)["protocolVersion"] as JsonPrimitive).content)
        } finally { client.close() }
    }
}
