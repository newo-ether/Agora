package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** One-time structural index construction; no history rewriting or derived sequence authority. */
val MIGRATION_43_44 = object : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_runId_runSequence ON messages(runId, runSequence)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversationId_id ON messages(conversationId, id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_modelName_id_conversationId ON messages(modelName, id, conversationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_modelId ON conversations(modelId)")
    }
}
