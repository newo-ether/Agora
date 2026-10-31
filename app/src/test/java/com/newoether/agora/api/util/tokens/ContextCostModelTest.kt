package com.newoether.agora.api.util.tokens

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.api.util.ContextTokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each cost-model component priced on its own, plus proof that the estimator uses the model. */
class ContextCostModelTest {

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

    @Test
    fun heuristicCounterChargesAsciiRunsByFourAndEveryOtherCodePointByOne() {
        val counter = HeuristicTextTokenCounter
        assertEquals(0L, counter.count(""))
        assertEquals(0L, counter.count("   \n\t"))
        assertEquals(1L, counter.count("abcd"))
        assertEquals(2L, counter.count("abcde"))
        // "hello" and "world" are two runs of five, so two tokens each.
        assertEquals(4L, counter.count("hello world"))
        // Punctuation flushes the run and costs one token of its own.
        assertEquals(3L, counter.count("a-b"))
        // Non-ASCII code points cost one each, never four to a token.
        assertEquals(2L, counter.count("你好"))
        // A surrogate pair is one code point, so one token, not two.
        assertEquals(1L, counter.count("\uD83D\uDE00"))
    }

    @Test
    fun unknownModelImageCostUsesDimensionsWithoutAUniversalCap() {
        val cost = HeuristicPixelImageCost
        assertEquals(1L, cost.tokens(ImageDescriptor(pixelWidth = 1, pixelHeight = 1)))
        assertEquals(256L, cost.tokens(ImageDescriptor(pixelWidth = 512, pixelHeight = 512)))
        assertEquals(4_096L, cost.tokens(ImageDescriptor(pixelWidth = 2_048, pixelHeight = 2_048)))
        val estimate = cost.estimate(ImageDescriptor(pixelWidth = 512, pixelHeight = 512))
        assertEquals(ImageEstimateBasis.HEURISTIC, estimate.basis)
        assertEquals("unpublished-32px-patch-proxy", estimate.ruleId)
    }

    @Test
    fun encodedLengthNeverChangesThePixelHeuristicOrResolvesMissingDimensions() {
        val cost = HeuristicPixelImageCost
        val smallFile = ImageDescriptor(byteSize = 1L, pixelWidth = 1_000, pixelHeight = 1_000)
        assertEquals(cost.estimate(smallFile), cost.estimate(smallFile.copy(byteSize = Long.MAX_VALUE)))
        listOf(null, 0L, -4_096L, Long.MAX_VALUE).forEach { size ->
            val estimate = cost.estimate(ImageDescriptor(byteSize = size))
            assertEquals(ImageEstimateBasis.MISSING_DIMENSIONS, estimate.basis)
            assertEquals(HeuristicPixelImageCost.MISSING_DIMENSIONS_RESERVATION, estimate.tokens)
        }
    }

    @Test
    fun safetyMarginRoundsUpAndClampsToIntRange() {
        val margin = SafetyMargin.Default
        assertEquals(0, margin.apply(0L))
        assertEquals(2, margin.apply(1L))
        assertEquals(11, margin.apply(10L))
        assertEquals(110, margin.apply(100L))
        assertEquals(Int.MAX_VALUE, margin.apply(Int.MAX_VALUE.toLong()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun safetyMarginRejectsZeroDenominator() {
        SafetyMargin(numerator = 11L, denominator = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun safetyMarginRejectsShrinkingTheEstimate() {
        SafetyMargin(numerator = 9L, denominator = 10L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun envelopeCostRejectsNegativeFraming() {
        EnvelopeCost(perMessage = -1, perToolDefinition = 16, perToolCall = 16)
    }

    @Test
    fun defaultModelReproducesTheSharedEstimatorExactly() {
        val estimator = CostModelContextEstimator(ContextCostModel.Default)
        val tools = listOf(tool())
        assertEquals(
            ContextTokenEstimator.estimateFixed("System prompt", tools),
            estimator.estimateFixed("System prompt", tools),
        )
        assertEquals(
            ContextTokenEstimator.estimateText("hello world"),
            estimator.estimateText("hello world"),
        )
        assertEquals(
            ContextTokenEstimator.estimateImageTokens(ImageDescriptor(pixelWidth = 640, pixelHeight = 480)),
            estimator.imageTokens(ImageDescriptor(pixelWidth = 640, pixelHeight = 480)),
        )
    }

    @Test
    fun estimatorPricesThroughTheInjectedModelInsteadOfFixedConstants() {
        val doubled = ContextCostModel.Default.copy(
            text = TextTokenCounter { text -> HeuristicTextTokenCounter.count(text) * 2 },
        )
        assertEquals(
            SafetyMargin.Default.apply(HeuristicTextTokenCounter.count("hello world") * 2),
            CostModelContextEstimator(doubled).estimateText("hello world"),
        )

        val heavierFraming = ContextCostModel.Default.copy(
            envelope = EnvelopeCost(perMessage = 80, perToolDefinition = 160, perToolCall = 160),
        )
        val tools = listOf(tool())
        assertTrue(
            CostModelContextEstimator(heavierFraming).estimateFixed("System prompt", tools) >
                ContextTokenEstimator.estimateFixed("System prompt", tools),
        )

        val freeImages = ContextCostModel.Default.copy(image = ImageTokenCost { 0L })
        assertEquals(0L, CostModelContextEstimator(freeImages).imageTokens(ImageDescriptor()))
    }
}
