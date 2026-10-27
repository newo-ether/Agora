package com.newoether.agora.api.util

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.util.tokens.ContextCostModel
import com.newoether.agora.api.util.tokens.ContextCostModels
import com.newoether.agora.api.util.tokens.CostModelContextEstimator
import com.newoether.agora.api.util.tokens.ImageDescriptor
import com.newoether.agora.model.ChatMessage

/**
 * Model-independent entry point for the Provider-visible token estimate.
 *
 * Exact tokenization is model-specific and unavailable offline for arbitrary custom providers, so
 * the estimate is a local computation that never consults endpoint-reported usage. The counting
 * rules live in a [ContextCostModel]; this object binds the default model, which is what every
 * threshold, rollout decision and UI indicator uses today. Use [forModel] to count with the price
 * list of a specific model family.
 */
object ContextTokenEstimator {
    private val default = CostModelContextEstimator(ContextCostModel.Default)

    /** Estimator bound to the cost model of a "Provider:model" id. */
    fun forModel(prefixedModelId: String?): CostModelContextEstimator =
        CostModelContextEstimator(ContextCostModels.forModel(prefixedModelId))

    fun estimate(
        messages: List<ChatMessage>,
        includeAssistantReasoning: Boolean = false,
    ): Int = default.estimate(messages, includeAssistantReasoning)

    /** Provider-visible cost that exists even when the conversation history is empty. */
    fun estimateFixed(
        systemPrompt: String?,
        tools: List<ToolDefinition>,
        initialUserPrompt: String? = null,
        codeExecutionEnabled: Boolean = false,
        googleSearchEnabled: Boolean = false,
        openAiWebSearchEnabled: Boolean = false,
    ): Int = default.estimateFixed(
        systemPrompt = systemPrompt,
        tools = tools,
        initialUserPrompt = initialUserPrompt,
        codeExecutionEnabled = codeExecutionEnabled,
        googleSearchEnabled = googleSearchEnabled,
        openAiWebSearchEnabled = openAiWebSearchEnabled,
    )

    /** The same fixed cost as [estimateFixed], split by where it comes from, for the UI. */
    fun estimateFixedComposition(
        systemPrompt: String?,
        tools: List<ToolDefinition>,
        initialUserPrompt: String? = null,
        codeExecutionEnabled: Boolean = false,
        googleSearchEnabled: Boolean = false,
        openAiWebSearchEnabled: Boolean = false,
    ) = default.estimateFixedComposition(
        systemPrompt = systemPrompt,
        tools = tools,
        initialUserPrompt = initialUserPrompt,
        codeExecutionEnabled = codeExecutionEnabled,
        googleSearchEnabled = googleSearchEnabled,
        openAiWebSearchEnabled = openAiWebSearchEnabled,
    )

    internal fun estimateText(text: String): Int = default.estimateText(text)

    internal fun estimateImageTokens(image: ImageDescriptor): Long = default.imageTokens(image)
}
