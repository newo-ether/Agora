package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.util.resolvedThinking
import com.newoether.agora.model.ThinkingProviderFamily

class CustomOpenAiProvider(
    override val name: String,
    override val defaultBaseUrl: String
) : BaseOpenAiProvider() {

    override val retryableStatusCodes: Set<Int> = setOf(429, 502, 503, 504)

    override val retryMissingV1BaseUrl: Boolean = true

    /**
     * A relay has no published parameter documentation, so no model id may decide the request
     * shape here. Thinking settings are forwarded exactly as the user configured them through the
     * permissive default capability, which the user can narrow per model when a relay rejects a
     * field.
     */
    override fun customizeRequest(
        request: OpenAiChatRequest,
        config: ProviderConfig,
    ): OpenAiChatRequest {
        val resolved = config.resolvedThinking(ThinkingProviderFamily.OPENAI_COMPATIBLE)
        val effort = if (resolved.disabled) {
            "none".takeIf { resolved.capability.supportsEffort }
        } else {
            resolved.effort
        }
        return request.copy(
            reasoningEffort = effort,
            thinkingBudget = resolved.budgetTokens,
        )
    }

    /** Same rule as the built-in provider: a relayed DeepSeek model replays chain of thought. */
    override fun forwardsAssistantReasoningContent(config: ProviderConfig): Boolean =
        config.includeAssistantReasoning
}
