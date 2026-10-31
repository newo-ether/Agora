package com.newoether.agora.viewmodel

import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.resolveRequest
import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.api.util.tokens.FixedContextComposition
import com.newoether.agora.util.Constants

/** Owns fixed-context pricing for preview and Compact using the dispatch tool-definition source. */
internal class GenerationContextAccounting(
    private val toolDefinitions: GenerationToolDefinitionSource,
) {
    private fun definitions(config: GenerationConfig, context: GenerationContext) =
        if (config.lowContextModeEnabled) emptyList() else toolDefinitions.definitions(context)

    fun fixedContextTokenCost(config: GenerationConfig, context: GenerationContext): Int =
        ContextTokenEstimator.forModel(config.modelId).estimateFixed(
            systemPrompt = config.effectiveSystemPrompt,
            tools = definitions(config, context),
            initialUserPrompt = config.initialUserPrompt,
            codeExecutionEnabled = config.codeExecutionEnabled,
            googleSearchEnabled = config.googleSearchEnabled,
            openAiWebSearchEnabled = config.openAiWebSearchEnabled,
        )

    fun fixedContextComposition(
        config: GenerationConfig,
        context: GenerationContext,
    ): FixedContextComposition = ContextTokenEstimator.forModel(config.modelId).estimateFixedComposition(
        systemPrompt = config.effectiveSystemPrompt,
        tools = definitions(config, context),
        initialUserPrompt = config.initialUserPrompt,
        codeExecutionEnabled = config.codeExecutionEnabled,
        googleSearchEnabled = config.googleSearchEnabled,
        openAiWebSearchEnabled = config.openAiWebSearchEnabled,
    )

    fun includesAssistantReasoning(config: GenerationConfig, context: GenerationContext): Boolean =
        !config.responsesApiEnabled && config.thinkingEnabled &&
            (config.providerName == Constants.PROVIDER_DEEPSEEK ||
                com.newoether.agora.api.openai.isDeepSeekModel(config.modelId)) &&
            !config.lowContextModeEnabled && definitions(config, context).isNotEmpty()

    suspend fun resolvedFixedContextTokenCost(
        config: GenerationConfig,
        context: GenerationContext,
    ): Int {
        val resolver = config.requestResolver ?: return fixedContextTokenCost(config, context)
        val tools = definitions(config, context)
        val providerConfig = ProviderConfig(
            apiKey = config.apiKey,
            modelId = config.modelId,
            anthropicCacheEnabled = config.anthropicCacheEnabled,
            anthropicCacheTtl = config.anthropicCacheTtl,
            maxContextWindow = config.maxContextWindow,
            codeExecutionEnabled = config.codeExecutionEnabled,
            googleSearchEnabled = config.googleSearchEnabled,
            openAiWebSearchEnabled = config.openAiWebSearchEnabled,
            tools = tools,
            includeImages = true,
            includeAssistantReasoning = includesAssistantReasoning(config, context),
            requestResolver = resolver,
        )
        val resolvedRequest = providerConfig.resolveRequest(emptyList())
        return ContextTokenEstimator.forModel(config.modelId).estimateFixed(
            systemPrompt = resolvedRequest.systemPrompt,
            tools = tools,
            initialUserPrompt = config.initialUserPrompt,
            codeExecutionEnabled = config.codeExecutionEnabled,
            googleSearchEnabled = config.googleSearchEnabled,
            openAiWebSearchEnabled = config.openAiWebSearchEnabled,
        )
    }
}
