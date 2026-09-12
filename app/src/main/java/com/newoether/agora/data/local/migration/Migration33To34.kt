package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object MIGRATION_33_34 : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // SMS pending queue: polled-but-not-yet-heartbeat-picked-up messages.
        // Mirrors the pending-queue semantics of the email/notification features:
        // a snapshot is shown to the AI during the heartbeat and removed after success.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_pending (
                id INTEGER PRIMARY KEY NOT NULL,
                address TEXT NOT NULL,
                date INTEGER NOT NULL,
                preview TEXT NOT NULL,
                read INTEGER NOT NULL
            )
        """)
    }
}