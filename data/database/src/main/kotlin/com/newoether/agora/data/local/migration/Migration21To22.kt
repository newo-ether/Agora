package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val ADD_UNREAD_GENERATION_COLUMN_SQL =
    "ALTER TABLE conversations ADD COLUMN hasUnreadGeneration INTEGER NOT NULL DEFAULT 0"

val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(ADD_UNREAD_GENERATION_COLUMN_SQL)
    }
}
