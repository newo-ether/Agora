package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add only empty derived tables. Legacy text is read later by the resumable metadata worker. */
val MIGRATION_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS semantic_sources (
                messageId TEXT NOT NULL PRIMARY KEY,
                conversationId TEXT NOT NULL,
                eligible INTEGER NOT NULL,
                sourcePrefix BLOB NOT NULL,
                sourceFingerprint TEXT,
                searchLength INTEGER NOT NULL,
                historyEligible INTEGER NOT NULL,
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_semantic_sources_conversationId_messageId ON semantic_sources(conversationId, messageId)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS semantic_conversations (
                conversationId TEXT NOT NULL PRIMARY KEY,
                sourceRevision INTEGER NOT NULL,
                eligibleCount INTEGER NOT NULL,
                cacheEligible INTEGER NOT NULL,
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS semantic_model_conversations (
                modelId TEXT NOT NULL,
                conversationId TEXT NOT NULL,
                cachedCount INTEGER NOT NULL,
                indexedRevision INTEGER NOT NULL,
                PRIMARY KEY(modelId, conversationId),
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_semantic_model_conversations_conversationId ON semantic_model_conversations(conversationId)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS semantic_metadata_state (
                id INTEGER NOT NULL PRIMARY KEY,
                complete INTEGER NOT NULL,
                afterMessageId TEXT
            )
        """.trimIndent())
        db.execSQL("INSERT INTO semantic_metadata_state(id, complete, afterMessageId) VALUES(0, 0, NULL)")
        db.execSQL("UPDATE semantic_index_ledger SET state = 'NEEDS_RECONCILE', sourceRevision = sourceRevision + 1, reconcileRevision = sourceRevision + 1")
    }
}
