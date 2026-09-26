package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.OpenAiThinking
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.util.resolvedThinking
import com.newoether.agora.model.ResolvedThinking
import com.newoether.agora.model.ThinkingProviderFamily
import com.newoether.agora.util.Constants

class DeepSeekProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_DEEPSEEK
    override val defaultBaseUrl: String = "https://api.deepseek.com"

    /**
     * DeepSeek controls thinking through `thinking.type` and `reasoning_effort`. Neither used to
     * be sent, so the app toggle did nothing and the server default (thinking on, effort high)
     * always applied.
     */
    override fun customizeRequest(
        request: OpenAiChatRequest,
        config: ProviderConfig,
    ): OpenAiChatRequest =
        request.withDeepSeekThinking(config.resolvedThinking(ThinkingProviderFamily.DEEPSEEK))

    /** A thinking-mode DeepSeek request with tools fails with 400 unless earlier turns replay CoT. */
    override fun forwardsAssistantReasoningContent(config: ProviderConfig): Boolean =
        config.includeAssistantReasoning

    // Reasoning/content parsing uses BaseOpenAiProvider's default (reasoning_content + content).
}

/** True when a model id names a DeepSeek model, including hub- and relay-style prefixes. */
internal fun isDeepSeekModel(modelId: String): Boolean =
    modelId.contains("deepseek", ignoreCase = true)

/**
 * Applies the DeepSeek thinking controls to an OpenAI-format request. `thinking.type` toggles
 * thinking and `reasoning_effort` carries the level the model accepts.
 */
internal fun OpenAiChatRequest.withDeepSeekThinking(
    resolved: ResolvedThinking,
): OpenAiChatRequest = if (resolved.disabled) {
    copy(thinking = OpenAiThinking(type = "disabled"))
} else {
    copy(
        thinking = OpenAiThinking(type = "enabled"),
        reasoningEffort = resolved.effort,
    )
}
