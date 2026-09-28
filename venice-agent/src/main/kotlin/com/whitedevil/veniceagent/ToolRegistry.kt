package com.whitedevil.veniceagent

/**
 * Aggregates one or more [ToolProvider]s into a single tool namespace for [Agent].
 * Tool names must be unique across all providers; providers are responsible for
 * namespacing their own tool names to avoid collisions (see MCP's `server__tool` scheme).
 */
class ToolRegistry(private val providers: List<ToolProvider>) : AutoCloseable {

    private var routes: Map<String, ToolProvider>? = null
    private var cachedDefinitions: List<ToolDefinition>? = null

    suspend fun definitions(): List<ToolDefinition> {
        cachedDefinitions?.let { return it }

        val routeMap = LinkedHashMap<String, ToolProvider>()
        val allDefinitions = mutableListOf<ToolDefinition>()
        for (provider in providers) {
            val providerDefinitions = try {
                provider.definitions()
            } catch (e: Exception) {
                System.err.println("Warning: a tool provider failed to list its tools and was skipped: ${e.message}")
                continue
            }
            for (definition in providerDefinitions) {
                val name = definition.function.name
                val previousOwner = routeMap.putIfAbsent(name, provider)
                if (previousOwner != null) {
                    throw IllegalStateException("duplicate tool name '$name' registered by multiple providers")
                }
                allDefinitions.add(definition)
            }
        }
        routes = routeMap
        cachedDefinitions = allDefinitions
        return allDefinitions
    }

    suspend fun execute(name: String, argumentsJson: String): String {
        val provider = routes?.get(name) ?: return "Error: unknown tool '$name'."
        return provider.execute(name, argumentsJson)
    }

    override fun close() {
        providers.forEach { it.close() }
    }
}
