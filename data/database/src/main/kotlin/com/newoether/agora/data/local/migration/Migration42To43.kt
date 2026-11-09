package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** No source/body scan during upgrade. Maintenance backfills identities in resumable pages. */
val MIGRATION_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS attachment_reference_owners (
            kind INTEGER NOT NULL, ownerId TEXT NOT NULL, token TEXT NOT NULL,
            dirty INTEGER NOT NULL, invalid INTEGER NOT NULL, PRIMARY KEY(kind, ownerId))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_attachment_reference_owners_dirty_kind_ownerId ON attachment_reference_owners(dirty, kind, ownerId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_attachment_reference_owners_invalid_kind_ownerId ON attachment_reference_owners(invalid, kind, ownerId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS attachment_references (
            kind INTEGER NOT NULL, ownerId TEXT NOT NULL, path TEXT NOT NULL,
            PRIMARY KEY(kind, ownerId, path),
            FOREIGN KEY(kind, ownerId) REFERENCES attachment_reference_owners(kind, ownerId) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_attachment_references_path ON attachment_references(path)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS attachment_reference_state (
            id INTEGER NOT NULL PRIMARY KEY, phase INTEGER NOT NULL, afterId TEXT, revision INTEGER NOT NULL)""")
        installAttachmentReferenceTriggers(db)
    }
}

/** Invalidates derived references atomically for every writer, including import and cascading deletes. */
internal fun installAttachmentReferenceTriggers(db: SupportSQLiteDatabase) {
    db.execSQL("INSERT OR IGNORE INTO attachment_reference_state VALUES(0, 0, NULL, 0)")
    val tables = listOf(
        "messages" to listOf("images", "attachmentMeta", "toolCallJson"),
        "conversations" to listOf("draftAttachments"),
        "new_chat_persist" to listOf("draftAttachments"),
    )
    tables.forEachIndexed { kind, (table, columns) ->
        val changed = columns.joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }
        // Room's outer UPDATE OR ABORT overrides a trigger's INSERT OR REPLACE policy.
        // Update existing owners, then insert only missing ones: neither statement can conflict.
        val mark = """
            UPDATE attachment_reference_owners
            SET token = lower(hex(randomblob(16))), dirty = 1, invalid = 0
            WHERE kind = $kind AND ownerId = CAST(NEW.id AS TEXT);
            INSERT INTO attachment_reference_owners(kind, ownerId, token, dirty, invalid)
            SELECT $kind, CAST(NEW.id AS TEXT), lower(hex(randomblob(16))), 1, 0
            WHERE NOT EXISTS (SELECT 1 FROM attachment_reference_owners
                WHERE kind = $kind AND ownerId = CAST(NEW.id AS TEXT));
            UPDATE attachment_reference_state SET revision = revision + 1 WHERE id = 0;
        """
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS attachment_refs_${table}_insert
            AFTER INSERT ON $table BEGIN $mark END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS attachment_refs_${table}_update
            AFTER UPDATE OF ${columns.joinToString(",")} ON $table WHEN $changed
            BEGIN $mark END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS attachment_refs_${table}_delete
            AFTER DELETE ON $table BEGIN
            DELETE FROM attachment_reference_owners WHERE kind = $kind AND ownerId = CAST(OLD.id AS TEXT);
            UPDATE attachment_reference_state SET revision = revision + 1 WHERE id = 0;
            END""")
    }
}
