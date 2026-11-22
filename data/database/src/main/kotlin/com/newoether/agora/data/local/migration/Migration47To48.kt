package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the request-time usage ledger. History is not backfilled from messages. */
val MIGRATION_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS usage_ledger (
            bucket INTEGER NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL, source TEXT NOT NULL,
            requests INTEGER NOT NULL, totalTokens INTEGER NOT NULL, inputTokens INTEGER NOT NULL,
            cachedInputTokens INTEGER NOT NULL, outputTokens INTEGER NOT NULL,
            cacheReportedInputTokens INTEGER NOT NULL,
            PRIMARY KEY(bucket, provider, model, source))""",
        )
    }
}
