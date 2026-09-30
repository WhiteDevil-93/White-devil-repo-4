package com.whitedevil.veniceagent

import com.whitedevil.agent.ToolDefinition
import com.whitedevil.agent.ToolFunctionSpec

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun emptyObjectSchema(): JsonObject = buildJsonObject { put("type", "object") }

/** An in-memory [ToolProvider] for exercising [ToolRegistry] without real subprocesses or IO. */
private class FakeToolProvider(
    private val toolNames: List<String>,
    private var failNextDefinitionsCall: Boolean = false,
    private val closeShouldThrow: Boolean = false,
    private val hasChangedShouldThrow: Boolean = false,
) : ToolProvider {
    var definitionsCallCount = 0
        private set
    var closeCalled = false
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

    override fun hasChanged(): Boolean {
        if (hasChangedShouldThrow) throw RuntimeException("simulated hasChanged failure")
        return false
    }

    override fun close() {
        closeCalled = true
        if (closeShouldThrow) throw RuntimeException("simulated close failure")
    }
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
    fun `keeps disambiguated names within the chat-completion function-name limit`() = runBlocking {
        // A name already at the 64-char cap must still fit after a "_1" suffix is appended.
        val maxLengthName = "a".repeat(64)
        val providerA = FakeToolProvider(listOf(maxLengthName))
        val providerB = FakeToolProvider(listOf(maxLengthName))
        val registry = ToolRegistry(listOf(providerA, providerB))

        val names = registry.definitions().map { it.function.name }
        assertEquals(names.size, names.toSet().size, "exposed names must be unique: $names")
        names.forEach { assertTrue(it.length <= 64, "name exceeds the 64-char limit: $it") }
    }

    @Test
    fun `substitutes a fallback name for a provider's blank tool name`() = runBlocking {
        val provider = FakeToolProvider(listOf(""))
        val registry = ToolRegistry(listOf(provider))

        val names = registry.definitions().map { it.function.name }
        assertTrue(names.all { it.isNotBlank() }, "a blank exposed name would make Venice reject the whole request: $names")

        // Routing must still reach the provider using its own original (blank) name.
        assertEquals("executed:", registry.execute(names.single(), "{}"))
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

    @Test
    fun `normalizes names from any provider, not just ones that already namespace themselves`() = runBlocking {
        // Only McpStdioClient sanitizes its own names; a plain ToolProvider (e.g. a third-party
        // plugin) can hand back characters Venice's function-name schema rejects, or a name
        // longer than the 64-char cap, and the registry is the only place left to catch it.
        val provider = FakeToolProvider(listOf("weird.name with spaces", "a".repeat(100)))
        val registry = ToolRegistry(listOf(provider))

        val names = registry.definitions().map { it.function.name }
        assertTrue(names.all { Regex("^[a-zA-Z0-9_-]+$").matches(it) }, "unsanitized name leaked through: $names")
        assertTrue(names.all { it.length <= 64 }, "name exceeds the 64-char limit: $names")

        // Routing must still reach the provider using its own original (unsanitized) name.
        assertEquals("executed:weird.name with spaces", registry.execute(names[0], "{}"))
        assertEquals("executed:${"a".repeat(100)}", registry.execute(names[1], "{}"))
    }

    @Test
    fun `closes every provider even when one close throws`() {
        val failing = FakeToolProvider(listOf("a"), closeShouldThrow = true)
        val healthy = FakeToolProvider(listOf("b"))
        val registry = ToolRegistry(listOf(failing, healthy))

        registry.close()

        assertTrue(failing.closeCalled)
        assertTrue(healthy.closeCalled, "a later provider's close() must still run even if an earlier one throws")
    }

    @Test
    fun `treats a provider's throwing hasChanged as a signal to rebuild instead of aborting the cache check`() = runBlocking {
        val healthy = FakeToolProvider(listOf("healthy__tool"))
        val flaky = FakeToolProvider(listOf("flaky__tool"), hasChangedShouldThrow = true)
        val registry = ToolRegistry(listOf(healthy, flaky))

        registry.definitions() // establish an initial cache

        // flaky.hasChanged() throws on this call's cache check; it must not abort the whole
        // definitions() call for every provider, just force a rebuild.
        val names = registry.definitions().map { it.function.name }
        assertTrue("healthy__tool" in names, "a throwing hasChanged() on one provider took down the others: $names")
        assertTrue("flaky__tool" in names)
    }

    @Test
    fun `propagates cancellation instead of treating it as a failed provider`() = runBlocking {
        val cancelling = object : ToolProvider {
            override suspend fun definitions(): List<ToolDefinition> = throw CancellationException("test cancellation")
            override suspend fun execute(name: String, argumentsJson: String): String = "unused"
        }
        val registry = ToolRegistry(listOf(cancelling))

        assertFailsWith<CancellationException> { registry.definitions() }
        Unit // see the comment on the equivalent McpStdioClientTest case: assertFailsWith's
        // return value would otherwise leak out as this function's inferred return type.
    }
}
