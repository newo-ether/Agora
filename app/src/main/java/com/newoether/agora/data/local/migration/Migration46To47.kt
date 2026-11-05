package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Keeps the measured first-token wait and whole-request time beside the generation speed. */
val MIGRATION_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN timeToFirstTokenMs INTEGER")
        db.execSQL("ALTER TABLE messages ADD COLUMN totalGenerationMs INTEGER")
    }
}
