package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val TOKEN_USAGE_COLUMNS = listOf(
    "inputTokenCount",
    "cachedInputTokenCount",
    "uncachedInputTokenCount",
    "outputTokenCount",
    "reasoningTokenCount",
)

val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        TOKEN_USAGE_COLUMNS.forEach { column ->
            db.execSQL("ALTER TABLE messages ADD COLUMN $column INTEGER")
        }
    }
}
