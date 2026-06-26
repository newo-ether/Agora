package com.newoether.agora.model

import com.newoether.agora.util.Constants

/**
 * Typed wrapper for model identifiers in "ProviderName:modelId" format.
 *
 * Replaces ad-hoc substringBefore(":")/substringAfter(":") parsing scattered
 * across ~30 call sites with a single canonical parse point.
 */
data class ModelId(
    val providerName: String,
    val modelName: String
) {
    /** "ProviderName:modelId" — the wire format stored in DataStore and Room. */
    val prefixed: String get() = "$providerName:$modelName"

    companion object {
        /**
         * Parse a "ProviderName:modelId" string.  Falls back to heuristics
         * for unprefixed legacy model IDs (e.g. "gpt-4", "claude-3-opus").
         */
        fun parse(prefixed: String): ModelId {
            if (prefixed.contains(":")) {
                val idx = prefixed.indexOf(":")
                return ModelId(prefixed.substring(0, idx), prefixed.substring(idx + 1))
            }
            // Legacy / unprefixed model IDs — match the heuristics in
            // ChatViewModel.getProviderForModel().
            val provider = when {
                prefixed.startsWith("gpt-") || prefixed.startsWith("o1") || prefixed.startsWith("o3") -> Constants.PROVIDER_OPENAI
                prefixed.startsWith("claude-") -> Constants.PROVIDER_ANTHROPIC
                prefixed.contains("deepseek") -> Constants.PROVIDER_DEEPSEEK
                prefixed.contains("qwen") -> Constants.PROVIDER_QWEN
                prefixed.contains("groq") -> Constants.PROVIDER_GROQ
                prefixed.contains("models/") || prefixed.startsWith("gemini") -> Constants.PROVIDER_GOOGLE
                else -> Constants.PROVIDER_UNKNOWN
            }
            return ModelId(provider, prefixed)
        }
    }
}

/**
 * Convenience — the "bare" model name without provider prefix, used for
 * API requests (Google's "models/gemini-2.5-flash" → "gemini-2.5-flash").
 */
val ModelId.apiModelName: String
    get() = modelName.removePrefix("models/")
