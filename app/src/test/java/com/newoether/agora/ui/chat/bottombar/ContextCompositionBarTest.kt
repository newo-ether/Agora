package com.newoether.agora.ui.chat.bottombar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reserved part of the composition bar must describe the same boundary the warning state uses,
 * otherwise the bar would show free budget the automatic compaction has already claimed.
 */
class ContextCompositionBarTest {

    @Test
    fun `reserved tokens are the budget above the compact threshold`() {
        assertEquals(20_000, contextReservedTokens(tokenBudget = 100_000, thresholdPercent = 80))
        assertEquals(10_000, contextReservedTokens(tokenBudget = 100_000, thresholdPercent = 90))
        assertEquals(0, contextReservedTokens(tokenBudget = 100_000, thresholdPercent = 100))
    }

    @Test
    fun `reserved tokens and the warning state flip at the same token`() {
        val budget = 512_000
        val percent = 80
        val reserved = contextReservedTokens(budget, percent)
        val threshold = budget - reserved
        assertEquals(
            false,
            contextUsageExceedsCompactThreshold(threshold, budget, percent),
        )
        assertEquals(
            true,
            contextUsageExceedsCompactThreshold(threshold + 1, budget, percent),
        )
    }

    @Test
    fun `threshold percentages outside the supported range are clamped like the warning state`() {
        // 50 is the lowest threshold the settings allow, so anything below it reserves half.
        assertEquals(50_000, contextReservedTokens(tokenBudget = 100_000, thresholdPercent = 10))
        assertEquals(0, contextReservedTokens(tokenBudget = 100_000, thresholdPercent = 140))
        assertEquals(0, contextReservedTokens(tokenBudget = 0, thresholdPercent = 80))
    }
}
