package com.whitedevil.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.whitedevil.agent.Agent
import com.whitedevil.agent.ChatMessage
import com.whitedevil.agent.MessageContent
import com.whitedevil.agent.ToolCall
import com.whitedevil.agent.ToolCallFunction
import com.whitedevil.agent.memoryPatch
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSessionTest {
    private fun msg(role: String, text: String) = ChatMessage(role = role, content = MessageContent.text(text))
    private fun call(id: String, name: String, args: String = "{}") = ToolCall(id = id, function = ToolCallFunction(name = name, arguments = args))

    private val conversation = listOf(
        msg("user", "put the 14B remix on a new L40"),
        ChatMessage(role = "assistant", content = MessageContent.text(""), toolCalls = listOf(call("c1", "hub_request", """{"path":"/api/setup/catalog"}"""), call("c2", "remember", """{"note":"likes L40"}"""))),
        ChatMessage(role = "tool", content = MessageContent.text("catalog ok"), toolCallId = "c1", name = "hub_request"),
        ChatMessage(role = "tool", content = MessageContent.text("Saved to the Hub's memory."), toolCallId = "c2"),
        msg("assistant", "Done. I noted your L40 preference."),
    )

    // ---- the conversation survives

    @Test fun `a conversation is saved and comes back, including after the app restarts`() {
        val dir = Files.createTempDirectory("agent").toFile()
        val first = AgentSession.load(dir)
        first.adopt(conversation)
        val again = AgentSession.load(dir)                                   // a new app start
        assertEquals(conversation.map { it.role to it.textContent() }, again.history.map { it.role to it.textContent() })
        assertEquals("put the 14B remix on a new L40", again.lines.first().body)
        assertEquals("Done. I noted your L40 preference.", again.lines.last().body)
        assertTrue(again.lines.any { it.role == ROLE_TOOL_CALL && it.title == "Tool · remember" })
    }

    @Test fun `new chat starts fresh but the old chat stays in the library`() {
        val dir = Files.createTempDirectory("agent").toFile()
        val s = AgentSession.load(dir); s.adopt(conversation)
        val first = s.currentId
        s.newChat()
        assertTrue(s.lines.isEmpty() && s.history.isEmpty()); assertTrue(s.currentId != first)
        assertEquals(listOf(first), s.list().map { it.id }, "the earlier chat is kept")
        assertEquals(first, AgentSession.load(dir).list().single().id)
        assertTrue(AgentSession.load(dir).history.isEmpty(), "and the fresh chat is what is open after a restart")
    }

    @Test fun `a damaged chat file is skipped instead of breaking the screen`() {
        val dir = Files.createTempDirectory("agent").toFile()
        java.io.File(dir, "chats").mkdirs(); java.io.File(dir, "chats/bad.json").writeText("{ this is not json")
        java.io.File(dir, "current_chat.txt").writeText("bad")
        val s = AgentSession.load(dir)
        assertTrue(s.history.isEmpty() && s.lines.isEmpty() && s.list().isEmpty())
        s.adopt(conversation); assertEquals(conversation.size, AgentSession.load(dir).history.size, "and saving works afterwards")
    }

    @Test fun `the library lists newest first, opens, deletes and names chats by what you said`() {
        val dir = Files.createTempDirectory("agent").toFile()
        val s = AgentSession.load(dir)
        s.adopt(listOf(msg("user", "first   chat" + 10.toChar() + "about   LoRAs"), msg("assistant", "ok")))
        val a = s.currentId; Thread.sleep(5); s.newChat()
        s.adopt(listOf(msg("user", "second chat about the L40 box"), msg("assistant", "noted the price")))
        val b = s.currentId
        assertEquals(listOf(b, a), s.list().map { it.id }); assertEquals("first chat about LoRAs", s.list().last().title)
        assertTrue(s.open(a)); assertEquals(a, s.currentId); assertTrue(s.lines.first().body.startsWith("first   chat"), "the bubble keeps what you typed")
        assertEquals(a, AgentSession.load(dir).currentId, "the open chat is remembered across restarts")
        assertFalse(s.open("nope"))
        s.delete(a); assertEquals(listOf(b), s.list().map { it.id }); assertTrue(s.currentId != a && s.lines.isEmpty(), "deleting the open chat starts a new one")
        assertEquals(60, AgentSession.titleOf(listOf(msg("user", "x".repeat(200)))).length); assertEquals("New chat", AgentSession.titleOf(emptyList()))
    }

    @Test fun `search finds a word inside any message of any chat, with a snippet`() {
        val dir = Files.createTempDirectory("agent").toFile()
        val s = AgentSession.load(dir)
        s.adopt(listOf(msg("user", "plan the render"), msg("assistant", "Thunder is cheaper than the L40 rental for this."))); Thread.sleep(5); s.newChat()
        s.adopt(listOf(msg("user", "unrelated"), msg("assistant", "nothing here")))
        val hits = s.search("l40")
        assertEquals(1, hits.size); assertTrue("L40 rental" in hits.single().snippet, hits.single().snippet)
        assertEquals(1, s.search("RENDER").size, "titles match too, ignoring case"); assertTrue(s.search("zzzz").isEmpty()); assertEquals(2, s.search("  ").size, "blank lists everything")
    }

    @Test fun `the single history file of earlier versions becomes the first chat`() {
        val dir = Files.createTempDirectory("agent").toFile()
        java.io.File(dir, "agent_history.json").writeText(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), conversation))
        val s = AgentSession.load(dir)
        assertEquals(conversation.size, s.history.size); assertEquals(1, s.list().size); assertFalse(java.io.File(dir, "agent_history.json").exists())
        assertEquals("put the 14B remix on a new L40", s.list().single().title)
        assertEquals(1, AgentSession.load(dir).list().size, "not imported twice")
    }

    @Test fun `only the recent messages are kept, and picture data is stripped`() {
        val many = (1..150).map { msg(if (it % 2 == 1) "user" else "assistant", "m$it") }
        val kept = AgentSession.lean(many + msg("system", "ignored"))
        assertEquals(Agent.MAX_HISTORY_MESSAGES, kept.size); assertEquals("m150", kept.last().textContent()); assertTrue(kept.none { it.role == "system" })
        val withImage = ChatMessage(role = "user", content = Json.parseToJsonElement("""[{"type":"text","text":"look"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AAAA"}}]"""))
        val lean = AgentSession.lean(listOf(withImage)).single().content.toString()
        assertFalse("AAAA" in lean, "image bytes are not written to disk"); assertTrue("look" in lean)
    }

    // ---- tool calls out of the way

    @Test fun `a run of tool calls and outputs becomes one quiet line`() {
        val items = groupChat(AgentSession.linesFrom(conversation))
        assertEquals(3, items.size)                                          // you, tools, venice
        val tools = items[1] as ChatItem.Tools
        assertEquals(4, tools.lines.size); assertEquals("Used 2 tools: hub_request, remember", tools.summary)
        assertEquals("Used a tool: hub_request ×2", ChatItem.Tools(listOf(ChatLine(ROLE_TOOL_CALL, "Tool · hub_request", ""), ChatLine(ROLE_TOOL_CALL, "Tool · hub_request", "")), 0).summary.replace("Used 2 tools", "Used a tool"))
        assertTrue(groupChat(emptyList()).isEmpty())
    }

    // ---- the hub's memory

    private val memJson = """{"preferences":{"video_length":"5 s","gpu":"L40"},"notes":[{"t":1790000000.0,"text":"remix14 on thunder works"},"a plain string note",{"text":"  "}],"projects":{},"updated":1}"""

    @Test fun `memory is read, folded into the system prompt like the web chat does, and told how to be added to`() {
        val m = parseHubMemory(Json.parseToJsonElement(memJson))!!
        assertEquals(mapOf("video_length" to "5 s", "gpu" to "L40"), m.preferences); assertEquals(listOf("remix14 on thunder works", "a plain string note"), m.notes.map { it.text })
        val pre = memoryPreamble(m)
        assertTrue(pre.startsWith("User preferences: {") && "\"gpu\":\"L40\"" in pre && "Recent notes: remix14 on thunder works | a plain string note" in pre)
        val prompt = systemPromptWithMemory("BASE", m)
        assertTrue(prompt.startsWith("BASE") && "[Persistent memory — use naturally, never quote raw to the user]" in prompt && "remember tool" in prompt)
        assertTrue("Persistent memory" !in systemPromptWithMemory("BASE", null) && "remember tool" in systemPromptWithMemory("BASE", null), "no memory block without memory, but it still knows how to save")
        assertTrue(HubMemory(emptyMap(), emptyList()).isEmpty); assertNull(parseHubMemory(Json.parseToJsonElement("[]")))
        assertEquals(5, memoryPreamble(HubMemory(emptyMap(), (1..9).map { HubNote(null, "n$it") })).split(" | ").size, "only the last five notes")
    }

    @Test fun `the memory client uses PUT to save and sends exactly what the hub merges`() = runBlocking {
        val seen = mutableListOf<Triple<HttpMethod, String, String>>()
        val engine = MockEngine { req -> seen += Triple(req.method, req.url.encodedPath, String(req.body.toByteArray())); respond(memJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val c = HubMemoryClient("https://hub.example", "u", "p", engine)
        assertEquals(2, (c.get() as MediaResult.Ok).value.preferences.size)
        c.addNote("  keep this  "); c.setPreference(" gpu ", " L40 "); c.replaceNotes(listOf(HubNote(1.0, "kept")))
        assertEquals(HttpMethod.Get, seen[0].first)
        assertTrue(seen.drop(1).all { it.first == HttpMethod.Put && it.second == "/api/agentic/memory" })
        assertEquals("keep this", ((Json.parseToJsonElement(seen[1].third) as JsonObject)["append_note"] as JsonPrimitive).content)
        assertEquals("L40", (((Json.parseToJsonElement(seen[2].third) as JsonObject)["preferences"] as JsonObject)["gpu"] as JsonPrimitive).content)
        assertTrue("\"text\":\"kept\"" in seen[3].third)
        assertTrue(c.addNote("  ") is MediaResult.Failure && c.setPreference("k", "") is MediaResult.Failure, "nothing is sent for an empty note or preference")
        assertEquals(4, seen.size)
    }

    @Test fun `the remember tool builds the body the hub merges`() {
        fun o(s: String?) = s?.let { Json.parseToJsonElement(it) as JsonObject }
        assertEquals("likes L40", (o(memoryPatch(" likes L40 ", null, null))!!["append_note"] as JsonPrimitive).content)
        assertEquals("5 s", ((o(memoryPatch(null, "video_length", "5 s"))!!["preferences"] as JsonObject)["video_length"] as JsonPrimitive).content)
        assertTrue(o(memoryPatch("n", "k", "v"))!!.keys == setOf("append_note", "preferences"))
        assertNull(memoryPatch(null, null, null)); assertNull(memoryPatch("  ", "key-without-value", null)); assertNull(memoryPatch(null, "", "v"))
        assertEquals(4000, ((o(memoryPatch("x".repeat(9000), null, null))!!["append_note"] as JsonPrimitive).content).length)
    }

    // ---- the real screen

    @OptIn(ExperimentalTestApi::class)
    @Test fun `the venice screen shows the saved chat, folds tools away, and opens the memory panel`() = runComposeUiTest {
        val session = AgentSession(null)
        session.lines.addAll(AgentSession.linesFrom(conversation))
        setContent {
            MaterialTheme(colorScheme = WhiteDevilColors) {
                AgentScreen(Settings(hubUrl = "http://127.0.0.1:9", veniceApiKey = ""), onOpenSettings = {}, session = session)
            }
        }
        waitForIdle()
        assertTrue(onAllNodesWithText("put the 14B remix on a new L40").fetchSemanticsNodes().isNotEmpty(), "the earlier conversation is on screen")
        assertTrue(onAllNodesWithText("TOOL · HUB_REQUEST").fetchSemanticsNodes().isEmpty(), "tool calls are not shown as bubbles")
        assertTrue(onAllNodesWithText("Used 2 tools", substring = true).fetchSemanticsNodes().isNotEmpty(), "one quiet summary line instead")
        onNodeWithText("SHOW TOOLS").performClick(); waitForIdle()
        assertTrue(onAllNodesWithText("TOOL · HUB_REQUEST").fetchSemanticsNodes().isNotEmpty(), "the toggle brings the details back")
        onNodeWithText("HIDE TOOLS").performClick()
        onNodeWithText("MEMORY").performClick()                              // opens the panel; the hub is unreachable, so it explains
        mainClock.advanceTimeBy(1_500)
        waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("Venice's memory").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("CLOSE").performClick(); waitForIdle()
        onNodeWithText("NEW CHAT").performClick(); waitForIdle()
        assertTrue(session.lines.isEmpty() && session.history.isEmpty(), "new chat clears it")
    }
}
