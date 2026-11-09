package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** SQLite column defaults retain all legacy vectors without a rewrite or backfill. */
val MIGRATION_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE embeddings ADD COLUMN generation INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE semantic_index_ledger ADD COLUMN generation INTEGER NOT NULL DEFAULT 0")
    }
}
