package com.newoether.agora.data.local.migration

import org.junit.Assert.assertEquals
import org.junit.Test

class Migration20To21Test {
    @Test
    fun migrationAddsEveryNullableTokenUsageColumn() {
        assertEquals(20, MIGRATION_20_21.startVersion)
        assertEquals(21, MIGRATION_20_21.endVersion)
        assertEquals(
            listOf(
                "inputTokenCount",
                "cachedInputTokenCount",
                "uncachedInputTokenCount",
                "outputTokenCount",
                "reasoningTokenCount",
            ),
            TOKEN_USAGE_COLUMNS,
        )
    }
}
