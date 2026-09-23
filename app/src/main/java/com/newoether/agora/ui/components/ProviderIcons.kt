package com.newoether.agora.ui.components

import com.newoether.agora.R

/**
 * Single source of truth mapping a built-in provider name to its brand icon drawable.
 * Returns 0 for unknown / custom providers (callers fall back to a generic Cloud icon).
 */
fun providerIcon(name: String): Int = when (name.lowercase()) {
    "google" -> R.drawable.provider_google
    "openai" -> R.drawable.provider_openai
    "anthropic" -> R.drawable.provider_anthropic
    "deepseek" -> R.drawable.provider_deepseek
    "qwen" -> R.drawable.provider_qwen
    "groq" -> R.drawable.provider_groq
    "ollama" -> R.drawable.provider_ollama
    "open router" -> R.drawable.provider_openrouter
    "opencode go" -> R.drawable.provider_opencode
    "requesty" -> R.drawable.provider_requesty
    else -> 0
}
