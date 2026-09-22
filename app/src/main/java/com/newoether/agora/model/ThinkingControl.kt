package com.newoether.agora.model

object ThinkingLevels {
    const val DefaultEffort = "medium"
    const val DefaultBudgetTokens = 4096

    val effortValues = listOf("minimal", "low", "medium", "high", "xhigh", "max")
    val budgetPresets = listOf(1024, 2048, 4096, 8192, 16384, 32768)

    fun effortRangeForProvider(providerName: String?): IntRange = when (providerName?.lowercase()) {
        "openai", "open router" -> 0..4   // Max clamps to xHigh
        "google", "gemini" -> 0..3         // xHigh/Max clamp to High
        else -> 0..5                        // full range (Anthropic, unknown)
    }

    fun effortForIndex(index: Int): String = effortValues.getOrElse(index) { "medium" }

    fun indexForEffort(effort: String): Int =
        effortValues.indexOf(normalizeEffort(effort)).coerceAtLeast(0)

    fun normalize(raw: String?): String = normalizeEffortValue(raw)

    fun normalizeEffortValue(raw: String?): String {
        val value = raw?.trim()?.lowercase().orEmpty()
        return when {
            value.isBlank() -> DefaultEffort
            // Legacy compat: old budget mode now migrates into independent budget fields.
            value == "auto" || value == "budget:dynamic" || value.startsWith("budget:") -> DefaultEffort
            else -> normalizeEffort(value)
        }
    }

    fun legacyBudgetTokens(raw: String?): Int? {
        val value = raw?.trim()?.lowercase().orEmpty()
        if (!value.startsWith("budget:")) return null
        return value.substringAfter("budget:").toIntOrNull()?.takeIf { it > 0 }
    }

    fun openAiEffort(effort: String): String = when (normalizeEffort(effort)) {
        "none", "minimal", "low", "medium", "high", "xhigh" -> normalizeEffort(effort)
        "max" -> "xhigh"
        else -> "medium"
    }

    fun openRouterEffort(effort: String): String = when (normalizeEffort(effort)) {
        "none", "minimal", "low", "medium", "high", "xhigh" -> normalizeEffort(effort)
        "max" -> "xhigh"
        else -> "medium"
    }

    fun requestyEffort(effort: String): String = when (normalizeEffort(effort)) {
        "none", "low", "medium", "high", "xhigh", "max" -> normalizeEffort(effort)
        "minimal" -> "min"
        else -> "medium"
    }

    fun anthropicEffort(effort: String): String = when (normalizeEffort(effort)) {
        "none", "minimal" -> "low"
        "low", "medium", "high", "xhigh", "max" -> normalizeEffort(effort)
        else -> "medium"
    }

    fun geminiLevel(effort: String): String = when (normalizeEffort(effort)) {
        "none", "minimal" -> "minimal"
        "low" -> "low"
        "medium" -> "medium"
        "high", "xhigh", "max" -> "high"
        else -> "medium"
    }

    private fun normalizeEffort(raw: String): String = when (raw.trim().lowercase()) {
        "none" -> "none"
        "minimal", "min" -> "minimal"
        "low", "quick", "fast" -> "low"
        "medium", "balanced", "balance" -> "medium"
        "high", "deep" -> "high"
        "xhigh", "x-high", "extra-high", "extreme" -> "xhigh"
        "max", "maximum" -> "max"
        else -> "medium"
    }
}
