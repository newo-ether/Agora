package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndocumentedModelCapabilityTest {
    @Test
    fun undocumentedIdsOfferEveryOptionSoTheUserDecides() {
        listOf(
            ThinkingProviderFamily.ANTHROPIC to "claude-opus-5",
            ThinkingProviderFamily.ANTHROPIC to "claude-mythos-5",
            ThinkingProviderFamily.ANTHROPIC to "claude-fable-5",
            ThinkingProviderFamily.ANTHROPIC to "some-relay-model",
            ThinkingProviderFamily.QWEN to "minimax/minimax-m3",
            ThinkingProviderFamily.OPENAI_COMPATIBLE to "anything",
        ).forEach { (family, model) ->
            val capability = ModelThinkingCapabilities.forModel(family, model)
            assertEquals("$family/$model", ThinkingLevels.effortValues, capability.supportedEfforts)
            assertTrue("$family/$model", capability.canDisableThinking)
            assertTrue("$family/$model", capability.supportsThinkingBudget)
            assertTrue("$family/$model", capability.supportsSamplingParams)
        }
    }

    @Test
    fun undocumentedGroqIdsOfferEveryEffortButNoBudgetBecauseGroqHasNoBudgetField() {
        val capability = ModelThinkingCapabilities.forModel(ThinkingProviderFamily.GROQ, "unlisted/model")
        assertEquals(ThinkingLevels.effortValues, capability.supportedEfforts)
        assertTrue(capability.canDisableThinking)
        assertFalse(capability.supportsThinkingBudget)
    }

    @Test
    fun documentedIdsKeepTheirDocumentedShape() {
        val legacy = ModelThinkingCapabilities.forModel(ThinkingProviderFamily.ANTHROPIC, "claude-3-5-sonnet-20240620")
        assertTrue(legacy.supportedEfforts.isEmpty())
        assertFalse(legacy.supportsThinkingBudget)

        val glm = ModelThinkingCapabilities.forModel(ThinkingProviderFamily.QWEN, "glm-5.3")
        assertFalse(glm.canDisableThinking)
    }
}
