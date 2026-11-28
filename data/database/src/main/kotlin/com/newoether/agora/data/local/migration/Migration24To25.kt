package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the Room outbox used to complete New Chat generation-setting transfers. */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation_settings_transfer (
                conversationId TEXT NOT NULL,
                settingsJson TEXT,
                PRIMARY KEY(conversationId),
                FOREIGN KEY(conversationId) REFERENCES conversations(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }
}
