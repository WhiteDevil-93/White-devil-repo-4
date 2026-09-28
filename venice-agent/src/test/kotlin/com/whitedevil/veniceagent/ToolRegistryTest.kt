package com.whitedevil.veniceagent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun emptyObjectSchema(): JsonObject = buildJsonObject { put("type", "object") }

/** An in-memory [ToolProvider] for exercising [ToolRegistry] without real subprocesses or IO. */
private class FakeToolProvider(
    private val toolNames: List<String>,
    private var failNextDefinitionsCall: Boolean = false,
) : ToolProvider {
    var definitionsCallCount = 0
        private set

    override suspend fun definitions(): List<ToolDefinition> {
        definitionsCallCount++
        if (failNextDefinitionsCall) {
            failNextDefinitionsCall = false
            throw RuntimeException("simulated transient failure")
        }
        return toolNames.map { name ->
            ToolDefinition(function = ToolFunctionSpec(name = name, description = "", parameters = emptyObjectSchema()))
        }
    }

    override suspend fun execute(name: String, argumentsJson: String): String = "executed:$name"
}

class ToolRegistryTest {

    @Test
    fun `disambiguates tool names that collide across providers and still routes correctly`() = runBlocking {
        // Simulates two different MCP servers whose namespaced tool names happen to collide,
        // e.g. server aliases "foo.bar" and "foo_bar" both sanitizing to "foo_bar".
        val providerA = FakeToolProvider(listOf("foo_bar__tool"))
        val providerB = FakeToolProvider(listOf("foo_bar__tool"))
        val registry = ToolRegistry(listOf(providerA, providerB))

        val names = registry.definitions().map { it.function.name }
        assertEquals(names.size, names.toSet().size, "exposed names must be unique: $names")
        assertTrue("foo_bar__tool" in names, "first provider keeps its natural name: $names")
        assertTrue("foo_bar__tool_1" in names, "second provider's collision gets a suffix: $names")

        // Each exposed (possibly disambiguated) name must route to the correct provider using
        // the name *that provider* actually knows, not the disambiguated one.
        assertEquals("executed:foo_bar__tool", registry.execute("foo_bar__tool", "{}"))
        assertEquals("executed:foo_bar__tool", registry.execute("foo_bar__tool_1", "{}"))
    }

    @Test
    fun `retries a provider that failed to list its tools instead of caching the gap`() = runBlocking {
        val healthy = FakeToolProvider(listOf("healthy__tool"))
        val flaky = FakeToolProvider(listOf("flaky__tool"), failNextDefinitionsCall = true)
        val registry = ToolRegistry(listOf(healthy, flaky))

        val firstPass = registry.definitions().map { it.function.name }
        assertTrue("flaky__tool" !in firstPass, "flaky provider should have been skipped: $firstPass")
        assertTrue("healthy__tool" in firstPass)

        val secondPass = registry.definitions().map { it.function.name }
        assertTrue("flaky__tool" in secondPass, "flaky provider should be retried on the next call: $secondPass")
        assertEquals(2, flaky.definitionsCallCount, "flaky provider must be re-queried, not served from a stale cache")
    }
}
