package com.newoether.agora.data.local.migration

import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.data.local.DatabaseConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration19To20Test {
    @Test
    fun migrationRepairsEveryLegacyOversizedPayloadWithoutReadingItIntoCursorWindow() {
        assertEquals(19, MIGRATION_19_20.startVersion)
        assertEquals(20, MIGRATION_19_20.endVersion)

        val statements = oversizedMessageRepairStatements()

        assertEquals(3, statements.size)
        assertTrue(statements[0].sql.contains("SET toolCallJson = NULL"))
        assertTrue(statements[0].sql.contains("length(CAST(toolCallJson AS BLOB))"))
        assertEquals(
            listOf(DatabaseConstants.MAX_PERSISTED_SEGMENTS_BYTES),
            statements[0].bindArgs,
        )
        statements.drop(1).forEach { statement ->
            assertEquals(
                listOf(
                    DatabaseConstants.MAX_PERSISTED_TEXT_CHARS,
                    MessagePersistenceGuard.TRUNCATION_MARKER,
                    DatabaseConstants.MAX_PERSISTED_TEXT_CHARS,
                ),
                statement.bindArgs,
            )
        }
    }
}
