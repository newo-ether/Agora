package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds semantic index state without scanning message or embedding content during migration. */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS semantic_index_ledger (
                modelId TEXT NOT NULL,
                state TEXT NOT NULL,
                sourceRevision INTEGER NOT NULL,
                completedRevision INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(modelId)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS semantic_index_work (
                modelId TEXT NOT NULL,
                messageId TEXT NOT NULL,
                sourceFingerprint TEXT,
                sourceRevision INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(modelId, messageId),
                FOREIGN KEY(modelId) REFERENCES semantic_index_ledger(modelId)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_semantic_index_work_modelId_sourceRevision_messageId
            ON semantic_index_work (modelId, sourceRevision, messageId)
            """.trimIndent(),
        )
    }
}
