package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.util.resolvedThinking
import com.newoether.agora.model.ThinkingProviderFamily
import com.newoether.agora.util.Constants

class GroqProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_GROQ
    override val defaultBaseUrl: String = "https://api.groq.com/openai/v1"

    /**
     * Groq's `reasoning_effort` accepts low/medium/high, plus none/default on the models documented
     * for them. A model that cannot turn reasoning off keeps its lowest effort and hides the
     * reasoning with `include_reasoning: false` instead of failing the request.
     */
    override fun customizeRequest(
        request: OpenAiChatRequest,
        config: ProviderConfig,
    ): OpenAiChatRequest {
        val resolved = config.resolvedThinking(ThinkingProviderFamily.GROQ)
        val capability = resolved.capability
        if (!config.thinkingEnabled && !capability.canDisableThinking) {
            return request.copy(
                reasoningEffort = resolved.effort,
                includeReasoning = false,
            )
        }
        val effort = if (resolved.disabled) {
            "none".takeIf { capability.supportsEffort }
        } else {
            resolved.effort
        }
        return request.copy(reasoningEffort = effort)
    }
}
