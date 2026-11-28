package com.newoether.agora.data.local.migration
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
/**
 * Adds the structured source of automatic user messages (Task, Loop, non-blocking ask_user).
 *
 * Existing rows keep a null source: they display and send exactly as before, with no backfill.
 */
val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN sourceJson TEXT")
    }
}
