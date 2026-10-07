package com.whitedevil

enum class AgentProvider(val label: String) {
    VENICE("Venice"),
    OPENROUTER("OpenRouter"),
}

enum class AgentCapability(val symbol: String, val label: String) {
    TEXT("T", "text"),
    VISION("V", "vision / VL"),
    TOOLS("F", "function calling / tools"),
    WEB("W", "web search"),
    REASONING("R", "reasoning"),
    UNCENSORED("U", "uncensored"),
}

data class AgentModelInfo(
    val id: String,
    val name: String,
    val provider: AgentProvider,
    val inputUsdPerMillion: Double,
    val outputUsdPerMillion: Double,
    val capabilities: Set<AgentCapability>,
)

object AgentModelCatalog {
    val entries = listOf(
        AgentModelInfo("zai-org-glm-5-2", "GLM 5.2", AgentProvider.VENICE, 1.40, 4.40, setOf(AgentCapability.TEXT, AgentCapability.TOOLS, AgentCapability.WEB, AgentCapability.REASONING)),
        AgentModelInfo("zai-org-glm-5", "GLM 5", AgentProvider.VENICE, 1.00, 3.20, setOf(AgentCapability.TEXT, AgentCapability.TOOLS, AgentCapability.WEB, AgentCapability.REASONING)),
        AgentModelInfo("venice-uncensored-1-2", "Venice Uncensored 1.2", AgentProvider.VENICE, 0.20, 0.90, setOf(AgentCapability.TEXT, AgentCapability.VISION, AgentCapability.TOOLS, AgentCapability.WEB, AgentCapability.UNCENSORED)),
        AgentModelInfo("venice-uncensored-role-play", "Venice Role Play Uncensored", AgentProvider.VENICE, 0.50, 2.00, setOf(AgentCapability.TEXT, AgentCapability.VISION, AgentCapability.TOOLS, AgentCapability.WEB, AgentCapability.UNCENSORED)),
        AgentModelInfo("gemma-4-uncensored", "Gemma 4 Uncensored", AgentProvider.VENICE, 0.1625, 0.50, setOf(AgentCapability.TEXT, AgentCapability.VISION, AgentCapability.TOOLS, AgentCapability.WEB, AgentCapability.UNCENSORED)),
        AgentModelInfo("qwen3-vl-235b-a22b", "Qwen3 VL 235B", AgentProvider.VENICE, 0.21, 1.90, setOf(AgentCapability.TEXT, AgentCapability.VISION, AgentCapability.TOOLS, AgentCapability.WEB)),
        AgentModelInfo("mistral-small-3-2-24b-instruct", "Mistral Small 3.2 24B", AgentProvider.VENICE, 0.09375, 0.25, setOf(AgentCapability.TEXT, AgentCapability.TOOLS, AgentCapability.WEB)),
        AgentModelInfo("kimi-k2-6", "Kimi K2.6", AgentProvider.VENICE, 0.75, 3.50, setOf(AgentCapability.TEXT, AgentCapability.TOOLS, AgentCapability.WEB, AgentCapability.REASONING)),
        AgentModelInfo("claude-opus-4-8", "Claude Opus 4.8", AgentProvider.VENICE, 6.00, 30.00, setOf(AgentCapability.TEXT, AgentCapability.VISION, AgentCapability.TOOLS, AgentCapability.WEB, AgentCapability.REASONING)),
        AgentModelInfo("z-ai/glm-5.2", "GLM 5.2", AgentProvider.OPENROUTER, 0.28, 4.40, setOf(AgentCapability.TEXT, AgentCapability.TOOLS, AgentCapability.REASONING)),
        AgentModelInfo("qwen/qwen3-vl-235b-a22b-instruct", "Qwen3 VL 235B", AgentProvider.OPENROUTER, 0.21, 1.90, setOf(AgentCapability.TEXT, AgentCapability.VISION, AgentCapability.TOOLS)),
        AgentModelInfo("mistralai/mistral-small-3.2-24b-instruct", "Mistral Small 3.2 24B", AgentProvider.OPENROUTER, 0.09, 0.25, setOf(AgentCapability.TEXT, AgentCapability.TOOLS)),
        AgentModelInfo("moonshotai/kimi-k2.6", "Kimi K2.6", AgentProvider.OPENROUTER, 0.65, 3.41, setOf(AgentCapability.TEXT, AgentCapability.TOOLS, AgentCapability.REASONING)),
        AgentModelInfo("anthropic/claude-opus-4.8", "Claude Opus 4.8", AgentProvider.OPENROUTER, 5.00, 25.00, setOf(AgentCapability.TEXT, AgentCapability.VISION, AgentCapability.TOOLS, AgentCapability.REASONING)),
        AgentModelInfo("cognitivecomputations/dolphin-mistral-24b-venice-edition", "Dolphin Mistral Venice", AgentProvider.OPENROUTER, 0.20, 0.90, setOf(AgentCapability.TEXT, AgentCapability.UNCENSORED)),
    )

    fun find(id: String): AgentModelInfo? = entries.firstOrNull { it.id == id }

    fun providerFor(id: String): AgentProvider = find(id)?.provider
        ?: if ('/' in id) AgentProvider.OPENROUTER else AgentProvider.VENICE

    fun priceLabel(model: AgentModelInfo): String =
        "${money(model.inputUsdPerMillion)} in / ${money(model.outputUsdPerMillion)} out per 1M"

    fun capabilityLabel(model: AgentModelInfo): String =
        model.capabilities.joinToString("  ") { "${it.symbol} ${it.label}" }

    private fun money(value: Double): String =
        if (value < 0.1) "\$${"%.4f".format(java.util.Locale.US, value)}".trimEnd('0')
        else "\$${"%.2f".format(java.util.Locale.US, value)}"
}
