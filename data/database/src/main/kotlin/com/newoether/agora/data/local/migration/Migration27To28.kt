package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds revision-fenced maintenance debt without scanning application tables during migration. */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS maintenance_debt (
                kind TEXT NOT NULL,
                identity TEXT NOT NULL,
                state TEXT NOT NULL,
                revision INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                claimId TEXT,
                claimedAt INTEGER,
                PRIMARY KEY(kind, identity)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_maintenance_debt_state_updatedAt_kind_identity
            ON maintenance_debt (state, updatedAt, kind, identity)
            """.trimIndent(),
        )
        listOf(
            "ATTACHMENT_ORPHANS",
            "EMBEDDING_ORPHANS",
            "RUN_BRANCHES",
        ).forEach { kind ->
            db.execSQL(
                """
                INSERT OR IGNORE INTO maintenance_debt (
                    kind, identity, state, revision, updatedAt, claimId, claimedAt
                ) VALUES ('$kind', '*', 'PENDING', 1, 0, NULL, NULL)
                """.trimIndent(),
            )
        }
    }
}
