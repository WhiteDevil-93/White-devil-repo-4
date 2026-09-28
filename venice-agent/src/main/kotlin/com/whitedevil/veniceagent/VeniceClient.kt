package com.whitedevil.veniceagent

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
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

    suspend fun chatCompletion(request: ChatCompletionRequest): ChatCompletionResponse {
        val response = http.post("$baseUrl/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ChatCompletionRequest.serializer(), request))
        }

        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw VeniceApiException(response.status.value, "Venice API error (${response.status.value}): $bodyText")
        }
        return json.decodeFromString(ChatCompletionResponse.serializer(), bodyText)
    }

    override fun close() {
        http.close()
    }
}
