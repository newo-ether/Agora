package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the saved system prompt a task runs with.
 *
 * The existing `systemPrompt` column keeps holding literal text, so tasks created before this
 * version behave exactly as before. A task that points at a saved prompt stores its id instead, so
 * the prompt's placeholders resolve per run and later edits to that prompt apply.
 */
val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN systemPromptId TEXT")
    }
}
