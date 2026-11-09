package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextBudgetTest {
    @Test
    fun legacyMessageWindowsMigrateToUsefulTokenBudgets() {
        assertEquals(20_480, ContextBudget.normalize(20))
        assertEquals(102_400, ContextBudget.normalize(100))
    }

    @Test
    fun tokenBudgetsAreClampedAndNullUsesNewDefault() {
        assertEquals(262_144, ContextBudget.DEFAULT_TOKENS)
        assertEquals(262_144, ContextBudget.normalize(null))
        assertEquals(262_144, ContextBudget.normalize(0))
        assertEquals(262_144, ContextBudget.normalize(-1))
        assertEquals(32_768, ContextBudget.normalize(32_768))
        assertEquals(ContextBudget.MIN_TOKENS, ContextBudget.normalize(1_000))
        assertEquals(ContextBudget.MAX_TOKENS, ContextBudget.normalize(Int.MAX_VALUE))
    }

    @Test
    fun compactLabelsDoNotMigrateLiveLowTokenUsageAsLegacySettings() {
        assertEquals("0", ContextBudget.compactLabel(0))
        assertEquals("64", ContextBudget.compactLabel(64))
        assertEquals("1000", ContextBudget.compactLabel(1_000))
        assertEquals("1K", ContextBudget.compactLabel(1_001))
        assertEquals("1K", ContextBudget.compactLabel(1_023))
        assertEquals("1K", ContextBudget.compactLabel(1_024))
        assertEquals("1.5K", ContextBudget.compactLabel(1_536))
        assertEquals("4K", ContextBudget.compactLabel(4_096))
        assertEquals("256K", ContextBudget.compactLabel(ContextBudget.DEFAULT_TOKENS))
        assertEquals("1M", ContextBudget.compactLabel(1_048_576))
    }
}
