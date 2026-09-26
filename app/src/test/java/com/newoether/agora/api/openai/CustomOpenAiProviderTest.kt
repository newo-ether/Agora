package com.newoether.agora.api.openai

import com.newoether.agora.model.ModelThinkingCapabilities
import com.newoether.agora.model.ModelThinkingCapabilityOverride
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.model.ThinkingProviderFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomOpenAiProviderTest {
    @Test
    fun relayModelsKeepEveryEffortAndCanDisableThinking() {
        // A relay publishes no parameter documentation, so no model id may narrow its capability.
        listOf("qwen3.8-27b", "Qwen/QWEN3.8-27B", "deepseek-v4", "llama-3.3-70b").forEach { model ->
            val capability = ModelThinkingCapabilities.forModel(
                family = ThinkingProviderFamily.OPENAI_COMPATIBLE,
                modelId = model,
            )
            assertEquals(ThinkingLevels.effortValues, capability.supportedEfforts)
            assertTrue(capability.canDisableThinking)
            assertTrue(capability.supportsThinkingBudget)
            assertEquals("max", capability.nearestEffort("max"))
        }
    }

    @Test
    fun userOverrideNarrowsOneRelayModel() {
        val capability = ModelThinkingCapabilities.forModel(
            family = ThinkingProviderFamily.OPENAI_COMPATIBLE,
            modelId = "relay-model",
            override = ModelThinkingCapabilityOverride(
                canDisableThinking = false,
                supportedEfforts = listOf("high", "low"),
                supportsThinkingBudget = false,
            ),
        )
        assertEquals(listOf("low", "high"), capability.supportedEfforts)
        assertEquals("low", capability.lowestEffort)
        assertEquals(false, capability.canDisableThinking)
        assertEquals(false, capability.supportsThinkingBudget)
    }
}
