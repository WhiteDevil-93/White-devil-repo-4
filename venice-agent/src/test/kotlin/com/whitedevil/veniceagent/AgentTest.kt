package com.whitedevil.veniceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A [VeniceApi] test double that always answers with the same canned response. */
private class FakeVeniceApi(private val response: ChatCompletionResponse) : VeniceApi {
    override suspend fun chatCompletion(request: ChatCompletionRequest): ChatCompletionResponse = response
}

/** A [ToolProvider] whose single tool never completes on its own, so it can only end via cancellation. */
private class HangingToolProvider : ToolProvider {
    override suspend fun definitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunctionSpec(
                name = "hang",
                description = "Never returns.",
                parameters = buildJsonObject { put("type", "object") },
            ),
        ),
    )

    override suspend fun execute(name: String, argumentsJson: String): String = awaitCancellation()
}

class AgentTest {

    @Test
    fun `answers every outstanding tool call before propagating cancellation`() = runBlocking {
        // The assistant's one response carries two tool_calls; execution hangs on the first, so
        // the second is never even started before cancellation arrives.
        val toolCalls = listOf(
            ToolCall(id = "call_1", function = ToolCallFunction(name = "hang", arguments = "{}")),
            ToolCall(id = "call_2", function = ToolCallFunction(name = "hang", arguments = "{}")),
        )
        val assistantMessage = ChatMessage(role = "assistant", toolCalls = toolCalls)
        val response = ChatCompletionResponse(choices = listOf(ChatChoice(message = assistantMessage)))

        val agent = Agent(
            client = FakeVeniceApi(response),
            model = "test-model",
            tools = ToolRegistry(listOf(HangingToolProvider())),
            systemPrompt = "system",
            enableWebSearch = false,
        )

        var caughtCancellation = false
        val job = launch {
            try {
                agent.send("do something")
            } catch (e: CancellationException) {
                caughtCancellation = true
                throw e
            }
        }
        delay(200) // let send() reach and start hanging on the first tool call
        job.cancelAndJoin()

        assertTrue(caughtCancellation, "cancellation must propagate out of send()")

        // The assistant message carrying both tool_calls is in history; a chat-completion API
        // rejects a follow-up request unless every one of those tool_call_ids has a matching
        // tool response. Both must be answered, not just the one that was actually executing.
        val toolResultIds = agent.historySnapshot().filter { it.role == "tool" }.map { it.toolCallId }.toSet()
        assertEquals(setOf("call_1", "call_2"), toolResultIds, "every tool_call must be answered, even one cancellation abandoned")
    }
}
