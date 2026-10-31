package com.newoether.agora.api.util.tokens

import com.newoether.agora.model.ModelId
import com.newoether.agora.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The single selection site: which cost family and cost model a provider and model id resolve to. */
class ContextCostModelsTest {

    private fun familyOf(provider: String, model: String): ContextCostFamily =
        ContextCostModels.familyOf(ModelId(provider, model))

    @Test
    fun builtInProvidersResolveToTheirCostFamily() {
        assertEquals(
            ContextCostFamily.ANTHROPIC,
            familyOf(Constants.PROVIDER_ANTHROPIC, "claude-sonnet-4-5"),
        )
        assertEquals(ContextCostFamily.OPENAI, familyOf(Constants.PROVIDER_OPENAI, "gpt-5"))
        assertEquals(
            ContextCostFamily.GEMINI,
            familyOf(Constants.PROVIDER_GOOGLE, "models/gemini-2.5-flash"),
        )
        assertEquals(ContextCostFamily.LOCAL, familyOf(Constants.PROVIDER_LOCAL, "qwen3-4b-q4"))
        // Providers that host many vendors carry no family of their own.
        assertEquals(ContextCostFamily.GENERIC, familyOf(Constants.PROVIDER_OLLAMA, "llama3.2"))
        assertEquals(
            ContextCostFamily.DEEPSEEK,
            familyOf(Constants.PROVIDER_DEEPSEEK, "deepseek-chat"),
        )
        assertEquals(ContextCostFamily.GENERIC, familyOf(Constants.PROVIDER_QWEN, "qwen3-max"))
        assertEquals(
            ContextCostFamily.GENERIC,
            familyOf(Constants.PROVIDER_GROQ, "llama-3.3-70b-versatile"),
        )
        assertEquals(
            ContextCostFamily.GENERIC,
            familyOf(Constants.PROVIDER_OPEN_ROUTER, "mistralai/mistral-large"),
        )
        assertEquals(
            ContextCostFamily.GENERIC,
            familyOf(Constants.PROVIDER_OPENCODE_GO, "opencode/sonic"),
        )
        assertEquals(ContextCostFamily.GENERIC, familyOf(Constants.PROVIDER_UNKNOWN, "mystery-v2"))
    }

    @Test
    fun providerNamesMatchRegardlessOfCase() {
        assertEquals(ContextCostFamily.GENERIC, familyOf("anthropic", "some-model"))
        assertEquals(ContextCostFamily.LOCAL, familyOf("local", "some-model"))
    }

    @Test
    fun unknownEndpointsFallBackToTheModelName() {
        // A private gateway can serve any vendor, so the model name decides the family.
        assertEquals(
            ContextCostFamily.ANTHROPIC,
            familyOf("My Gateway", "anthropic/claude-3-5-sonnet"),
        )
        assertEquals(ContextCostFamily.GEMINI, familyOf("My Gateway", "gemini-2.5-pro"))
        assertEquals(ContextCostFamily.OPENAI, familyOf("My Gateway", "gpt-4o"))
        assertEquals(ContextCostFamily.OPENAI, familyOf("My Gateway", "o3-mini"))
        assertEquals(ContextCostFamily.OPENAI, familyOf("My Gateway", "ChatGPT-4o-latest"))
        assertEquals(ContextCostFamily.GENERIC, familyOf("My Gateway", "mystery-model-v2"))
    }

    @Test
    fun reasoningPrefixesOnlyMatchAtTheStartOfTheModelName() {
        assertEquals(ContextCostFamily.GENERIC, familyOf("My Gateway", "hermes-o1-tuned"))
        assertEquals(ContextCostFamily.GENERIC, familyOf("My Gateway", "moss-o4"))
    }

    @Test
    fun noSelectedModelResolvesToTheGenericFamily() {
        assertEquals(ContextCostFamily.GENERIC, ContextCostModels.familyOf(null))
        assertEquals(ContextCostModel.Default, ContextCostModels.forModel(null as ModelId?))
        assertEquals(ContextCostModel.Default, ContextCostModels.forModel(null as String?))
        assertEquals(ContextCostModel.Default, ContextCostModels.forModel("  "))
    }

    @Test
    fun theSafetyMarginStaysFamilyIndependent() {
        ContextCostFamily.entries.forEach { family ->
            assertEquals(
                ContextCostModel.Default.safetyMargin,
                ContextCostModels.forFamily(family).safetyMargin,
            )
        }
    }

    @Test
    fun everyFamilyCountsTextWithTheClosestTokenizerItHas() {
        // The embedded runtime is the one family that can be exact, because its own vocabulary is on
        // the device. Every remote family counts through the shipped BPE vocabulary instead of the
        // character heuristic, which now only survives as a fallback inside those counters.
        assertTrue(
            ContextCostModels.forFamily(ContextCostFamily.LOCAL).text is LocalModelTextCounter,
        )
        val remote = ContextCostFamily.entries - ContextCostFamily.LOCAL
        remote.forEach { family ->
            assertTrue(
                "$family should count with the shipped vocabulary",
                ContextCostModels.forFamily(family).text is BpeTextTokenCounter,
            )
        }
    }

    @Test
    fun onlyFamiliesWithPublishedFramingLeaveTheDefaultEnvelope() {
        assertEquals(
            EnvelopeCost.Default,
            ContextCostModels.forFamily(ContextCostFamily.GEMINI).envelope,
        )
        assertEquals(
            EnvelopeCost.Default,
            ContextCostModels.forFamily(ContextCostFamily.LOCAL).envelope,
        )
        assertEquals(
            EnvelopeCost.Default,
            ContextCostModels.forFamily(ContextCostFamily.GENERIC).envelope,
        )
        assertEquals(
            OpenAiEnvelopeCost,
            ContextCostModels.forModel("${Constants.PROVIDER_OPENAI}:gpt-5.1").envelope,
        )
        assertEquals(
            496,
            ContextCostModels.forModel("${Constants.PROVIDER_ANTHROPIC}:claude-sonnet-4-5")
                .envelope.toolSetOverhead,
        )
    }

    @Test
    fun eachFamilyPricesImagesWithItsOwnRule() {
        val image = ImageDescriptor(pixelWidth = 1_000, pixelHeight = 1_000)
        // Anthropic: 1000x1000 is 36 x 36 visual tokens.
        assertEquals(
            1_296L,
            ContextCostModels.forModel("${Constants.PROVIDER_ANTHROPIC}:claude-sonnet-4-5")
                .image.tokens(image),
        )
        // Gemini: a square image is 2 x 2 crops at 258 tokens each.
        assertEquals(
            4L * 258L,
            ContextCostModels.forModel("${Constants.PROVIDER_GOOGLE}:models/gemini-2.5-flash")
                .image.tokens(image),
        )
        // OpenAI tile-based family: 1000x1000 fits four 512 px tiles plus base tokens.
        assertEquals(
            765L,
            ContextCostModels.forModel("${Constants.PROVIDER_OPENAI}:gpt-4o").image.tokens(image),
        )
        // An unpublished identity uses only dimensions and carries heuristic provenance.
        assertEquals(
            HeuristicPixelImageCost.estimate(image),
            ContextCostModels.forModel("My Gateway:mystery-model-v2").image.estimate(image),
        )
        assertEquals(ContextCostModel.Default, ContextCostModels.forModel(null as String?))
    }

    @Test
    fun actualIdentityWinsOverAnOpenAiCompatibleTransport() {
        assertEquals(ContextCostFamily.ANTHROPIC, familyOf(Constants.PROVIDER_OPENAI, "claude-opus-4-7"))
        assertEquals(ContextCostFamily.DEEPSEEK, familyOf(Constants.PROVIDER_OPENAI, "deepseek-flash"))
        assertEquals(ContextCostFamily.GEMINI, familyOf(Constants.PROVIDER_OPENAI, "google/gemini-3-pro"))
        assertEquals(ContextCostFamily.GENERIC, familyOf(Constants.PROVIDER_OPENAI, "private-vision"))
        assertEquals(ContextCostFamily.ANTHROPIC, familyOf("Gateway", "anthropic/private-alias"))
        assertEquals(ContextCostFamily.LOCAL, familyOf(Constants.PROVIDER_LOCAL, "claude-opus-4-7"))
        val image = ImageDescriptor(pixelWidth = 1_000, pixelHeight = 1_000)
        assertEquals(
            602L,
            ContextCostModels.forModel(ModelId(Constants.PROVIDER_OPENAI, "deepseek-flash"))
                .image.tokens(image),
        )
    }

    @Test
    fun theAnthropicTierFollowsTheModelIdThroughTheFactory() {
        val image = ImageDescriptor(pixelWidth = 1_920, pixelHeight = 1_080)
        assertEquals(
            1_560L,
            ContextCostModels.forModel("${Constants.PROVIDER_ANTHROPIC}:claude-sonnet-4-5")
                .image.tokens(image),
        )
        assertEquals(
            2_691L,
            ContextCostModels.forModel("${Constants.PROVIDER_ANTHROPIC}:claude-sonnet-4-7")
                .image.tokens(image),
        )
    }
}
