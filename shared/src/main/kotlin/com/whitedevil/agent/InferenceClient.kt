package com.whitedevil.agent

/**
 * Common abstraction for OpenAI-compatible chat completion providers (Venice, Qwen, etc.).
 *
 * Both streaming and non-streaming modes resolve tool calls into a [ChatCompletionResponse]
 * that WhiteDevil's agent loop evaluates and executes locally.
 */
interface InferenceClient : AutoCloseable {
    suspend fun chatCompletion(request: ChatCompletionRequest): ChatCompletionResponse
    suspend fun chatCompletionStream(request: ChatCompletionRequest, onText: (String) -> Unit): ChatCompletionResponse
}
