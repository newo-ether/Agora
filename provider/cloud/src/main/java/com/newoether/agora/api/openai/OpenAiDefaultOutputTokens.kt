package com.newoether.agora.api.openai

import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.anthropic.AnthropicOutputLimits
import com.newoether.agora.api.anthropic.AnthropicProvider

/**
 * The output cap sent on the OpenAI-compatible path when the user sets none.
 *
 * Omitting the field leaves the cap to the endpoint, and endpoints pick small ones: DeepSeek and
 * relays translating to Anthropic commonly stop at 4K or 8K, which cuts off long answers and
 * context compacts with `finish_reason: length`. Models Agora knows get the same generous default
 * as the native Anthropic path (it is a cap, not a target); unknown models keep the field omitted,
 * because an unfamiliar upstream may reject a value above its own ceiling.
 */
internal fun ProviderConfig.effectiveOpenAiMaxTokens(): Int? {
    maxTokens?.let { return it }
    // A thinking budget counts against the same cap, so the answer keeps its headroom above it.
    val wanted = if (thinkingEnabled && thinkingBudgetEnabled) {
        maxOf(
            thinkingBudgetTokens + AnthropicProvider.ANSWER_HEADROOM_TOKENS,
            AnthropicProvider.DEFAULT_MAX_TOKENS,
        )
    } else {
        AnthropicProvider.DEFAULT_MAX_TOKENS
    }
    AnthropicOutputLimits.maxOutputTokens(modelId)?.let { return minOf(wanted, it) }
    // DeepSeek documents a 384K output ceiling for every current model, so the default always fits.
    if (isDeepSeekModel(modelId)) return wanted
    return null
}
