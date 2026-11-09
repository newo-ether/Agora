package com.newoether.agora.api.util.tokens

/**
 * Anthropic charges a documented tool-use system prompt whenever a request carries tools.
 *
 * From the tool-use overview (docs.claude.com, read 2026-09-27), the `auto, none` column, which is
 * the tool choice Agora sends. The values are per model and not monotonic, so they are a table, not
 * a formula. The same page states that a request with no tools pays nothing extra, which is why this
 * is a tool-set overhead rather than a per-request one.
 *
 * Per-message and per-tool framing is not published for Anthropic, so those keep the shared default.
 */
object AnthropicEnvelopeCosts {
    /**
     * Tool-use system prompt tokens by tier and version. An unrecognised id takes the largest
     * published value, because over-estimating the window is the safe direction.
     */
    private val TOOL_USE_SYSTEM_PROMPT: Map<Pair<AnthropicModelId.Tier, Int>, Int> = mapOf(
        // Key is the version scaled by ten, so 4.5 is 45 and 5 is 50.
        (AnthropicModelId.Tier.OPUS to 55) to 286,
        (AnthropicModelId.Tier.OPUS to 50) to 286,
        (AnthropicModelId.Tier.OPUS to 48) to 290,
        (AnthropicModelId.Tier.OPUS to 47) to 675,
        (AnthropicModelId.Tier.OPUS to 46) to 497,
        (AnthropicModelId.Tier.OPUS to 45) to 496,
        (AnthropicModelId.Tier.OPUS to 41) to 313,
        (AnthropicModelId.Tier.OPUS to 40) to 313,
        (AnthropicModelId.Tier.SONNET to 50) to 354,
        (AnthropicModelId.Tier.SONNET to 46) to 497,
        (AnthropicModelId.Tier.SONNET to 45) to 496,
        (AnthropicModelId.Tier.SONNET to 40) to 313,
        (AnthropicModelId.Tier.HAIKU to 45) to 496,
        (AnthropicModelId.Tier.HAIKU to 35) to 264,
    )

    private val LARGEST_PUBLISHED = TOOL_USE_SYSTEM_PROMPT.values.max()

    fun forModelName(modelName: String): EnvelopeCost {
        val model = AnthropicModelId.parse(modelName)
        val tier = model.tier
        val major = model.major
        val overhead = if (tier != null && major != null) {
            TOOL_USE_SYSTEM_PROMPT[tier to (major * 10 + model.minor)] ?: LARGEST_PUBLISHED
        } else {
            LARGEST_PUBLISHED
        }
        return EnvelopeCost.Default.copy(toolSetOverhead = overhead)
    }
}

/**
 * OpenAI's chat framing, from the token-counting cookbook: every message costs three tokens of
 * framing, and every reply is primed with three more.
 *
 * Tool schema framing is not published, so it keeps the shared default.
 */
val OpenAiEnvelopeCost = EnvelopeCost(
    perMessage = 3,
    perToolDefinition = EnvelopeCost.Default.perToolDefinition,
    perToolCall = EnvelopeCost.Default.perToolCall,
    requestOverhead = 3,
)
