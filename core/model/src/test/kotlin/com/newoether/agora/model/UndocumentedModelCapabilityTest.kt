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
    fun gpt6OfficialThinkingChoicesAndOffBehaviorMatchRequests() {
        val astra = ModelThinkingCapabilities.forModel(ThinkingProviderFamily.OPENAI, "gpt-6-astra")
        assertFalse(astra.canDisableThinking)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), astra.supportedEfforts)
        assertEquals("low", astra.nearestEffort("minimal"))
        val forced = ThinkingResolution.resolve(astra, false, "max", false, 4096)
        assertTrue(forced.enabled)
        assertEquals("low", forced.effort)

        listOf("gpt-6-sol", "gpt-6-luna").forEach { id ->
            val capability = ModelThinkingCapabilities.forModel(ThinkingProviderFamily.OPENAI, id)
            assertTrue(capability.canDisableThinking)
            assertEquals(listOf("none", "low", "medium", "high", "xhigh", "max"),
                capability.supportedEfforts)
            assertEquals("low", capability.nearestEffort("minimal"))
        }
        assertEquals(ThinkingLevels.effortValues,
            ModelThinkingCapabilities.forModel(ThinkingProviderFamily.OPENAI, "future-gpt")
                .supportedEfforts)
        assertEquals(ThinkingLevels.effortValues,
            ModelThinkingCapabilities.forModel(ThinkingProviderFamily.OPENAI_COMPATIBLE, "gpt-6-astra")
                .supportedEfforts)
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
