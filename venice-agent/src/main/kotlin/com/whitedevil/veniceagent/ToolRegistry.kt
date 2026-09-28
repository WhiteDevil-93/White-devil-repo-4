package com.whitedevil.veniceagent

/** Chat-completion APIs (OpenAI-compatible, including Venice) cap function names at 64 chars. */
private const val MAX_TOOL_NAME_LENGTH = 64

/**
 * Aggregates one or more [ToolProvider]s into a single tool namespace for [Agent].
 * Providers are expected to namespace their own tool names to avoid collisions (see MCP's
 * `server__tool` scheme), but this is the final authority on uniqueness: a name that still
 * collides across providers is exposed to the model under a disambiguated name, while calls
 * are routed back to the name the owning provider actually knows.
 */
class ToolRegistry(private val providers: List<ToolProvider>) : AutoCloseable {

    private data class Route(val provider: ToolProvider, val providerToolName: String)

    private var routes: Map<String, Route>? = null
    private var cachedDefinitions: List<ToolDefinition>? = null

    suspend fun definitions(): List<ToolDefinition> {
        if (cachedDefinitions != null && providers.none { it.hasChanged() }) {
            return cachedDefinitions!!
        }

        val routeMap = LinkedHashMap<String, Route>()
        val allDefinitions = mutableListOf<ToolDefinition>()
        var anyProviderSkipped = false
        for (provider in providers) {
            val providerDefinitions = try {
                provider.definitions()
            } catch (e: Exception) {
                System.err.println("Warning: a tool provider failed to list its tools and was skipped: ${e.message}")
                anyProviderSkipped = true
                continue
            }
            for (definition in providerDefinitions) {
                val providerToolName = definition.function.name
                val exposedName = disambiguate(providerToolName, routeMap.keys)
                routeMap[exposedName] = Route(provider, providerToolName)
                allDefinitions.add(
                    if (exposedName == providerToolName) {
                        definition
                    } else {
                        definition.copy(function = definition.function.copy(name = exposedName))
                    },
                )
            }
        }
        routes = routeMap
        // Don't cache a build that skipped a failing provider: keep it dirty so the next call
        // retries that provider instead of permanently losing its tools until a restart.
        cachedDefinitions = if (anyProviderSkipped) null else allDefinitions
        return allDefinitions
    }

    private fun disambiguate(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        System.err.println("Warning: duplicate tool name '$name' from multiple providers; disambiguating it.")
        var suffix = 1
        var candidate: String
        do {
            val suffixText = "_$suffix"
            candidate = name.take((MAX_TOOL_NAME_LENGTH - suffixText.length).coerceAtLeast(0)) + suffixText
            suffix++
        } while (candidate in taken)
        return candidate
    }

    suspend fun execute(name: String, argumentsJson: String): String {
        val route = routes?.get(name) ?: return "Error: unknown tool '$name'."
        return route.provider.execute(route.providerToolName, argumentsJson)
    }

    override fun close() {
        providers.forEach { it.close() }
    }
}
