package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object MIGRATION_31_32 : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Heartbeat logs table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS heartbeat_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestampEpochMs INTEGER NOT NULL,
                success INTEGER NOT NULL,
                error TEXT
            )
        """)

        // Heartbeat config table (singleton)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS heartbeat_config (
                id INTEGER PRIMARY KEY NOT NULL,
                enabled INTEGER NOT NULL,
                intervalMinutes INTEGER NOT NULL,
                activeHoursStart INTEGER NOT NULL,
                activeHoursEnd INTEGER NOT NULL,
                lastHeartbeatEpochMs INTEGER NOT NULL,
                heartbeatInstanceId TEXT
            )
        """)

        // SMS messages table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_messages (
                id INTEGER PRIMARY KEY NOT NULL,
                address TEXT NOT NULL,
                date INTEGER NOT NULL,
                preview TEXT NOT NULL,
                body TEXT NOT NULL,
                read INTEGER NOT NULL
            )
        """)

        // SMS sync state table (singleton)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_sync_state (
                id INTEGER PRIMARY KEY NOT NULL,
                lastSeenId INTEGER NOT NULL,
                lastSyncEpochMs INTEGER NOT NULL,
                lastAttemptEpochMs INTEGER NOT NULL,
                unreadCount INTEGER NOT NULL,
                lastError TEXT
            )
        """)

        // SMS drafts table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_drafts (
                id TEXT PRIMARY KEY NOT NULL,
                address TEXT NOT NULL,
                body TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                inReplyToSmsId INTEGER,
                status TEXT NOT NULL,
                lastError TEXT
            )
        """)

        // Initialize default heartbeat config row
        db.execSQL("""
            INSERT OR IGNORE INTO heartbeat_config (id, enabled, intervalMinutes, activeHoursStart, activeHoursEnd, lastHeartbeatEpochMs, heartbeatInstanceId)
            VALUES (0, 1, 30, 8, 22, 0, NULL)
        """)

        // Initialize default SMS sync state row
        db.execSQL("""
            INSERT OR IGNORE INTO sms_sync_state (id, lastSeenId, lastSyncEpochMs, lastAttemptEpochMs, unreadCount, lastError)
            VALUES (0, 0, 0, 0, 0, NULL)
        """)
    }
}