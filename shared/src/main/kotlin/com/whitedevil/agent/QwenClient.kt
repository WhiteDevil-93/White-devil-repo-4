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
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.json.Json

class QwenApiException(val status: Int, message: String) : Exception(message)

/**
 * OpenAI-compatible inference client for Qwen model servers / gateways.
 *
 * Exposes both streaming and synchronous completions. Tool calls returned by the model
 * are passed back in [ChatCompletionResponse] without executing them here; execution
 * and sandbox permissions belong entirely to WhiteDevil's [ToolBox].
 */
class QwenClient(
    private val apiKey: String = "",
    private val baseUrl: String = "http://127.0.0.1:18080",
    client: HttpClient? = null,
) : InferenceClient {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val ownsHttp = client == null
    private val http: HttpClient = client ?: HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    private fun resolveEndpoint(path: String): String {
        val clean = baseUrl.trimEnd('/')
        return if (clean.endsWith("/v1")) "$clean$path" else "$clean/v1$path"
    }

    /** Prepares request by stripping provider-specific vendor parameters (e.g. venice_parameters). */
    fun sanitizeRequest(request: ChatCompletionRequest): ChatCompletionRequest {
        return if (request.veniceParameters != null) request.copy(veniceParameters = null) else request
    }

    fun encodeRequest(request: ChatCompletionRequest): String =
        json.encodeToString(ChatCompletionRequest.serializer(), sanitizeRequest(request))

    override suspend fun chatCompletion(request: ChatCompletionRequest): ChatCompletionResponse {
        val url = resolveEndpoint("/chat/completions")
        val response = http.post(url) {
            if (apiKey.isNotBlank()) {
                header("Authorization", "Bearer $apiKey")
            }
            contentType(ContentType.Application.Json)
            setBody(encodeRequest(request))
        }

        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw QwenApiException(response.status.value, "Qwen API error (${response.status.value}): $bodyText")
        }
        return json.decodeFromString(ChatCompletionResponse.serializer(), bodyText)
    }

    override suspend fun chatCompletionStream(
        request: ChatCompletionRequest,
        onText: (String) -> Unit,
    ): ChatCompletionResponse {
        val url = resolveEndpoint("/chat/completions")
        val asm = StreamAssembler(json)
        http.preparePost(url) {
            if (apiKey.isNotBlank()) {
                header("Authorization", "Bearer $apiKey")
            }
            header("Accept", "text/event-stream")
            contentType(ContentType.Application.Json)
            timeout { requestTimeoutMillis = 300_000; socketTimeoutMillis = 120_000 }
            setBody(encodeRequest(request.copy(stream = true)))
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw QwenApiException(
                    response.status.value,
                    "Qwen API error (${response.status.value}): ${response.bodyAsText()}",
                )
            }
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                asm.feed(line)?.let(onText)
                if (asm.done) break
            }
        }
        asm.error?.let { throw QwenApiException(502, "Qwen stream error: $it") }
        if (!asm.hasContent() && !asm.done) throw java.io.IOException("The stream ended without a reply")
        return asm.result()
    }

    override fun close() {
        if (ownsHttp) {
            http.close()
        }
    }
}
