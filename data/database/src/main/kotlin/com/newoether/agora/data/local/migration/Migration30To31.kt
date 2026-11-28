package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Covers attachment reference scans without fetching the full message payload rows. */
val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX index_messages_id_images_attachmentMeta ON messages(id, images, attachmentMeta)",
        )
    }
}
