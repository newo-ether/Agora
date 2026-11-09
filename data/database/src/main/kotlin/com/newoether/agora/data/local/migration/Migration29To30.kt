package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Separates full-reconcile generations from exact source revisions and records the canonical
 * source fingerprint on newly generated embeddings. Existing payload blobs are preserved.
 */
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE semantic_index_ledger " +
                "ADD COLUMN reconcileRevision INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            """
            UPDATE semantic_index_ledger
            SET reconcileRevision = sourceRevision
            WHERE state = 'NEEDS_RECONCILE'
            """.trimIndent(),
        )
        db.execSQL("ALTER TABLE embeddings ADD COLUMN sourceFingerprint TEXT")
    }
}
