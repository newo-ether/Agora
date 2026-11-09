package com.newoether.agora.api.util.tokens

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Published request framing: Anthropic's tool-use system prompt and OpenAI's chat framing. */
class EnvelopeCostTest {

    private val systemPrompt = "You are a careful assistant."

    private fun tool(): ToolDefinition = ToolDefinition(
        function = ToolFunction(
            name = "shell",
            description = "Execute a command",
            parameters = ToolParameters(
                properties = linkedMapOf("command" to ToolProperty("string", "Command text")),
                required = listOf("command"),
            ),
        ),
    )

    private fun overheadFor(modelName: String): Int =
        AnthropicEnvelopeCosts.forModelName(modelName).toolSetOverhead

    @Test
    fun anthropicToolUseOverheadMatchesThePublishedTable() {
        // docs.claude.com tool-use overview, the "auto, none" column.
        assertEquals(286, overheadFor("claude-opus-5-5-20260401"))
        assertEquals(286, overheadFor("claude-opus-5-20260101"))
        assertEquals(290, overheadFor("claude-opus-4-8"))
        assertEquals(675, overheadFor("claude-opus-4-7"))
        assertEquals(497, overheadFor("claude-opus-4-6"))
        assertEquals(496, overheadFor("claude-opus-4-5"))
        assertEquals(313, overheadFor("claude-opus-4-1-20250805"))
        assertEquals(354, overheadFor("claude-sonnet-5-20260201"))
        assertEquals(497, overheadFor("claude-sonnet-4-6"))
        assertEquals(496, overheadFor("claude-sonnet-4-5-20250929"))
        assertEquals(313, overheadFor("claude-sonnet-4-20250514"))
        assertEquals(496, overheadFor("claude-haiku-4-5"))
        assertEquals(264, overheadFor("claude-3-5-haiku-20241022"))
    }

    @Test
    fun unrecognisedAnthropicIdsTakeTheLargestPublishedOverhead() {
        assertEquals(675, overheadFor("claude-latest"))
        assertEquals(675, overheadFor("claude-sonnet-9-9"))
        assertEquals(675, overheadFor("anthropic/claude-experimental"))
    }

    @Test
    fun anthropicKeepsTheSharedFramingItDoesNotPublish() {
        val envelope = AnthropicEnvelopeCosts.forModelName("claude-sonnet-4-5")
        assertEquals(EnvelopeCost.Default.perMessage, envelope.perMessage)
        assertEquals(EnvelopeCost.Default.perToolDefinition, envelope.perToolDefinition)
        assertEquals(EnvelopeCost.Default.perToolCall, envelope.perToolCall)
        assertEquals(0, envelope.requestOverhead)
    }

    @Test
    fun toolSetOverheadIsChargedOnlyWhenTheRequestCarriesTools() {
        val anthropic = CostModelContextEstimator(
            ContextCostModels.forModel("Anthropic:claude-sonnet-4-5"),
        )
        val withoutOverhead = CostModelContextEstimator(
            ContextCostModels.forModel("Anthropic:claude-sonnet-4-5").let {
                it.copy(envelope = it.envelope.copy(toolSetOverhead = 0))
            },
        )
        // No tools at all: the provider charges nothing extra, so both agree.
        assertEquals(
            withoutOverhead.estimateFixed(systemPrompt, emptyList()),
            anthropic.estimateFixed(systemPrompt, emptyList()),
        )
        // One tool: the whole documented preamble is charged exactly once.
        val tools = listOf(tool())
        assertTrue(
            anthropic.estimateFixed(systemPrompt, tools) -
                withoutOverhead.estimateFixed(systemPrompt, tools) >= 496,
        )
        // Two tools pay the same single preamble, never one per tool. The bound allows only the
        // safety margin's rounding, which is far below a second 496-token charge.
        val twoToolDelta = anthropic.estimateFixed(systemPrompt, tools + tool()) -
            withoutOverhead.estimateFixed(systemPrompt, tools + tool())
        assertTrue(twoToolDelta in 545..547)
        // A provider-native tool is still a tool.
        assertTrue(
            anthropic.estimateFixed(systemPrompt, emptyList(), codeExecutionEnabled = true) -
                withoutOverhead.estimateFixed(
                    systemPrompt,
                    emptyList(),
                    codeExecutionEnabled = true,
                ) >= 496,
        )
    }

    @Test
    fun toolSetOverheadLandsInTheToolPartOfTheComposition() {
        val anthropic = CostModelContextEstimator(
            ContextCostModels.forModel("Anthropic:claude-sonnet-4-5"),
        )
        val tools = listOf(tool())
        val withTools = anthropic.estimateFixedComposition(systemPrompt, tools)
        val withoutTools = anthropic.estimateFixedComposition(systemPrompt, emptyList())
        assertEquals(withoutTools.systemPromptTokens, withTools.systemPromptTokens)
        assertTrue(withTools.toolTokens - withoutTools.toolTokens >= 496)
    }

    @Test
    fun openAiFramingIsCheaperPerMessageThanTheSharedDefault() {
        val openAi = CostModelContextEstimator(ContextCostModels.forModel("OpenAI:gpt-5.1"))
        val generic = CostModelContextEstimator(ContextCostModels.forModel("My Gateway:mystery"))
        // Three tokens of framing per message plus three for reply priming, against a flat eight.
        assertTrue(
            openAi.estimateFixed(systemPrompt, emptyList()) <
                generic.estimateFixed(systemPrompt, emptyList()),
        )
        assertEquals(3, OpenAiEnvelopeCost.perMessage)
        assertEquals(3, OpenAiEnvelopeCost.requestOverhead)
        assertEquals(0, OpenAiEnvelopeCost.toolSetOverhead)
    }
}
