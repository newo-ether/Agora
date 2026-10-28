package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.util.Constants

class MunitoProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_MUNITO
    override val defaultBaseUrl: String = "https://api.munito.ai/inference/v1"

    /**
     * Prompt caching keys on a stable prefix. Generation sends the conversation id as
     * `prompt_cache_key`; every request with a session also sends `x-session-affinity`, including
     * title and transcription calls that have no cache-key body field.
     */
    override fun getExtraHeaders(config: ProviderConfig): Map<String, String> =
        config.sessionId?.takeIf(String::isNotBlank)
            ?.let { mapOf("x-session-affinity" to it) }
            ?: emptyMap()

    /** Munito documents `reasoning_effort` as the thinking control on Chat Completions. */
    override fun customizeRequest(request: OpenAiChatRequest, config: ProviderConfig): OpenAiChatRequest =
        request.withOpenAiReasoningEffort(config)
}
