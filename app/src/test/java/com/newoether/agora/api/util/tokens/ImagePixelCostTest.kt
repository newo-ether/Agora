package com.newoether.agora.api.util.tokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pixel image costs checked against the figures published by each provider.
 *
 * Formula goldens use published examples or the provider's live reference calculator. Selector
 * and fallback tests separately check Agora's conservative scope and provenance handling.
 */
class ImagePixelCostTest {

    private fun ImageTokenCost.tokens(width: Int, height: Int): Long =
        tokens(ImageDescriptor(pixelWidth = width, pixelHeight = height))

    @Test
    fun anthropicStandardTierMatchesPublishedVisualTokenTable() {
        val cost = AnthropicImageCost(AnthropicImageCost.Tier.STANDARD)
        // docs.claude.com resolution and token cost table.
        assertEquals(64L, cost.tokens(200, 200))
        assertEquals(1_296L, cost.tokens(1_000, 1_000))
        assertEquals(1_521L, cost.tokens(1_092, 1_092))
        assertEquals(1_560L, cost.tokens(1_920, 1_080))
        assertEquals(1_564L, cost.tokens(2_000, 1_500))
        assertEquals(1_560L, cost.tokens(3_840, 2_160))
    }

    @Test
    fun anthropicHighResolutionTierMatchesPublishedVisualTokenTable() {
        val cost = AnthropicImageCost(AnthropicImageCost.Tier.HIGH_RESOLUTION)
        assertEquals(64L, cost.tokens(200, 200))
        assertEquals(1_296L, cost.tokens(1_000, 1_000))
        // Not resized at this tier, unlike the standard tier's 1560.
        assertEquals(2_691L, cost.tokens(1_920, 1_080))
        assertEquals(4_784L, cost.tokens(3_840, 2_160))
    }

    @Test
    fun anthropicCostIsOrientationIndependent() {
        val cost = AnthropicImageCost(AnthropicImageCost.Tier.STANDARD)
        assertEquals(cost.tokens(1_920, 1_080), cost.tokens(1_080, 1_920))
    }

    @Test
    fun anthropicTierReadsTheModelVersionFromTheId() {
        assertEquals(
            AnthropicImageCost.Tier.STANDARD,
            AnthropicImageCost.tierForModelName("claude-3-7-sonnet-20250219"),
        )
        assertEquals(
            AnthropicImageCost.Tier.STANDARD,
            AnthropicImageCost.tierForModelName("claude-sonnet-4-5-20250929"),
        )
        assertEquals(
            AnthropicImageCost.Tier.STANDARD,
            AnthropicImageCost.tierForModelName("anthropic/claude-opus-4-1"),
        )
        assertEquals(
            AnthropicImageCost.Tier.HIGH_RESOLUTION,
            AnthropicImageCost.tierForModelName("claude-sonnet-4-7-20260115"),
        )
        assertEquals(
            AnthropicImageCost.Tier.HIGH_RESOLUTION,
            AnthropicImageCost.tierForModelName("claude-opus-5-20260601"),
        )
        // An unreadable version has no documented rule in the selector, even though the tier
        // helper remains compatible with old callers.
        assertEquals(
            AnthropicImageCost.Tier.STANDARD,
            AnthropicImageCost.tierForModelName("claude-latest"),
        )
    }

    @Test
    fun openAiPatchCostMatchesPublishedWorkedExamples() {
        // platform.openai.com worked examples for a 2,500-patch budget and a 1.2 multiplier.
        val cost = OpenAiPatchImageCost(maxEdgePx = 65_535, patchBudget = 2_500, multiplier = 1.2)
        assertEquals(1_229L, cost.tokens(1_024, 1_024))
        assertEquals(3_000L, cost.tokens(2_048, 2_048))
        assertEquals(2_458L, cost.tokens(4_096, 512))
    }

    @Test
    fun openAiPatchCostWithoutBudgetOnlyAppliesThePixelLimit() {
        val cost = OpenAiPatchImageCost(maxEdgePx = 2_048, patchBudget = null, multiplier = 1.2)
        // 2048x1536 covers 64 x 48 patches, and no budget shrinks it.
        assertEquals(3_687L, cost.tokens(2_048, 1_536))
        // A 4096 px long edge is first fitted to 2048 px, so it costs the same as the fitted image.
        assertEquals(cost.tokens(2_048, 1_024), cost.tokens(4_096, 2_048))
    }

    @Test
    fun openAiTileCostMatchesPublishedWorkedExamples() {
        val cost = OpenAiTileImageCost(baseTokens = 85, tileTokens = 170)
        assertEquals(765L, cost.tokens(1_024, 1_024))
        assertEquals(1_105L, cost.tokens(2_048, 4_096))
        // A single tile still pays the base cost.
        assertEquals(255L, cost.tokens(512, 512))
    }

    @Test
    fun openAiModelSelectionFollowsThePublishedSizingTable() {
        assertEquals(255L, OpenAiImageCosts.forModelName("gpt-4o").tokens(512, 512))
        // gpt-4o-mini prices the same tiles far higher.
        assertEquals(
            2_833L + 5_667L,
            OpenAiImageCosts.forModelName("gpt-4o-mini").tokens(512, 512),
        )
        // gpt-4.1-mini is patch based with a 1.62 multiplier, unlike gpt-4.1.
        assertEquals(765L, OpenAiImageCosts.forModelName("gpt-4.1").tokens(1_024, 1_024))
        assertEquals(1_659L, OpenAiImageCosts.forModelName("gpt-4.1-mini").tokens(1_024, 1_024))
        // Unpublished variants use an explicitly labelled pixel proxy.
        assertEquals(1_024L, OpenAiImageCosts.forModelName("gpt-7-unknown").tokens(1_024, 1_024))
    }

    @Test
    fun openAiAutoDistinguishesOriginalFromHighAndDoesNotCapTheRejectionLimit() {
        assertEquals(19_661L, OpenAiImageCosts.forModelName("gpt-6-astra").tokens(4_096, 4_096))
        assertEquals(19_661L, OpenAiImageCosts.forModelName("gpt-5.6-sol").tokens(4_096, 4_096))
        assertEquals(12_000L, OpenAiImageCosts.forModelName("gpt-5.5").tokens(4_096, 4_096))
        listOf("gpt-5.4", "gpt-5.4-mini", "gpt-5.4-nano").forEach { model ->
            assertEquals(3_000L, OpenAiImageCosts.forModelName(model).tokens(4_096, 4_096))
        }
        assertTrue(OpenAiImageCosts.forModelName("gpt-6-astra").tokens(8_192, 8_192) > 36_000L)
    }

    @Test
    fun openAiOnlyListedModelsAndDatedSnapshotsReceiveDocumentedRules() {
        val image = ImageDescriptor(pixelWidth = 1_024, pixelHeight = 1_024)
        assertEquals(ImageEstimateBasis.DOCUMENTED,
            OpenAiImageCosts.forModelName("openai/gpt-4.1-mini-2025-04-14").estimate(image).basis)
        listOf("gpt-6-sol", "gpt-6.1-sol", "gpt-5-unknown", "gpt-5.4-special",
            "gpt-4o-audio-preview", "gpt-5-mini", "gpt-4.1-nano", "o4-mini").forEach { model ->
            assertEquals(model, ImageEstimateBasis.HEURISTIC,
                OpenAiImageCosts.forModelName(model).estimate(image).basis)
        }
    }

    @Test
    fun deepSeekMatchesTheOfficialV41CalculatorIncludingRowSeparatorsAndUpsampling() {
        // https://api-docs.deepseek.com/assets/js/1e0da2c4.24c3fb4b.js, evaluated 2026-10-27.
        listOf(1, 200, 512).forEach { assertEquals(184L, DeepSeekImageCost.tokens(it, it)) }
        assertEquals(602L, DeepSeekImageCost.tokens(1_000, 1_000))
        assertEquals(968L, DeepSeekImageCost.tokens(1_920, 1_080))
        assertEquals(986L, DeepSeekImageCost.tokens(1_080, 1_920))
        assertEquals(994L, DeepSeekImageCost.tokens(2_000, 2_000))
        assertEquals(994L, DeepSeekImageCost.tokens(5_000, 5_000))
        assertEquals(1_024L, DeepSeekImageCost.tokens(999_999, 1))
        assertEquals(1_024L, DeepSeekImageCost.tokens(1, 999_999))
        assertEquals(981L, DeepSeekImageCost.tokens(4_096, 512))
        assertEquals(1_001L, DeepSeekImageCost.tokens(1_075, 1_520))
    }

    @Test
    fun deepSeekOnlyThePublishedVisionAliasesUseTheCalculator() {
        val image = ImageDescriptor(pixelWidth = 1_000, pixelHeight = 1_000)
        listOf("deepseek-flash", "deepseek/deepseek-v4-flash-vision-exp").forEach { model ->
            assertEquals(ImageEstimateBasis.DOCUMENTED, DeepSeekImageCost.forModelName(model)
                .estimate(image).basis)
        }
        listOf("deepseek-chat", "deepseek-v4.1", "deepseek-v4-pro").forEach { model ->
            assertEquals(ImageEstimateBasis.HEURISTIC, DeepSeekImageCost.forModelName(model)
                .estimate(image).basis)
        }
    }

    @Test
    fun geminiThreeAndFourUseTheUnspecifiedImageResolutionDefault() {
        listOf("gemini-3-pro", "gemini-4-flash").forEach { model ->
            assertEquals(1_120L, GeminiImageCost.forModelName(model).tokens(200, 200))
            assertEquals(1_120L, GeminiImageCost.forModelName(model).tokens(4_096, 2_048))
        }
        assertEquals(ImageEstimateBasis.HEURISTIC, GeminiImageCost.forModelName("gemini-future")
            .estimate(ImageDescriptor(pixelWidth = 200, pixelHeight = 200)).basis)
    }

    @Test
    fun geminiCostMatchesPublishedCropRule() {
        // ai.google.dev: 258 tokens when both dimensions are at most 384 px.
        assertEquals(258L, GeminiImageCost.tokens(300, 300))
        assertEquals(258L, GeminiImageCost.tokens(384, 384))
        // The guide's own example: 960x540 has a 360 px crop unit and 3 x 2 = 6 tiles.
        assertEquals(6L * 258L, GeminiImageCost.tokens(960, 540))
        // A square image is always 2 x 2 crops, whatever its size.
        assertEquals(4L * 258L, GeminiImageCost.tokens(1_024, 1_024))
        assertEquals(4L * 258L, GeminiImageCost.tokens(3_000, 3_000))
    }

    @Test
    fun pixelCostsKeepMissingDimensionsExplicitWithoutUsingBytes() {
        val descriptors = listOf(
            ImageDescriptor(byteSize = 768L * 300L),
            ImageDescriptor(),
            ImageDescriptor(byteSize = 768L * 300L, pixelWidth = 1_024),
        )
        val costs = listOf(
            AnthropicImageCost(AnthropicImageCost.Tier.STANDARD),
            OpenAiPatchImageCost(2_048, 2_500, 1.2),
            OpenAiTileImageCost(85, 170),
            GeminiImageCost,
            DeepSeekImageCost,
        )
        costs.forEach { cost ->
            descriptors.forEach { descriptor ->
                assertEquals(
                    HeuristicPixelImageCost.estimate(descriptor),
                    cost.estimate(descriptor),
                )
            }
        }
    }
}
