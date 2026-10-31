package com.newoether.agora.api.util.tokens

/**
 * Cost of the framing a provider adds around content it is sent.
 *
 * Role markers, message delimiters, tool schema wrappers and tool-call envelopes are real tokens
 * that no content-based counter sees. The defaults are one shared pair of constants; per-family
 * values replace them once each provider's request shape is measured.
 */
data class EnvelopeCost(
    val perMessage: Int,
    val perToolDefinition: Int,
    val perToolCall: Int,
    /** Charged once per request, for framing that exists even with one message and no tools. */
    val requestOverhead: Int = 0,
    /**
     * Charged once when the request carries at least one tool, for the tool-use preamble a provider
     * injects on its own. Zero when the provider publishes no such cost.
     */
    val toolSetOverhead: Int = 0,
) {
    init {
        require(
            perMessage >= 0 && perToolDefinition >= 0 && perToolCall >= 0 &&
                requestOverhead >= 0 && toolSetOverhead >= 0,
        ) {
            "Envelope cost cannot be negative"
        }
    }

    companion object {
        /** Historical shared framing cost: 8 tokens per message, 16 per tool schema or call. */
        val Default = EnvelopeCost(perMessage = 8, perToolDefinition = 16, perToolCall = 16)
    }
}

/**
 * Final upward correction applied to a raw count.
 *
 * An offline estimate that lands under the real count is the dangerous direction: it lets a request
 * exceed the window. The margin is a fixed, reviewed ratio, never a value learned from a provider
 * response.
 */
data class SafetyMargin(val numerator: Long, val denominator: Long) {
    init {
        require(denominator > 0L) { "Safety margin denominator must be positive" }
        require(numerator >= denominator) { "Safety margin must not reduce the estimate" }
    }

    /** Rounds up, so the margin never disappears on small counts. */
    fun apply(raw: Long): Int =
        ((raw * numerator + denominator - 1) / denominator)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()

    companion object {
        /** Ten percent, the ratio every surface has used since the estimate was introduced. */
        val Default = SafetyMargin(numerator = 11L, denominator = 10L)
    }
}

/**
 * The complete price list an estimator needs: how text, images and framing are counted, plus the
 * final margin.
 *
 * The estimator walks messages and asks this bundle for prices. It holds no provider knowledge of
 * its own, so adding a family means adding implementations here and one mapping entry in
 * [ContextCostModels], not new branches in the walk.
 */
data class ContextCostModel(
    val text: TextTokenCounter,
    val image: ImageTokenCost,
    val envelope: EnvelopeCost,
    val safetyMargin: SafetyMargin,
) {
    companion object {
        /**
         * Model-independent default: the character heuristic, dimension-based image proxy, shared
         * framing constants, and the ten percent margin. Used for every endpoint whose tokenizer
         * and image rules are unknown.
         */
        val Default = ContextCostModel(
            text = HeuristicTextTokenCounter,
            image = HeuristicPixelImageCost,
            envelope = EnvelopeCost.Default,
            safetyMargin = SafetyMargin.Default,
        )
    }
}
