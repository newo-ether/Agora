package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Keeps the measured first-token wait and whole-request time beside the generation speed.
 *
 * The columns are added only when absent: an intermediate local build shipped these two columns as
 * part of a v48 schema that upstream later used for the usage ledger, so a device that installed it
 * arrives here with the columns already present and a plain ALTER would abort the upgrade with
 * "duplicate column name".
 */
val MIGRATION_49_50 = object : Migration(49, 50) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumnIfMissing("messages", "timeToFirstTokenMs")
        db.addColumnIfMissing("messages", "totalGenerationMs")
    }
}

internal fun SupportSQLiteDatabase.addColumnIfMissing(table: String, column: String) {
    val present = query("PRAGMA table_info($table)").use { cursor ->
        var found = false
        while (cursor.moveToNext()) if (cursor.getString(1) == column) found = true
        found
    }
    if (!present) execSQL("ALTER TABLE $table ADD COLUMN $column INTEGER")
}
