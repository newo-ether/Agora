package com.newoether.agora.api.util.tokens

import com.newoether.agora.api.LocalModelRuntime
import com.newoether.agora.api.util.tokens.bpe.O200kBase
import com.newoether.agora.model.ModelId
import com.newoether.agora.util.Constants

/** Cost identity is independent of the HTTP transport selected by a provider. */
enum class ContextCostFamily { ANTHROPIC, OPENAI, GEMINI, DEEPSEEK, LOCAL, GENERIC }

object ContextCostModels {
    fun forModel(modelId: ModelId?): ContextCostModel {
        if (modelId == null) return ContextCostModel.Default
        val family = familyOf(modelId)
        return ContextCostModel.Default.copy(
            text = textCounter(family),
            image = imageCost(family, modelId.modelName),
            envelope = envelopeCost(family, modelId.modelName),
        )
    }

    fun forModel(prefixedModelId: String?): ContextCostModel =
        forModel(prefixedModelId?.trim()?.takeIf(String::isNotBlank)?.let(ModelId::parse))

    fun forFamily(family: ContextCostFamily): ContextCostModel = ContextCostModel.Default.copy(
        text = textCounter(family),
        image = imageCost(family, modelName = ""),
        envelope = envelopeCost(family, modelName = ""),
    )

    private fun envelopeCost(family: ContextCostFamily, modelName: String): EnvelopeCost =
        when (family) {
            ContextCostFamily.ANTHROPIC -> AnthropicEnvelopeCosts.forModelName(modelName)
            ContextCostFamily.OPENAI -> OpenAiEnvelopeCost
            else -> EnvelopeCost.Default
        }

    private fun textCounter(family: ContextCostFamily): TextTokenCounter = when (family) {
        ContextCostFamily.LOCAL -> LocalModelTextCounter(
            exactCount = { text -> LocalModelRuntime.exactTokenCountOrNull(text) },
        )
        // Remote non-OpenAI vocabularies remain approximate. This change only prices image input.
        else -> BpeTextTokenCounter(vocabulary = O200kBase::loadedOrNull)
    }

    private fun imageCost(family: ContextCostFamily, modelName: String): ImageTokenCost =
        when (family) {
            ContextCostFamily.ANTHROPIC -> AnthropicImageCost.forModelName(modelName)
            ContextCostFamily.OPENAI -> OpenAiImageCosts.forModelName(modelName)
            ContextCostFamily.GEMINI -> GeminiImageCost.forModelName(modelName)
            ContextCostFamily.DEEPSEEK -> DeepSeekImageCost.forModelName(modelName)
            ContextCostFamily.LOCAL, ContextCostFamily.GENERIC -> HeuristicPixelImageCost
        }

    fun familyOf(modelId: ModelId?): ContextCostFamily {
        if (modelId == null) return ContextCostFamily.GENERIC
        // Local is an execution boundary with the only exact on-device text tokenizer.
        if (modelId.providerName.equals(Constants.PROVIDER_LOCAL, ignoreCase = true)) {
            return ContextCostFamily.LOCAL
        }
        val family = familyOfModelName(modelId.modelName)
        if (family != ContextCostFamily.GENERIC) return family
        // A configured transport cannot establish an otherwise unknown model's visual rules.
        return ContextCostFamily.GENERIC
    }

    private fun familyOfModelName(modelName: String): ContextCostFamily {
        val components = modelName.trim().lowercase().split('/')
        val name = components.last().trim()
        return when {
            name.startsWith("claude") -> ContextCostFamily.ANTHROPIC
            name.startsWith("gemini") -> ContextCostFamily.GEMINI
            name.startsWith("deepseek") -> ContextCostFamily.DEEPSEEK
            name.startsWith("gpt-") || name.startsWith("chatgpt") ||
                Regex("^o[134](?:$|[-.])").containsMatchIn(name) -> ContextCostFamily.OPENAI
            components.dropLast(1).any { it == "anthropic" } -> ContextCostFamily.ANTHROPIC
            components.dropLast(1).any { it == "deepseek" } -> ContextCostFamily.DEEPSEEK
            components.dropLast(1).any { it == "openai" } -> ContextCostFamily.OPENAI
            else -> ContextCostFamily.GENERIC
        }
    }
}
