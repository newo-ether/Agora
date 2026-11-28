package com.newoether.agora.api.util.tokens

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.api.util.ContextTokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** App integration cases; standalone cost components are tested in provider/cloud. */
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
