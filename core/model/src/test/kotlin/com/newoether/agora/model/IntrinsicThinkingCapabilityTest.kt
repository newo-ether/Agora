package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntrinsicThinkingCapabilityTest {
    private val sharedFamilies = listOf(ThinkingProviderFamily.OPENAI_COMPATIBLE,
        ThinkingProviderFamily.OPENROUTER, ThinkingProviderFamily.OPENAI,
        ThinkingProviderFamily.ANTHROPIC, ThinkingProviderFamily.GEMINI)

    @Test
    fun ownerMatrixAppliesAcrossCompatibleTransportsAndNormalizedIds() {
        val rows = listOf(
            Row("glm-5.3", false, "low high max"),
            Row("glm-5.3-flash", false, "low high max"),
            Row("glm-5.3-flashx", false, "low high max"),
            Row("glm-5.2", true, "high max"),
            Row("qwen3.8-27b", true, "low medium xhigh", true),
            Row("qwen3.8-flash-next", true, "low medium xhigh", true),
            Row("qwen3.8-flash", true, "low medium xhigh", true),
            Row("qwen3.8-max-preview", true, "low medium xhigh", true),
            Row("qwen3.8-omni-flash", true, "low medium xhigh", true),
            Row("qwen3.8-2.4t-a95b", false, "low medium xhigh", true),
            Row("deepseek-v4-flash", true, "low high max"),
            Row("deepseek-v4.1-pro", true, "low high max"),
            Row("kimi-k3", false, "low high max"),
            Row("kimi-k3.1-pro", false, "low high max"),
            Row("doubao-seed-2.1-pro", true, "low medium high"),
            Row("doubao-seed-evolving", true, "low medium high"),
            Row("minimax-m3.1-flash", false, "low medium high xhigh max"),
            Row("minimax-m3", true, ""),
            Row("gpt-6.1-sol", false, "low medium high xhigh max"),
            Row("claude-opus-5.5", false, "low medium high xhigh max", sampling = false),
            Row("claude-sonnet-5-5", false, "low medium high xhigh max", sampling = false),
            Row("claude-fable-5.1", false, "low medium high xhigh max", sampling = false),
            Row("claude-haiku-5.5", true, "low medium high xhigh max", sampling = false),
            Row("gemini-4-argon", false, "low medium high"),
            Row("gemini-3.8-flash", false, "low medium high"),
            Row("gemini-3.6-flash", false, "minimal low medium high"),
            Row("gemini-3.5-flash", false, "minimal low medium high"),
            Row("gemini-3.5-flash-lite", false, "minimal low medium high"),
            Row("grok-4.7", false, "low medium high xhigh"),
            Row("grok-4.6", false, "low medium high xhigh"),
            Row("grok-4.5", false, "low medium high"),
            Row("step-5-preview", true, "low medium high"),
            Row("step-3.7-flash", true, "low medium high"),
        )
        rows.forEach { row ->
            sharedFamilies.forEach { family ->
                listOf(row.id, "Provider/${row.id.uppercase()}-261026",
                    "provider/${row.id.replace('.', '-')}-2026-10-26:latest").forEach { id ->
                    val actual = ModelThinkingCapabilities.forModel(family, id)
                    val label = "$family/$id"
                    assertEquals(label, row.off, actual.canDisableThinking)
                    assertEquals(label, row.efforts.split(' ').filter(String::isNotEmpty), actual.supportedEfforts)
                    assertEquals(label, row.budget, actual.supportsThinkingBudget)
                    assertEquals(label, row.sampling, actual.supportsSamplingParams)
                    if (row.budget) assertFalse(label, actual.supportsEffortWithBudget)
                }
            }
        }
    }

    @Test
    fun arkDateIdsAndZaiNamespaceResolveToTheSameModelFacts() {
        listOf("glm-5-3-flash-260828", "z-ai/glm-5.3").forEach { id ->
            val cap = capability(id)
            assertFalse(cap.canDisableThinking)
            assertEquals(listOf("low", "high", "max"), cap.supportedEfforts)
            assertFalse(cap.supportsThinkingBudget)
        }
        assertTrue(capability("glm-5-2-260617").canDisableThinking)
        assertEquals(listOf("high", "max"), capability("glm-5-2-260617").supportedEfforts)
        assertEquals(listOf("low", "medium", "high"),
            capability("doubao-seed-2-1-pro-260915").supportedEfforts)
        assertEquals(listOf("low", "high", "max"),
            capability("deepseek-v4-1-flash-261017").supportedEfforts)
        assertFalse(capability("kimi-k3-1-pro-261024").canDisableThinking)
    }

    @Test
    fun qwenEndpointRetainsItsDistinctExistingOffPolicy() {
        listOf("kimi-k3", "qwen3.8-2.4t-a95b").forEach { id ->
            assertTrue(ModelThinkingCapabilities.forModel(ThinkingProviderFamily.QWEN, id).canDisableThinking)
            assertFalse(capability(id).canDisableThinking)
        }
    }

    @Test
    fun unknownVariantsKeepThePermissiveFallback() {
        listOf("anything", "glm-5.3-future", "glm-5.4", "kimi-k3.2",
            "claude-opus-5.50", "gemini-3.8-flash-experimental", "gpt-6.2-sol").forEach { id ->
            assertEquals(id, ModelThinkingCapability.Permissive, capability(id))
        }
    }

    @Test
    fun savedInvalidEffortsClampWithoutMutatingTheSettingOrRejectingOff() {
        val glm = capability("z-ai/glm-5.3")
        assertEquals("high", resolve(glm, true, "medium").effort)
        assertEquals("max", resolve(glm, true, "xhigh").effort)
        assertEquals("high", resolve(glm, true, "corrupt-saved-value").effort)
        assertEquals("low", resolve(glm, false, "max").effort)
        assertTrue(resolve(glm, false, "max").enabled)
        assertFalse(resolve(capability("glm-5.2"), false, "max").enabled)
        val qwen = capability("Qwen/Qwen3.8-27B")
        val budgeted = ThinkingResolution.resolve(qwen, true, "max", true, 8192)
        assertEquals(8192, budgeted.budgetTokens)
        assertNull(budgeted.effort)
        assertEquals("xhigh", resolve(qwen, true, "max").effort)
    }

    private fun capability(id: String) = ModelThinkingCapabilities.forModel(
        ThinkingProviderFamily.OPENAI_COMPATIBLE, id)
    private fun resolve(cap: ModelThinkingCapability, on: Boolean, effort: String) =
        ThinkingResolution.resolve(cap, on, effort, false, 4096)
    private data class Row(val id: String, val off: Boolean, val efforts: String,
        val budget: Boolean = false, val sampling: Boolean = true)
}
