package com.whitedevil.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QwenClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `sanitizeRequest removes venice parameters`() {
        val client = QwenClient(baseUrl = "http://127.0.0.1:18080")
        val req = ChatCompletionRequest(
            model = "qwen-agent",
            messages = listOf(ChatMessage(role = "user", content = MessageContent.text("Hello"))),
            veniceParameters = VeniceParameters(enableWebSearch = "on"),
        )
        val sanitized = client.sanitizeRequest(req)
        assertNull(sanitized.veniceParameters)
        assertEquals("qwen-agent", sanitized.model)
        client.close()
    }

    @Test
    fun `chatCompletion parses tool calls from response`() = runBlocking {
        val mockEngine = MockEngine { request ->
            assertEquals("/v1/chat/completions", request.url.encodedPath)
            assertEquals("Bearer secret_qwen_token", request.headers["Authorization"])
            val responseBody = """
                {
                    "id": "chatcmpl-123",
                    "model": "qwen-agent",
                    "choices": [
                        {
                            "index": 0,
                            "message": {
                                "role": "assistant",
                                "content": null,
                                "tool_calls": [
                                    {
                                        "id": "call_abc",
                                        "type": "function",
                                        "function": {
                                            "name": "read_file",
                                            "arguments": "{\"path\":\"sample.txt\"}"
                                        }
                                    }
                                ]
                            },
                            "finish_reason": "tool_calls"
                        }
                    ]
                }
            """.trimIndent()
            respond(
                content = responseBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val client = QwenClient(apiKey = "secret_qwen_token", baseUrl = "http://127.0.0.1:18080", client = http)
        val resp = client.chatCompletion(
            ChatCompletionRequest(
                model = "qwen-agent",
                messages = listOf(ChatMessage(role = "user", content = MessageContent.text("Read sample.txt"))),
            ),
        )

        assertEquals("chatcmpl-123", resp.id)
        val choice = resp.choices.first()
        val toolCalls = choice.message.toolCalls
        assertNotNull(toolCalls)
        assertEquals(1, toolCalls.size)
        assertEquals("read_file", toolCalls[0].function.name)
        assertEquals("{\"path\":\"sample.txt\"}", toolCalls[0].function.arguments)
        client.close()
    }

    @Test
    fun `chatCompletion throws QwenApiException on 401 unauthorized`() = runBlocking {
        val mockEngine = MockEngine {
            respond(
                content = """{"error": "Unauthorized key"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        val client = QwenClient(apiKey = "bad_token", baseUrl = "http://127.0.0.1:18080", client = http)

        val ex = assertFailsWith<QwenApiException> {
            client.chatCompletion(
                ChatCompletionRequest(
                    model = "qwen-agent",
                    messages = listOf(ChatMessage(role = "user", content = MessageContent.text("Hi"))),
                ),
            )
        }
        assertEquals(401, ex.status)
        assertTrue(ex.message?.contains("Unauthorized") == true)
        client.close()
    }

    @Test
    fun `chatCompletion throws QwenApiException on 503 model loading`() = runBlocking {
        val mockEngine = MockEngine {
            respond(
                content = """{"error": "Model upstream loading"}""",
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        val client = QwenClient(apiKey = "token", baseUrl = "http://127.0.0.1:18080", client = http)

        val ex = assertFailsWith<QwenApiException> {
            client.chatCompletion(
                ChatCompletionRequest(
                    model = "qwen-agent",
                    messages = listOf(ChatMessage(role = "user", content = MessageContent.text("Hi"))),
                ),
            )
        }
        assertEquals(503, ex.status)
        assertTrue(ex.message?.contains("Model upstream loading") == true)
        client.close()
    }

    @Test
    fun `chatCompletionStream delivers text and finishes with full response`() = runBlocking {
        val mockEngine = MockEngine {
            val sseData = """
                data: {"id":"1","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"}}]}

                data: {"id":"1","choices":[{"index":0,"delta":{"content":" world"}}]}

                data: [DONE]

            """.trimIndent()
            respond(
                content = sseData,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
            install(io.ktor.client.plugins.HttpTimeout)
        }
        val client = QwenClient(apiKey = "token", baseUrl = "http://127.0.0.1:18080", client = http)

        val partials = mutableListOf<String>()
        val resp = client.chatCompletionStream(
            ChatCompletionRequest(
                model = "qwen-agent",
                messages = listOf(ChatMessage(role = "user", content = MessageContent.text("Hi"))),
            ),
        ) { chunk ->
            partials.add(chunk)
        }

        assertEquals("Hello world", resp.choices.first().message.textContent())
        assertTrue(partials.isNotEmpty())
        client.close()
    }
}
