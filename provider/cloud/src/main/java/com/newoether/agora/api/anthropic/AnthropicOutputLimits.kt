package com.newoether.agora.api.anthropic

/**
 * Documented output ceilings (`max_tokens`) of Claude models.
 *
 * The default `max_tokens` is lowered to these values, because the API rejects a request whose
 * `max_tokens` exceeds the model's ceiling (Claude 3 accepts at most 4096, Claude 3.5 8192).
 *
 * Values come from each model's page on platform.claude.com ("Max output"); the pre-4.5 entries
 * come from the models overview as it stood when those models were current.
 */
internal object AnthropicOutputLimits {
    /** Ordered so that a longer, more specific prefix always wins over a shorter one. */
    private val ceilings: List<Pair<String, Int>> = listOf(
        "claude-fable-5" to 128_000,
        "claude-mythos-5" to 128_000,
        "claude-opus-5" to 128_000,
        "claude-sonnet-5" to 128_000,
        "claude-opus-4-8" to 128_000,
        "claude-opus-4-7" to 128_000,
        "claude-opus-4-6" to 128_000,
        "claude-sonnet-4-6" to 128_000,
        "claude-opus-4-5" to 64_000,
        "claude-sonnet-4-5" to 64_000,
        "claude-haiku-4-5" to 64_000,
        "claude-opus-4-1" to 32_000,
        "claude-opus-4" to 32_000,
        "claude-sonnet-4" to 64_000,
        "claude-3-7-sonnet" to 64_000,
        "claude-3-5-sonnet" to 8_192,
        "claude-3-5-haiku" to 8_192,
        "claude-3-opus" to 4_096,
        "claude-3-sonnet" to 4_096,
        "claude-3-haiku" to 4_096,
    )

    /**
     * The documented ceiling for [modelId], or null when Agora has no documentation for it.
     *
     * Relay prefixes (`anthropic/…`) and dotted versions (`claude-opus-4.5`) are normalised first;
     * a prefix only matches at a version boundary, so `claude-opus-4` never claims `claude-opus-45`.
     */
    fun maxOutputTokens(modelId: String): Int? {
        val model = modelId.trim().lowercase().substringAfterLast('/').replace('.', '-')
        return ceilings.firstOrNull { (prefix, _) ->
            model == prefix || model.startsWith("$prefix-") || model.startsWith("${prefix}@")
        }?.second
    }
}
