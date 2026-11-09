package com.newoether.agora.data.local.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration21To22Test {
    @Test
    fun migrationAddsPersistedUnreadGenerationFlag() {
        assertEquals(21, MIGRATION_21_22.startVersion)
        assertEquals(22, MIGRATION_21_22.endVersion)
        assertTrue(ADD_UNREAD_GENERATION_COLUMN_SQL.contains("hasUnreadGeneration"))
        assertTrue(ADD_UNREAD_GENERATION_COLUMN_SQL.endsWith("NOT NULL DEFAULT 0"))
    }
}
