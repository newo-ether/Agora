package com.newoether.agora.model

object ModelThinkingCapabilities {

    /**
     * Resolves the wire family. [customProtocolWireValue] is the persisted protocol of a custom
     * provider (`openai`, `google`, `anthropic`) and wins over the display name, because a relay can
     * be named anything.
     */
    fun familyFor(
        providerName: String?,
        customProtocolWireValue: String? = null,
    ): ThinkingProviderFamily {
        customProtocolWireValue?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { protocol ->
            return when (protocol) {
                "anthropic", "claude" -> ThinkingProviderFamily.ANTHROPIC
                "google", "gemini" -> ThinkingProviderFamily.GEMINI
                "openai" -> ThinkingProviderFamily.OPENAI_COMPATIBLE
                else -> ThinkingProviderFamily.OPENAI_COMPATIBLE
            }
        }
        return when (providerName?.trim()?.lowercase()) {
            "anthropic" -> ThinkingProviderFamily.ANTHROPIC
            "openai" -> ThinkingProviderFamily.OPENAI
            "open router", "openrouter" -> ThinkingProviderFamily.OPENROUTER
            "deepseek" -> ThinkingProviderFamily.DEEPSEEK
            "qwen" -> ThinkingProviderFamily.QWEN
            "groq" -> ThinkingProviderFamily.GROQ
            "google", "gemini" -> ThinkingProviderFamily.GEMINI
            "ollama" -> ThinkingProviderFamily.OLLAMA
            "local" -> ThinkingProviderFamily.LOCAL
            else -> ThinkingProviderFamily.OPENAI_COMPATIBLE
        }
    }

    fun forModel(family: ThinkingProviderFamily, modelId: String): ModelThinkingCapability =
        ModelThinkingCapabilityDefaults.forModel(family, modelId)

    fun forProvider(
        providerName: String?,
        modelId: String,
        customProtocolWireValue: String? = null,
    ): ModelThinkingCapability =
        forModel(familyFor(providerName, customProtocolWireValue), modelId)
}