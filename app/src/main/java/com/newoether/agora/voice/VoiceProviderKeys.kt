package com.newoether.agora.voice

import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.util.Constants

internal fun VoiceTypingMode.keyProvider(): String? = when (this) {
    VoiceTypingMode.OPENAI -> Constants.PROVIDER_OPENAI
    VoiceTypingMode.GROQ -> Constants.PROVIDER_GROQ
    else -> null
}

internal fun voiceKeyCandidates(config: VoiceTypingConfig, keys: List<ApiKeyEntry>): List<ApiKeyEntry> =
    config.mode.keyProvider()?.let { provider -> keys.filter { it.provider == provider } } ?: keys

internal fun voiceProviderKey(config: VoiceTypingConfig, keys: List<ApiKeyEntry>, activeIds: Map<String, String>): String {
    val candidates = voiceKeyCandidates(config, keys)
    if (config.apiKeyId != null) return candidates.firstOrNull { it.id == config.apiKeyId }?.key.orEmpty()
    val provider = config.mode.keyProvider() ?: return "" // Custom endpoints may be unauthenticated.
    return candidates.firstOrNull { it.id == activeIds[provider] }?.key ?: candidates.firstOrNull()?.key.orEmpty()
}
