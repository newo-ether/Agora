package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.data.local.DatabaseConstants

internal data class OversizedMessageRepairStatement(
    val sql: String,
    val bindArgs: List<Any?>,
)

/**
 * SQL-only repair plan for message rows already poisoned before the persistence guard existed.
 *
 * The statements run inside Room's migration transaction and never materialize the oversized
 * values through CursorWindow. Oversized segment JSON cannot be truncated bytewise without making
 * it invalid JSON, so it is cleared; the independently stored answer/reasoning text is retained.
 */
internal fun oversizedMessageRepairStatements(): List<OversizedMessageRepairStatement> = listOf(
    OversizedMessageRepairStatement(
        sql = """
            UPDATE messages
            SET toolCallJson = NULL
            WHERE toolCallJson IS NOT NULL
              AND length(CAST(toolCallJson AS BLOB)) > ?
        """.trimIndent(),
        bindArgs = listOf(DatabaseConstants.MAX_PERSISTED_SEGMENTS_BYTES),
    ),
    OversizedMessageRepairStatement(
        sql = """
            UPDATE messages
            SET text = substr(text, 1, ?) || ?
            WHERE length(text) > ?
        """.trimIndent(),
        bindArgs = listOf(
            DatabaseConstants.MAX_PERSISTED_TEXT_CHARS,
            MessagePersistenceGuard.TRUNCATION_MARKER,
            DatabaseConstants.MAX_PERSISTED_TEXT_CHARS,
        ),
    ),
    OversizedMessageRepairStatement(
        sql = """
            UPDATE messages
            SET thoughts = substr(thoughts, 1, ?) || ?
            WHERE thoughts IS NOT NULL
              AND length(thoughts) > ?
        """.trimIndent(),
        bindArgs = listOf(
            DatabaseConstants.MAX_PERSISTED_TEXT_CHARS,
            MessagePersistenceGuard.TRUNCATION_MARKER,
            DatabaseConstants.MAX_PERSISTED_TEXT_CHARS,
        ),
    ),
)

val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        oversizedMessageRepairStatements().forEach { statement ->
            db.execSQL(statement.sql, statement.bindArgs.toTypedArray())
        }
    }
}
