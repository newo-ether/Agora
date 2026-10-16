package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the generic corpus document-embedding store that powers semantic `introspect` over the
 * bundled manual. It is isolated from the message `embeddings` table so the two corpora never mix.
 * No existing data is touched; the table starts empty and is populated by startup indexing.
 */
val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS document_embeddings (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                corpus TEXT NOT NULL,
                docId TEXT NOT NULL,
                title TEXT NOT NULL,
                chunkIndex INTEGER NOT NULL,
                modelId TEXT NOT NULL,
                embedding BLOB NOT NULL,
                dimension INTEGER NOT NULL,
                chunkText TEXT NOT NULL,
                sourceFingerprint TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "index_document_embeddings_corpus_docId_chunkIndex_modelId " +
                "ON document_embeddings (corpus, docId, chunkIndex, modelId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_document_embeddings_corpus_modelId " +
                "ON document_embeddings (corpus, modelId)",
        )
    }
}
