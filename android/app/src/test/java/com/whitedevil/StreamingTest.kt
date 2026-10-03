package com.whitedevil

import com.whitedevil.agent.Agent
import com.whitedevil.agent.AgentEvent
import com.whitedevil.agent.StreamAssembler
import com.whitedevil.agent.ToolBox
import com.whitedevil.agent.VeniceClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class StreamingTest {

    @get:Rule
    val folder = TemporaryFolder()

    // Shapes copied from a real Venice stream (gemma-4-uncensored, 2026-10-03).
    private fun chunk(delta: String, finish: String? = null) =
        """data: {"id":"c1","object":"chat.completion.chunk","model":"m","choices":[{"index":0,"delta":$delta,"finish_reason":${if (finish == null) "null" else "\"$finish\""}}]}"""

    private val usageChunk = """data: {"id":"c1","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}"""

    @Test
    fun textDeltasAccumulateAndTheFinalResponseMatches() {
        val a = StreamAssembler()
        assertNull(a.feed(chunk("""{"role":"assistant","content":""}""")))            // role-only chunk adds nothing
        assertNull(a.feed(""))                                                         // blank separator line
        assertNull(a.feed(": keep-alive comment"))
        assertEquals("Hel", a.feed(chunk("""{"content":"Hel"}""")))
        assertEquals("Hello", a.feed(chunk("""{"content":"lo"}""")))
        assertNull(a.feed(chunk("""{"content":""}""", "stop")))
        assertNull(a.feed(usageChunk))                                                 // empty choices
        assertFalse(a.done)
        a.feed("data: [DONE]")
        assertTrue(a.done)
        val r = a.result()
        assertEquals("Hello", r.choices.single().message.textContent())
        assertEquals("stop", r.choices.single().finishReason)
        assertNull(r.choices.single().message.toolCalls)
        assertEquals(13, r.usage?.totalTokens)
        assertNull(a.error)
    }

    @Test
    fun toolCallFragmentsAreAssembledIntoOneCall() {
        val a = StreamAssembler()
        a.feed(chunk("""{"role":"assistant","content":""}"""))
        a.feed(chunk("""{"tool_calls":[{"index":0,"id":"call_9","type":"function","function":{"name":"list_directory","arguments":""}}]}"""))
        a.feed(chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\": "}}]}"""))
        a.feed(chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"\".\"}"}}]}"""))
        a.feed(chunk("""{"tool_calls":[{"index":1,"id":"call_10","function":{"name":"hub_overview","arguments":"{}"}}]}"""))
        a.feed(chunk("""{"content":""}""", "tool_calls"))
        a.feed("data: [DONE]")
        val msg = a.result().choices.single().message
        assertNull(msg.content)
        val calls = msg.toolCalls!!
        assertEquals(listOf("call_9", "call_10"), calls.map { it.id })
        assertEquals(listOf("list_directory", "hub_overview"), calls.map { it.function.name })
        assertEquals("{\"path\": \".\"}", calls[0].function.arguments)
        assertEquals("tool_calls", a.result().choices.single().finishReason)
    }

    @Test
    fun servingsThatOmitTheIndexStillGroupByIdAndErrorsAreReported() {
        val a = StreamAssembler()
        a.feed(chunk("""{"tool_calls":[{"id":"x1","function":{"name":"read_file","arguments":"{\"p"}}]}"""))
        a.feed(chunk("""{"tool_calls":[{"function":{"arguments":"ath\":\"a\"}"}}]}"""))     // no id, no index: continues the last call
        a.feed(chunk("""{"tool_calls":[{"id":"x2","function":{"name":"write_file","arguments":"{}"}}]}"""))
        val calls = a.result().choices.single().message.toolCalls!!
        assertEquals(listOf("x1", "x2"), calls.map { it.id })
        assertEquals("{\"path\":\"a\"}", calls[0].function.arguments)
        val e = StreamAssembler()
        assertNull(e.feed("""data: {"error":{"message":"overloaded"}}"""))
        assertTrue(e.error!!.contains("overloaded"))
        assertNull(StreamAssembler().feed("data: {not json"))                          // garbage is ignored, never thrown
    }

    /** One scripted SSE reply per request, in order. Records what the client sent. */
    private class FakeStream(private val replies: List<List<String>>) : AutoCloseable {
        val server = ServerSocket(0)
        val requests = CopyOnWriteArrayList<String>()
        val accepts = CopyOnWriteArrayList<String>()
        val auths = CopyOnWriteArrayList<String>()
        val baseUrl get() = "http://127.0.0.1:${server.localPort}/api/v1"

        init {
            thread(isDaemon = true) {
                var n = 0
                while (!server.isClosed && n < replies.size) {
                    try {
                        server.accept().use { s ->
                            val r = s.getInputStream().bufferedReader()
                            r.readLine()
                            var len = 0
                            while (true) {
                                val l = r.readLine() ?: return@use
                                if (l.isEmpty()) break
                                val low = l.lowercase()
                                if (low.startsWith("content-length:")) len = l.substringAfter(":").trim().toInt()
                                if (low.startsWith("accept:")) accepts += l.substringAfter(":").trim()
                                if (low.startsWith("authorization:")) auths += l.substringAfter(":").trim()
                            }
                            // Content-Length counts BYTES; tool descriptions contain multi-byte characters (em dashes).
                            val sb = StringBuilder(); val one = CharArray(256)
                            while (sb.toString().toByteArray(Charsets.UTF_8).size < len) {
                                val k = r.read(one, 0, one.size); if (k < 0) break; sb.append(one, 0, k)
                            }
                            requests += sb.toString()
                            val body = replies[n++].joinToString("\n\n") + "\n\n"
                            val out = s.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                            out.flush()
                            out.write(body.toByteArray())
                            out.flush()
                        }
                    } catch (e: Exception) { /* closed */ }
                }
            }
        }

        override fun close() = server.close()
    }

    @Test
    fun agentStreamsATextReplyAfterAToolTurnThroughTheRealClient() {
        val toolTurn = listOf(
            chunk("""{"role":"assistant","content":""}"""),
            chunk("""{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"list_directory","arguments":""}}]}"""),
            chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":\".\"}"}}]}"""),
            chunk("""{"content":""}""", "tool_calls"),
            "data: [DONE]",
        )
        val textTurn = listOf(
            chunk("""{"role":"assistant","content":""}"""),
            chunk("""{"content":"Nothing"}"""),
            chunk("""{"content":" is in"}"""),
            chunk("""{"content":" there."}""", null),
            chunk("""{"content":""}""", "stop"),
            usageChunk,
            "data: [DONE]",
        )
        FakeStream(listOf(toolTurn, textTurn)).use { srv ->
            val partials = CopyOnWriteArrayList<String>()
            val events = CopyOnWriteArrayList<String>()
            val box = ToolBox(folder.newFolder("ws"), "", "", "")
            val reply = runBlocking {
                VeniceClient("sekret", srv.baseUrl).use { client ->
                    Agent(
                        client = client, model = "m", toolBox = box, systemPrompt = "sys",
                        onEvent = { e -> events += e::class.simpleName.orEmpty() },
                        onPartial = { partials += it },
                    ).send("what is in the workspace?")
                }
            }
            assertEquals("Nothing is in there.", reply)
            assertEquals(listOf("User", "ToolCall", "ToolOutput", "Venice"), events.toList())
            // Each request starts the text over (""), and the second one grows chunk by chunk.
            assertEquals(listOf("", "", "Nothing", "Nothing is in", "Nothing is in there."), partials.toList())
            assertEquals(2, srv.requests.size)
            assertTrue(srv.requests.all { it.contains("\"stream\":true") })
            assertTrue(srv.accepts.all { it.contains("text/event-stream") })
            assertTrue(srv.auths.all { it == "Bearer sekret" })
            // The second request carries the assembled tool call and its result back to the model.
            assertTrue(srv.requests[1], srv.requests[1].contains("call_1") && srv.requests[1].contains("list_directory"))
        }
    }

    @Test
    fun withoutAnOnPartialCallbackTheAgentStillUsesTheNormalRequest() {
        // No server at all: the non-streaming path must be what runs, so it fails to connect (and reports it)
        // instead of silently streaming. Agent turns the failure into an error event, not an exception.
        val events = CopyOnWriteArrayList<String>()
        val reply = runBlocking {
            VeniceClient("k", "http://127.0.0.1:1/api/v1").use { c ->
                Agent(c, "m", ToolBox(folder.newFolder("ws2"), "", "", ""), "sys", onEvent = { events += it::class.simpleName.orEmpty() }).send("hi")
            }
        }
        assertTrue(reply, reply.startsWith("Error communicating with Venice"))
        assertTrue(events.contains("Error"))
    }
}
