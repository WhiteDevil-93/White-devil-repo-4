package com.whitedevil.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readUTF8Line
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class VeniceApiException(val status: Int, message: String) : Exception(message)

class VeniceClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.venice.ai/api/v1",
) : AutoCloseable {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    /**
     * The exact request body [chatCompletion] sends. Public so a test can assert on the
     * real bytes: this encoder runs with encodeDefaults = false, which drops any field
     * equal to its default, and one of those (tool_calls[].type) turned out to be
     * required by the API.
     */
    fun encodeRequest(request: ChatCompletionRequest): String =
        json.encodeToString(ChatCompletionRequest.serializer(), request)

    suspend fun chatCompletion(request: ChatCompletionRequest): ChatCompletionResponse {
        val cleanBase = baseUrl.trimEnd('/')
        val response = http.post("$cleanBase/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(encodeRequest(request))
        }

        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw VeniceApiException(response.status.value, "Venice API error (${response.status.value}): $bodyText")
        }
        return json.decodeFromString(ChatCompletionResponse.serializer(), bodyText)
    }

    /**
     * Same request as [chatCompletion] but streamed. [onText] receives the reply text accumulated so far
     * each time it grows (so a retry that starts over simply replaces it). Tool calls are assembled
     * from their fragments and come back in the returned response exactly like the non-streaming call.
     */
    suspend fun chatCompletionStream(request: ChatCompletionRequest, onText: (String) -> Unit): ChatCompletionResponse {
        val cleanBase = baseUrl.trimEnd('/')
        val asm = StreamAssembler(json)
        http.preparePost("$cleanBase/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            header("Accept", "text/event-stream")
            contentType(ContentType.Application.Json)
            timeout { requestTimeoutMillis = 300_000; socketTimeoutMillis = 120_000 }
            setBody(json.encodeToString(ChatCompletionRequest.serializer(), request.copy(stream = true)))
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw VeniceApiException(response.status.value, "Venice API error (${response.status.value}): ${response.bodyAsText()}")
            }
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                asm.feed(line)?.let(onText)
                if (asm.done) break
            }
        }
        asm.error?.let { throw VeniceApiException(502, "Venice stream error: $it") }
        if (!asm.hasContent() && !asm.done) throw java.io.IOException("The stream ended without a reply")
        return asm.result()
    }

    override fun close() {
        http.close()
    }
}
