package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Schema-only upgrade. Existing payloads are parsed by resumable reference maintenance. */
val MIGRATION_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS conversation_attachment_directories (
            conversationId TEXT NOT NULL, directory TEXT NOT NULL,
            PRIMARY KEY(conversationId, directory),
            FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_attachment_directories_directory ON conversation_attachment_directories(directory)")
        // Completed v46 owners did not index sandboxPath. Drop only the derived cache and
        // restart identity seeding; no message/draft payload is read during migration.
        db.execSQL("DELETE FROM attachment_reference_owners")
        db.execSQL("UPDATE attachment_reference_state SET phase = 0, afterId = NULL, revision = revision + 1 WHERE id = 0")
    }
}
