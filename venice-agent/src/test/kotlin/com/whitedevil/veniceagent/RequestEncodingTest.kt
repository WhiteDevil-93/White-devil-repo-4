package com.whitedevil.veniceagent

import com.whitedevil.agent.ChatCompletionRequest
import com.whitedevil.agent.ChatMessage
import com.whitedevil.agent.ToolCall
import com.whitedevil.agent.ToolCallFunction
import com.whitedevil.agent.ToolDefinition
import com.whitedevil.agent.ToolFunctionSpec
import com.whitedevil.agent.VeniceClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the bytes the client really sends. VeniceClient encodes with encodeDefaults = false, which
 * omits any field equal to its default, and `type = "function"` was one of them: every assistant
 * tool call replayed to Venice went out without a `type`, and GLM answered each follow-up with
 * 500 {"error":"Inference processing failed"}. A turn that used any tool therefore failed, and the
 * history it left behind kept the next messages failing too.
 */
class RequestEncodingTest {

    private fun encode(request: ChatCompletionRequest): JsonObject =
        VeniceClient(apiKey = "test-key").use {
            Json.parseToJsonElement(it.encodeRequest(request)).jsonObject
        }

    private fun requestWithToolRoundTrip(arguments: String) = ChatCompletionRequest(
        model = "zai-org-glm-5-2",
        messages = listOf(
            ChatMessage(role = "user", content = JsonPrimitive("check the hub")),
            ChatMessage(
                role = "assistant",
                toolCalls = listOf(
                    ToolCall(id = "call_1", function = ToolCallFunction(name = "hub_overview", arguments = arguments)),
                ),
            ),
            ChatMessage(role = "tool", content = JsonPrimitive("{}"), toolCallId = "call_1", name = "hub_overview"),
        ),
        tools = listOf(
            ToolDefinition(
                function = ToolFunctionSpec(
                    name = "hub_overview",
                    description = "Overview of the hub.",
                    parameters = buildJsonObject {},
                ),
            ),
        ),
    )

    @Test
    fun `a replayed assistant tool call still carries type function`() {
        val call = encode(requestWithToolRoundTrip("{}"))
            .getValue("messages").jsonArray[1].jsonObject
            .getValue("tool_calls").jsonArray[0].jsonObject
        assertEquals("function", call.getValue("type").jsonPrimitive.content, "tool_calls[].type was dropped: $call")
        assertEquals("call_1", call.getValue("id").jsonPrimitive.content)
        assertEquals("hub_overview", call.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `an empty arguments string is still sent for a tool that takes none`() {
        val fn = encode(requestWithToolRoundTrip(""))
            .getValue("messages").jsonArray[1].jsonObject
            .getValue("tool_calls").jsonArray[0].jsonObject
            .getValue("function").jsonObject
        assertEquals("", fn.getValue("arguments").jsonPrimitive.content)
    }

    @Test
    fun `tool definitions carry type function`() {
        val tool = encode(requestWithToolRoundTrip("{}")).getValue("tools").jsonArray[0].jsonObject
        assertEquals("function", tool.getValue("type").jsonPrimitive.content, "tools[].type was dropped: $tool")
    }

    @Test
    fun `the tool result is linked back to its call`() {
        val result = encode(requestWithToolRoundTrip("{}")).getValue("messages").jsonArray[2].jsonObject
        assertEquals("tool", result.getValue("role").jsonPrimitive.content)
        assertEquals("call_1", result.getValue("tool_call_id").jsonPrimitive.content)
    }
}
