package com.newoether.agora.model

/** Shared storage/UI bounds for the provider-visible conversation token budget. */
object ContextBudget {
    const val DEFAULT_TOKENS = 262_144
    const val MIN_TOKENS = 4_096
    const val MAX_TOKENS = 1_048_576

    val PRESETS = intArrayOf(
        4_096,
        8_192,
        16_384,
        32_768,
        65_536,
        131_072,
        262_144,
        524_288,
        1_048_576,
    )

    /**
     * Values up to 100 are legacy logical-message windows from pre-token-budget builds. Convert
     * them once at read/use boundaries so old settings remain useful instead of becoming a
     * nonsensical 20-token context.
     */
    fun normalize(value: Int?): Int {
        if (value == null || value <= 0) return DEFAULT_TOKENS
        val migrated = if (value <= 100) value * 1_024 else value
        return migrated.coerceIn(MIN_TOKENS, MAX_TOKENS)
    }

    fun compactLabel(tokens: Int): String {
        // This formats both configured budgets and live occupancy. Live estimates can legally be
        // below 101 tokens, so applying legacy-setting migration here would turn an empty context
        // into "32K" and a 64-token context into "64K".
        val normalized = tokens.coerceAtLeast(0)
        return when {
            normalized >= 1_048_576 -> compactUnitLabel(normalized, 1_048_576, "M")
            normalized > 1_000 -> compactUnitLabel(normalized, 1_024, "K")
            else -> normalized.toString()
        }
    }

    private fun compactUnitLabel(tokens: Int, unit: Int, suffix: String): String {
        val tenths = ((tokens.toLong() * 10L + unit / 2L) / unit).toInt()
        return if (tenths % 10 == 0) {
            "${tenths / 10}$suffix"
        } else {
            "${tenths / 10}.${tenths % 10}$suffix"
        }
    }
}
