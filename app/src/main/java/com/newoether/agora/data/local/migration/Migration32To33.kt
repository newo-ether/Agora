package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object MIGRATION_32_33 : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Notifications table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS notifications (
                id TEXT PRIMARY KEY NOT NULL,
                package_name TEXT NOT NULL,
                app_label TEXT NOT NULL,
                title TEXT NOT NULL,
                text TEXT NOT NULL,
                subtext TEXT,
                posted_at INTEGER NOT NULL,
                category TEXT,
                preview TEXT NOT NULL
            )
        """)

        // Indexes for notifications
        db.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_package_name ON notifications (package_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_posted_at ON notifications (posted_at)")

        // Notification sync state table (singleton)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS notification_sync_state (
                id INTEGER PRIMARY KEY NOT NULL,
                lastSeenKey TEXT,
                pendingKeysJson TEXT NOT NULL,
                recordsCount INTEGER NOT NULL,
                lastSyncEpochMs INTEGER NOT NULL,
                lastAttemptEpochMs INTEGER NOT NULL,
                lastError TEXT
            )
        """)

        // Initialize default notification sync state row
        db.execSQL("""
            INSERT OR IGNORE INTO notification_sync_state (id, lastSeenKey, pendingKeysJson, recordsCount, lastSyncEpochMs, lastAttemptEpochMs, lastError)
            VALUES (0, NULL, '[]', 0, 0, 0, NULL)
        """)
    }
}