package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Replace conflicting trigger bodies without scanning or changing user or reference data. */
val MIGRATION_45_46 = object : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in listOf("messages", "conversations", "new_chat_persist")) {
            for (event in listOf("insert", "update", "delete")) {
                db.execSQL("DROP TRIGGER IF EXISTS attachment_refs_${table}_$event")
            }
        }
        installAttachmentReferenceTriggers(db)
    }
}
