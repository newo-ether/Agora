package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Persists the singleton native-import conversation-settings outbox. */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation_settings_import_transfer (
                id INTEGER NOT NULL,
                transferId TEXT NOT NULL,
                settingsJson TEXT NOT NULL,
                mode TEXT NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
    }
}
