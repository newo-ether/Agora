package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the singleton workspace persisted for the New Chat screen before first Send. */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS new_chat_persist (
                id INTEGER NOT NULL,
                modelId TEXT,
                systemPromptId TEXT,
                conversationSettingsJson TEXT,
                draftText TEXT NOT NULL,
                draftAttachments TEXT,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
    }
}
