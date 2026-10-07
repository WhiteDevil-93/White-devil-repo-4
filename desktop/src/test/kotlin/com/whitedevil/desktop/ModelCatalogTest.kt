package com.whitedevil.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelCatalogTest {
    @Test
    fun `catalog exposes provider pricing and capability markers`() {
        val model = ModelCatalog.find("qwen/qwen3-vl-235b-a22b-instruct")!!
        assertEquals(ModelProvider.OPENROUTER, model.provider)
        assertEquals("\$0.21 in / \$1.90 out per 1M", ModelCatalog.priceLabel(model))
        assertTrue(ModelCatalog.capabilityLabel(model).contains("V vision / VL"))
        assertTrue(ModelCatalog.capabilityLabel(model).contains("F function calling / tools"))
    }

    @Test
    fun `unknown slash model routes to openrouter`() {
        assertEquals(ModelProvider.OPENROUTER, ModelCatalog.providerFor("openai/gpt-4o-mini"))
        assertEquals(ModelProvider.VENICE, ModelCatalog.providerFor("zai-org-glm-5"))
    }
}
