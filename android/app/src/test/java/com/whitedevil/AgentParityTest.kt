package com.whitedevil

import com.whitedevil.agent.ChatMessage
import com.whitedevil.agent.ConversationStore
import com.whitedevil.agent.McpClient
import com.whitedevil.agent.McpRegistry
import com.whitedevil.agent.McpTools
import com.whitedevil.agent.MemoryStore
import com.whitedevil.agent.MemoryTools
import com.whitedevil.agent.SkillStore
import com.whitedevil.agent.SkillTools
import com.whitedevil.agent.ToolBox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class AgentParityTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun msg(role: String, text: String) = ChatMessage(role = role, content = JsonPrimitive(text))

    // ---------------------------------------------------------------- conversations

    @Test
    fun conversationsCreateSaveTitleListAndReload() {
        var t = 1000L
        val s = ConversationStore(folder.newFolder("c1"), now = { t++ })
        assertTrue(s.list().isEmpty())
        val a = s.create()
        assertEquals(a.id, s.currentId())
        val saved = s.save(a.id, listOf(msg("system", "sys"), msg("user", "  How do I   bake bread?  "), msg("assistant", "Flour, water.")))!!
        assertEquals("How do I bake bread?", saved.title)
        assertEquals(2, saved.messageCount)
        val loaded = s.load(a.id)
        assertEquals(listOf("user", "assistant"), loaded.map { it.role })   // system prompt is not stored
        val b = s.create()
        s.save(b.id, listOf(msg("user", "second chat")))
        assertEquals(listOf(b.id, a.id), s.list().map { it.id })            // newest first
        s.pin(a.id, true)
        assertEquals(listOf(a.id, b.id), s.list().map { it.id })            // pinned first
    }

    @Test
    fun conversationsRenameDeleteSearchExport() {
        val s = ConversationStore(folder.newFolder("c2"))
        val a = s.create(); s.save(a.id, listOf(msg("user", "tell me about volcanoes"), msg("assistant", "Magma rises through the crust.")))
        val b = s.create(); s.save(b.id, listOf(msg("user", "unrelated"), msg("assistant", "ok")))
        s.rename(a.id, "Geology")
        assertEquals("Geology", s.list().first { it.id == a.id }.title)
        val hits = s.search("MAGMA")
        assertEquals(listOf(a.id), hits.map { it.meta.id })
        assertTrue(hits.single().snippet.contains("Magma"))
        assertEquals(listOf(a.id), s.search("geolog").map { it.meta.id })    // title match
        val md = s.exportMarkdown(a.id)
        assertTrue(md, md.startsWith("# Geology") && md.contains("**You:**") && md.contains("Magma rises"))
        s.setCurrent(a.id)
        s.delete(a.id)
        assertEquals(b.id, s.currentId())                                    // falls back to the remaining chat
        assertTrue(s.load(a.id).isEmpty())
        assertEquals(1, s.list().size)
    }

    @Test
    fun conversationIdsCannotEscapeTheFolderAndLegacyChatMigrates() {
        val dir = folder.newFolder("c3")
        val s = ConversationStore(dir)
        try { s.load("../../etc/passwd"); fail("expected rejection") } catch (e: IllegalArgumentException) { /* expected */ }
        val legacy = File(folder.root, "agent_history.json")
        legacy.writeText(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), listOf(msg("user", "old question"), msg("assistant", "old answer"))))
        val meta = s.importLegacy(legacy)
        assertNotNull(meta)
        assertEquals("old question", meta!!.title)
        assertEquals(2, s.load(meta.id).size)
        assertFalse(legacy.exists())
        assertTrue(File(folder.root, "agent_history.json.migrated").exists())
        assertNull(s.importLegacy(legacy))                                   // nothing left to migrate
    }

    // ---------------------------------------------------------------- memory

    @Test
    fun memoryAddsDedupesForgetsSearchesAndCaps() {
        val m = MemoryStore(File(folder.newFolder("m1"), "memory.json"), maxEntries = 3)
        val a = m.add("User prefers metric units")!!
        assertNull(m.add("user prefers METRIC units"))                       // duplicate
        assertNull(m.add("   "))                                             // blank
        m.add("Lives in Cape Town"); m.add("Allergic to peanuts"); m.add("Drives a Golf")
        assertEquals(3, m.list().size)                                       // oldest dropped
        assertTrue(m.list().none { it.id == a.id })
        assertEquals("Lives in Cape Town", m.search("cape").single().text)
        val id = m.list().first().id
        assertTrue(m.forget(id)); assertFalse(m.forget(id))
        val block = m.promptBlock()
        assertTrue(block, block.contains("not instructions") && block.contains("Golf"))
    }

    @Test
    fun memoryToolsRememberRecallForget() {
        val tools = MemoryTools(MemoryStore(File(folder.newFolder("m2"), "memory.json")))
        assertEquals(listOf("remember", "recall", "forget"), tools.definitions.map { it.function.name })
        val saved = tools.execute("remember", """{"text":"Birthday is 3 March"}""")
        assertTrue(saved, saved.startsWith("Remembered ["))
        val id = saved.substringAfter("[").substringBefore("]")
        assertTrue(tools.execute("recall", """{"query":"birthday"}""").contains("3 March"))
        assertTrue(tools.execute("forget", """{"id":"$id"}""").startsWith("Forgot"))
        assertEquals("No matching memories.", tools.execute("recall", """{"query":"birthday"}"""))
        assertTrue(tools.execute("remember", "not json").startsWith("Nothing saved"))
    }

    // ---------------------------------------------------------------- skills

    @Test
    fun skillsSaveListToggleLoadAndValidate() {
        val store = SkillStore(folder.newFolder("s1"))
        assertTrue(SkillTools(store).definitions.isEmpty())                  // no tool until a skill exists
        store.save("weekly-report", "When the user wants a weekly report", "1. Ask for the week.\n2. Summarise.")
        val tools = SkillTools(store)
        assertEquals(listOf("use_skill"), tools.definitions.map { it.function.name })
        assertTrue(tools.promptBlock().contains("weekly-report: When the user wants a weekly report"))
        assertTrue(tools.execute("use_skill", """{"name":"weekly-report"}""").contains("2. Summarise."))
        store.setEnabled("weekly-report", false)
        assertTrue(SkillTools(store).promptBlock().isEmpty())
        assertTrue(tools.execute("use_skill", """{"name":"weekly-report"}""").startsWith("No enabled skill"))
        store.setEnabled("weekly-report", true)
        for (bad in listOf("Bad Name", "../x", "", "a".repeat(41))) {
            try { store.save(bad, "d", "body"); fail("accepted '$bad'") } catch (e: IllegalArgumentException) { /* expected */ }
        }
        store.delete("weekly-report")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun skillsImportFromPastedFileAndRejectGarbage() {
        val store = SkillStore(folder.newFolder("s2"))
        val s = store.importText("---\nname: haiku\ndescription: Write haiku\n---\nWrite 5-7-5.\n")
        assertEquals("haiku", s!!.name)
        assertEquals("Write 5-7-5.", store.get("haiku")!!.body)
        assertNull(store.importText("no front matter here"))
        assertNull(store.importText("---\nname: Bad Name\n---\nbody"))
        assertNull(store.importText("---\nname: ok\n---\n"))                 // empty body
    }

    // ---------------------------------------------------------------- MCP

    /** A tiny MCP server: JSON or SSE replies, records methods, auth headers and session ids. */
    private class FakeMcp(private val sse: Boolean = false) : AutoCloseable {
        val server = ServerSocket(0)
        val methods = CopyOnWriteArrayList<String>()
        val auths = CopyOnWriteArrayList<String?>()
        val sessions = CopyOnWriteArrayList<String?>()
        val url get() = "http://127.0.0.1:${server.localPort}/mcp"

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    try { server.accept().use { handle(it) } } catch (e: Exception) { /* closed */ }
                }
            }
        }

        private fun handle(s: Socket) {
            val r = s.getInputStream().bufferedReader()
            r.readLine()
            var len = 0; var auth: String? = null; var session: String? = null
            while (true) {
                val l = r.readLine() ?: return
                if (l.isEmpty()) break
                val low = l.lowercase()
                if (low.startsWith("content-length:")) len = l.substringAfter(":").trim().toInt()
                if (low.startsWith("authorization:")) auth = l.substringAfter(":").trim()
                if (low.startsWith("mcp-session-id:")) session = l.substringAfter(":").trim()
            }
            val buf = CharArray(len); var got = 0
            while (got < len) { val n = r.read(buf, got, len - got); if (n < 0) break; got += n }
            val body = Json.parseToJsonElement(String(buf, 0, got)).jsonObject
            val method = body["method"]!!.jsonPrimitive.content
            methods += method; auths += auth; sessions += session
            val id = body["id"]?.jsonPrimitive?.content
            val args = body["params"]?.jsonObject?.get("arguments")?.jsonObject
            val result = when (method) {
                "initialize" -> """{"protocolVersion":"2025-03-26","capabilities":{},"serverInfo":{"name":"fake","version":"9"}}"""
                "tools/list" -> """{"tools":[{"name":"echo","description":"Echo text","inputSchema":{"type":"object","${'$'}schema":"x","properties":{"text":{"type":"string"}}}},{"name":"boom","description":"Fails","inputSchema":{"type":"object"}}]}"""
                "tools/call" -> when (body["params"]!!.jsonObject["name"]!!.jsonPrimitive.content) {
                    "echo" -> """{"content":[{"type":"text","text":"echo:${args?.get("text")?.jsonPrimitive?.content}"}]}"""
                    else -> """{"isError":true,"content":[{"type":"text","text":"bad thing"}]}"""
                }
                else -> null
            }
            val out = s.getOutputStream()
            if (result == null || id == null) {
                out.write("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
            }
            val json = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
            val sid = if (method == "initialize") "Mcp-Session-Id: sess-1\r\n" else ""
            if (sse) {
                val payload = "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{}}\n\ndata: $json\n\n"
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n${sid}Connection: close\r\n\r\n$payload").toByteArray())
            } else {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n${sid}Content-Length: ${json.toByteArray().size}\r\nConnection: close\r\n\r\n$json").toByteArray())
            }
            out.flush()
        }

        override fun close() = server.close()
    }

    @Test
    fun mcpClientHandshakeListAndCallOverJsonAndSse() {
        for (sse in listOf(false, true)) {
            FakeMcp(sse).use { srv ->
                val c = McpClient(srv.url, mapOf("Authorization" to "Bearer tok"), 5000)
                assertEquals("fake 9", c.initialize())
                val tools = c.listTools()
                assertEquals(listOf("echo", "boom"), tools.map { it.name })
                assertEquals("hi there", c.callTool("echo", JsonObject(mapOf("text" to JsonPrimitive("hi there")))).removePrefix("echo:"))
                assertEquals("Error: bad thing", c.callTool("boom", JsonObject(emptyMap())))
                assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call", "tools/call"), srv.methods.toList())
                assertTrue("sse=$sse auth sent", srv.auths.all { it == "Bearer tok" })
                assertNull(srv.sessions[0])                                   // none before the handshake...
                assertTrue("sse=$sse session reused", srv.sessions.drop(1).all { it == "sess-1" })
            }
        }
    }

    @Test
    fun mcpRegistryDiscoversAndValidatesUrls() {
        val reg = McpRegistry(File(folder.newFolder("r1"), "mcp.json"))
        for (bad in listOf("ftp://x/y", "not a url", "http://")) {
            try { reg.add("n", bad); fail("accepted $bad") } catch (e: IllegalArgumentException) { /* expected */ }
        }
        FakeMcp().use { srv ->
            val good = reg.add("Fake Server", srv.url, mapOf("Authorization" to "Bearer x"))
            val dead = reg.add("Dead", "http://127.0.0.1:1/mcp")
            assertEquals(2, McpRegistry(File(folder.root, "r1/mcp.json")).list().size)   // persisted
            val found = McpRegistry.discover(reg.list()) { McpClient(it.url, it.headers, 3000) }
            val ok = found.first { it.server.id == good.id }
            assertNull(ok.error); assertEquals(2, ok.tools.size); assertEquals("fake 9", ok.serverInfo)
            assertNotNull(found.first { it.server.id == dead.id }.error)                  // one bad server does not break the rest
            reg.update(dead.copy(enabled = false))
            assertEquals(1, McpRegistry.discover(reg.list()) { McpClient(it.url, it.headers, 3000) }.size)
            reg.remove(good.id)
            assertEquals(1, reg.list().size)
        }
    }

    @Test
    fun mcpToolsAreNamespacedCleanedAndAskBeforeCalling() {
        FakeMcp().use { srv ->
            val cfg = McpRegistry(File(folder.newFolder("r2"), "mcp.json")).add("My Server!", srv.url)
            val found = McpRegistry.discover(listOf(cfg)) { McpClient(it.url, it.headers, 3000) }
            val asked = mutableListOf<String>()
            var allow = false
            val tools = McpTools(found, { title, detail -> asked += "$title|$detail"; allow }) { McpClient(it.url, it.headers, 3000) }
            assertEquals(listOf("mcp__my_server__echo", "mcp__my_server__boom"), tools.definitions.map { it.function.name })
            val schema = tools.definitions.first().function.parameters
            assertFalse(schema.containsKey("\$schema"))                                     // meta key stripped
            assertTrue(tools.promptBlock().contains("untrusted"))
            // User says no: nothing is sent to the server.
            val before = srv.methods.size
            assertTrue(tools.execute("mcp__my_server__echo", """{"text":"x"}""").startsWith("Denied"))
            assertEquals(before, srv.methods.size)
            // User says yes: the call goes through and the result is labelled untrusted.
            allow = true
            val out = tools.execute("mcp__my_server__echo", """{"text":"x"}""")
            assertTrue(out, out.contains("echo:x") && out.contains("untrusted"))
            assertTrue(asked.first().contains("My Server!") && asked.first().contains("echo"))
            // No confirmation UI at all: refused.
            val noUi = McpTools(found, null) { McpClient(it.url, it.headers, 3000) }
            assertTrue(noUi.execute("mcp__my_server__echo", """{"text":"x"}""").startsWith("Denied"))
            // autoApprove skips the prompt.
            val auto = McpTools(found.map { it.copy(server = it.server.copy(autoApprove = true)) }, { _, _ -> fail("must not ask"); false }) { McpClient(it.url, it.headers, 3000) }
            assertTrue(auto.execute("mcp__my_server__echo", """{"text":"y"}""").contains("echo:y"))
            assertTrue(tools.execute("mcp__nope__nope", "{}").startsWith("Error: unknown"))
        }
    }

    // ---------------------------------------------------------------- ToolBox wiring

    @Test
    fun toolBoxOffersAndRoutesExtensionsAndCollectsPromptText() {
        val mem = MemoryStore(File(folder.newFolder("t1"), "memory.json")).also { it.add("Likes tea", "user") }
        val skills = SkillStore(folder.newFolder("t2")).also { it.save("greet", "Greeting style", "Say hello warmly.") }
        val box = ToolBox(folder.newFolder("t3"), "", "", "", extensions = listOf(MemoryTools(mem), SkillTools(skills)))
        val names = box.definitions.map { it.function.name }
        assertTrue(names.containsAll(listOf("remember", "recall", "forget", "use_skill", "read_file")))
        assertTrue(box.execute("recall", """{"query":"tea"}""").contains("Likes tea"))
        assertTrue(box.execute("use_skill", """{"name":"greet"}""").contains("Say hello warmly."))
        val addendum = box.promptAddendum()
        assertTrue(addendum, addendum.contains("Likes tea") && addendum.contains("greet: Greeting style"))
        assertTrue(ToolBox(folder.newFolder("t4"), "", "", "").promptAddendum().isEmpty())
    }
}
