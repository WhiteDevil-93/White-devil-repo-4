package com.whitedevil.desktop

import java.util.Locale

/** The small curated catalog shown in Settings. Prices are USD per 1M tokens. */
enum class ModelProvider(val label: String) {
    VENICE("Venice"),
    OPENROUTER("OpenRouter"),
}

enum class ModelCapability(val symbol: String, val label: String) {
    TEXT("T", "text"),
    VISION("V", "vision / VL"),
    TOOLS("F", "function calling / tools"),
    WEB("W", "web search"),
    REASONING("R", "reasoning"),
    UNCENSORED("U", "uncensored"),
}

data class ModelInfo(
    val id: String,
    val name: String,
    val provider: ModelProvider,
    val inputUsdPerMillion: Double,
    val outputUsdPerMillion: Double,
    val capabilities: Set<ModelCapability>,
)

object ModelCatalog {
    // Keep these entries aligned with the provider catalogs used by the hub. The
    // custom model field still permits an ID not listed here.
    val entries: List<ModelInfo> = listOf(
        ModelInfo("zai-org-glm-5-2", "GLM 5.2", ModelProvider.VENICE, 1.40, 4.40, setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.WEB, ModelCapability.REASONING)),
        ModelInfo("zai-org-glm-5", "GLM 5", ModelProvider.VENICE, 1.00, 3.20, setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.WEB, ModelCapability.REASONING)),
        ModelInfo("venice-uncensored-1-2", "Venice Uncensored 1.2", ModelProvider.VENICE, 0.20, 0.90, setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.WEB, ModelCapability.UNCENSORED)),
        ModelInfo("venice-uncensored-role-play", "Venice Role Play Uncensored", ModelProvider.VENICE, 0.50, 2.00, setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.WEB, ModelCapability.UNCENSORED)),
        ModelInfo("gemma-4-uncensored", "Gemma 4 Uncensored", ModelProvider.VENICE, 0.1625, 0.50, setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.WEB, ModelCapability.UNCENSORED)),
        ModelInfo("qwen3-vl-235b-a22b", "Qwen3 VL 235B", ModelProvider.VENICE, 0.21, 1.90, setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.WEB)),
        ModelInfo("mistral-small-3-2-24b-instruct", "Mistral Small 3.2 24B", ModelProvider.VENICE, 0.09375, 0.25, setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.WEB)),
        ModelInfo("kimi-k2-6", "Kimi K2.6", ModelProvider.VENICE, 0.75, 3.50, setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.WEB, ModelCapability.REASONING)),
        ModelInfo("claude-opus-4-8", "Claude Opus 4.8", ModelProvider.VENICE, 6.00, 30.00, setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.WEB, ModelCapability.REASONING)),
        ModelInfo("z-ai/glm-5.2", "GLM 5.2", ModelProvider.OPENROUTER, 0.28, 4.40, setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.REASONING)),
        ModelInfo("qwen/qwen3-vl-235b-a22b-instruct", "Qwen3 VL 235B", ModelProvider.OPENROUTER, 0.21, 1.90, setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS)),
        ModelInfo("mistralai/mistral-small-3.2-24b-instruct", "Mistral Small 3.2 24B", ModelProvider.OPENROUTER, 0.09, 0.25, setOf(ModelCapability.TEXT, ModelCapability.TOOLS)),
        ModelInfo("moonshotai/kimi-k2.6", "Kimi K2.6", ModelProvider.OPENROUTER, 0.65, 3.41, setOf(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.REASONING)),
        ModelInfo("anthropic/claude-opus-4.8", "Claude Opus 4.8", ModelProvider.OPENROUTER, 5.00, 25.00, setOf(ModelCapability.TEXT, ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.REASONING)),
        ModelInfo("cognitivecomputations/dolphin-mistral-24b-venice-edition", "Dolphin Mistral Venice", ModelProvider.OPENROUTER, 0.20, 0.90, setOf(ModelCapability.TEXT, ModelCapability.UNCENSORED)),
    )

    fun find(id: String): ModelInfo? = entries.firstOrNull { it.id == id }

    fun providerFor(id: String): ModelProvider = find(id)?.provider
        ?: if ('/' in id) ModelProvider.OPENROUTER else ModelProvider.VENICE

    fun priceLabel(model: ModelInfo): String =
        "${money(model.inputUsdPerMillion)} in / ${money(model.outputUsdPerMillion)} out per 1M"

    fun capabilityLabel(model: ModelInfo): String =
        model.capabilities.joinToString("  ") { "${it.symbol} ${it.label}" }

    private fun money(value: Double): String =
        if (value < 0.1) String.format(Locale.US, "\$%.4f", value).trimEnd('0')
        else String.format(Locale.US, "\$%.2f", value)
}
