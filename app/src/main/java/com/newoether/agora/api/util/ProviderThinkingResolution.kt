package com.newoether.agora.api.util

import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.model.ModelThinkingCapabilities
import com.newoether.agora.model.ResolvedThinking
import com.newoether.agora.model.ThinkingProviderFamily
import com.newoether.agora.model.ThinkingResolution

/**
 * Single place where a request's thinking settings meet the selected model's capability.
 *
 * Providers call this instead of interpreting model names. The result never blocks a request: a
 * model that cannot disable thinking receives its lowest accepted setting, and an effort the model
 * does not accept is resolved to its nearest accepted level.
 */
internal fun ProviderConfig.resolvedThinking(family: ThinkingProviderFamily): ResolvedThinking =
    ThinkingResolution.resolve(
        capability = ModelThinkingCapabilities.forModel(
            family = family,
            modelId = modelId,
        ),
        requestedEnabled = thinkingEnabled,
        requestedEffort = thinkingLevel,
        requestedBudgetEnabled = thinkingBudgetEnabled,
        requestedBudgetTokens = thinkingBudgetTokens,
    )
