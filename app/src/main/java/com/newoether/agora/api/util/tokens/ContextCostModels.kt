package com.newoether.agora.api.util.tokens

import com.newoether.agora.api.util.tokens.bpe.O200kBase
import com.newoether.agora.model.ModelId
import com.newoether.agora.util.Constants

/**
 * Token-cost families. Models in one family share a tokenizer, image rules and request framing.
 *
 * A family is about how tokens are counted, not about which HTTP protocol is spoken: the same
 * Claude model priced through Anthropic, OpenRouter or a private gateway costs the same tokens.
 */
enum class ContextCostFamily {
    ANTHROPIC,
    OPENAI,
    GEMINI,

    /** The embedded llama.cpp runtime, the only family whose exact vocabulary is on device. */
    LOCAL,

    /** Everything else, including arbitrary OpenAI-compatible endpoints. */
    GENERIC,
}

/**
 * The single place where a provider and model id choose a cost model.
 *
 * Nothing else in the estimator may branch on provider names. Adding a family means adding
 * implementations of the cost interfaces plus one entry here.
 */
object ContextCostModels {
    /** Cost model for a parsed model id, or the default when no model is selected. */
    fun forModel(modelId: ModelId?): ContextCostModel {
        if (modelId == null) return ContextCostModel.Default
        val family = familyOf(modelId)
        return ContextCostModel.Default.copy(
            text = textCounter(family),
            image = imageCost(family, modelId.modelName),
            envelope = envelopeCost(family, modelId.modelName),
        )
    }

    /** Cost model for a "Provider:model" string, parsed through [ModelId.parse]. */
    fun forModel(prefixedModelId: String?): ContextCostModel =
        forModel(prefixedModelId?.takeIf(String::isNotBlank)?.let(ModelId::parse))

    /**
     * Cost model for a family with no model id available. Image rules that depend on the model, such
     * as the Anthropic resolution tier and the OpenAI sizing behaviour, take their conservative
     * family default here.
     */
    fun forFamily(family: ContextCostFamily): ContextCostModel = ContextCostModel.Default.copy(
        text = textCounter(family),
        image = imageCost(family, modelName = ""),
        envelope = envelopeCost(family, modelName = ""),
    )

    /**
     * Request framing per family. Only values a provider publishes are used; anything unpublished
     * keeps the shared default, so a family never gets invented numbers.
     */
    private fun envelopeCost(family: ContextCostFamily, modelName: String): EnvelopeCost =
        when (family) {
            ContextCostFamily.ANTHROPIC -> AnthropicEnvelopeCosts.forModelName(modelName)
            ContextCostFamily.OPENAI -> OpenAiEnvelopeCost
            // Gemini, the embedded runtime and unknown endpoints publish no framing constants. The
            // local runtime's chat template cost arrives with exact tokenization instead.
            ContextCostFamily.GEMINI,
            ContextCostFamily.LOCAL,
            ContextCostFamily.GENERIC,
            -> EnvelopeCost.Default
        }

    /**
     * Text counting per family. Only the embedded runtime can be exact, because only it has the
     * model's own vocabulary on the device; every remote family shares the offline counter.
     */
    private fun textCounter(family: ContextCostFamily): TextTokenCounter = when (family) {
        ContextCostFamily.LOCAL -> LocalModelTextCounter(
            // GGUF native token counting is gone; the ONNX local model has no synchronous
            // on-device vocabulary for cost estimation, so use the offline heuristic.
            exactCount = { null },
        )
        // Every remote family counts through the one vocabulary that ships with the app. It is the
        // OpenAI families' own tokenizer; for the others it is a much closer proxy than counting
        // characters, and correcting it per family waits for measured factors rather than guesses.
        ContextCostFamily.ANTHROPIC,
        ContextCostFamily.OPENAI,
        ContextCostFamily.GEMINI,
        ContextCostFamily.GENERIC,
        -> BpeTextTokenCounter(vocabulary = O200kBase::loadedOrNull)
    }

    /** Image rules per family, the other half of what a family can differ in today. */
    private fun imageCost(family: ContextCostFamily, modelName: String): ImageTokenCost =
        when (family) {
            ContextCostFamily.ANTHROPIC -> AnthropicImageCost.forModelName(modelName)
            ContextCostFamily.OPENAI -> OpenAiImageCosts.forModelName(modelName)
            ContextCostFamily.GEMINI -> GeminiImageCost
            // The embedded runtime's projector and unrecognised endpoints publish no pixel rule, so
            // the byte proxy stays their most truthful available estimate.
            ContextCostFamily.LOCAL, ContextCostFamily.GENERIC -> ByteProportionalImageCost
        }

    /**
     * Classifies a model. Built-in provider names that pin exactly one family decide first;
     * otherwise the model name decides, which is what makes aggregators and custom gateways land in
     * the right family. Anything unrecognised is [ContextCostFamily.GENERIC].
     */
    fun familyOf(modelId: ModelId?): ContextCostFamily {
        if (modelId == null) return ContextCostFamily.GENERIC
        familyOfProvider(modelId.providerName)?.let { return it }
        return familyOfModelName(modelId.modelName)
    }

    private fun familyOfProvider(providerName: String): ContextCostFamily? = when {
        providerName.equals(Constants.PROVIDER_ANTHROPIC, ignoreCase = true) ->
            ContextCostFamily.ANTHROPIC
        providerName.equals(Constants.PROVIDER_OPENAI, ignoreCase = true) ->
            ContextCostFamily.OPENAI
        providerName.equals(Constants.PROVIDER_GOOGLE, ignoreCase = true) ->
            ContextCostFamily.GEMINI
        providerName.equals(Constants.PROVIDER_LOCAL, ignoreCase = true) ->
            ContextCostFamily.LOCAL
        else -> null
    }

    private fun familyOfModelName(modelName: String): ContextCostFamily {
        val name = modelName.lowercase()
        return when {
            name.contains("claude") -> ContextCostFamily.ANTHROPIC
            name.contains("gemini") -> ContextCostFamily.GEMINI
            name.contains("gpt-") || name.contains("chatgpt") ||
                OPENAI_REASONING_PREFIXES.any { name.startsWith(it) } -> ContextCostFamily.OPENAI
            else -> ContextCostFamily.GENERIC
        }
    }

    /** OpenAI reasoning families are named by bare prefix, so they only match at the start. */
    private val OPENAI_REASONING_PREFIXES = listOf("o1", "o3", "o4")
}
