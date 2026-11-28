package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN selectedRunBranchesJson TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS runs (
                id TEXT PRIMARY KEY NOT NULL,
                conversationId TEXT NOT NULL,
                parentRunId TEXT,
                status TEXT NOT NULL,
                activeSlot INTEGER,
                startedAt INTEGER NOT NULL,
                lastCheckpointAt INTEGER NOT NULL,
                stopRequestedAt INTEGER,
                endedAt INTEGER,
                endReason TEXT,
                currentPass INTEGER NOT NULL,
                legacyAmbiguous INTEGER NOT NULL,
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(parentRunId) REFERENCES runs(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_runs_conversationId ON runs (conversationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_runs_parentRunId ON runs (parentRunId)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_runs_conversationId_activeSlot " +
                "ON runs (conversationId, activeSlot)"
        )

        db.execSQL("ALTER TABLE messages ADD COLUMN runId TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN runSequence INTEGER")
        db.execSQL("ALTER TABLE messages ADD COLUMN consumedAtPass INTEGER")

        db.query("SELECT id, selectedBranchesJson FROM conversations").use { conversations ->
            val idIndex = conversations.getColumnIndexOrThrow("id")
            val selectedIndex = conversations.getColumnIndexOrThrow("selectedBranchesJson")
            while (conversations.moveToNext()) {
                val conversationId = conversations.getString(idIndex)
                val messages = readLegacyMessages(db, conversationId)
                val plan = LegacyRunBackfillPlanner.plan(conversationId, messages)

                for (run in plan.runs) {
                    db.execSQL(
                        """
                        INSERT INTO runs (
                            id, conversationId, parentRunId, status, activeSlot, startedAt,
                            lastCheckpointAt, stopRequestedAt, endedAt, endReason, currentPass,
                            legacyAmbiguous
                        ) VALUES (?, ?, ?, ?, NULL, ?, ?, NULL, ?, ?, 0, ?)
                        """.trimIndent(),
                        arrayOf<Any?>(
                            run.id,
                            run.conversationId,
                            run.parentRunId,
                            run.status.name,
                            run.startedAt,
                            run.endedAt,
                            run.endedAt,
                            run.endReason.name,
                            if (run.legacyAmbiguous) 1 else 0,
                        )
                    )
                }
                for (assignment in plan.assignments) {
                    db.execSQL(
                        """
                        UPDATE messages
                        SET runId = ?, runSequence = ?, consumedAtPass = ?
                        WHERE id = ?
                        """.trimIndent(),
                        arrayOf<Any?>(
                            assignment.runId,
                            assignment.runSequence,
                            assignment.consumedAtPass,
                            assignment.messageId,
                        )
                    )
                }

                val selectedMessages = decodeSelections(
                    if (conversations.isNull(selectedIndex)) null
                    else conversations.getString(selectedIndex)
                )
                val selectedRuns = LegacyRunBackfillPlanner.selectedRunBranches(
                    messages = messages,
                    plan = plan,
                    selectedMessageBranches = selectedMessages,
                )
                if (selectedRuns.isNotEmpty()) {
                    val stored = selectedRuns.mapKeys { it.key ?: "null" }
                    db.execSQL(
                        "UPDATE conversations SET selectedRunBranchesJson = ? WHERE id = ?",
                        arrayOf(Json.encodeToString(stored), conversationId),
                    )
                }
            }
        }

        val missingAssignments = db.query(
            "SELECT COUNT(*) FROM messages WHERE runId IS NULL OR runSequence IS NULL"
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        check(missingAssignments == 0L) {
            "v16-to-v17 migration left $missingAssignments messages without a Run"
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages_new (
                id TEXT PRIMARY KEY NOT NULL,
                conversationId TEXT NOT NULL,
                parentId TEXT,
                text TEXT NOT NULL,
                images TEXT NOT NULL,
                thoughts TEXT,
                thoughtTitle TEXT,
                tokenCount INTEGER NOT NULL,
                status TEXT NOT NULL,
                participant TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                thoughtTimeMs INTEGER,
                modelName TEXT,
                toolCallJson TEXT,
                attachmentMeta TEXT,
                runId TEXT NOT NULL,
                runSequence INTEGER NOT NULL,
                consumedAtPass INTEGER,
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(runId) REFERENCES runs(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO messages_new (
                id, conversationId, parentId, text, images, thoughts, thoughtTitle, tokenCount,
                status, participant, timestamp, thoughtTimeMs, modelName, toolCallJson,
                attachmentMeta, runId, runSequence, consumedAtPass
            )
            SELECT
                id, conversationId, parentId, text, images, thoughts, thoughtTitle, tokenCount,
                status, participant, timestamp, thoughtTimeMs, modelName, toolCallJson,
                attachmentMeta, runId, runSequence, consumedAtPass
            FROM messages
            """.trimIndent()
        )
        db.execSQL("DROP TABLE messages")
        db.execSQL("ALTER TABLE messages_new RENAME TO messages")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_messages_conversationId ON messages (conversationId)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_runId ON messages (runId)")
    }
}

private fun readLegacyMessages(
    db: SupportSQLiteDatabase,
    conversationId: String,
): List<LegacyMessageRecord> = db.query(
    """
    SELECT id, parentId, participant, status, timestamp
    FROM messages
    WHERE conversationId = ?
    """.trimIndent(),
    arrayOf(conversationId),
).use { cursor ->
    val idIndex = cursor.getColumnIndexOrThrow("id")
    val parentIndex = cursor.getColumnIndexOrThrow("parentId")
    val participantIndex = cursor.getColumnIndexOrThrow("participant")
    val statusIndex = cursor.getColumnIndexOrThrow("status")
    val timestampIndex = cursor.getColumnIndexOrThrow("timestamp")
    buildList {
        while (cursor.moveToNext()) {
            add(
                LegacyMessageRecord(
                    id = cursor.getString(idIndex),
                    parentId = if (cursor.isNull(parentIndex)) null else cursor.getString(parentIndex),
                    participant = runCatching {
                        Participant.valueOf(cursor.getString(participantIndex))
                    }.getOrDefault(Participant.ERROR),
                    status = runCatching {
                        MessageStatus.valueOf(cursor.getString(statusIndex))
                    }.getOrDefault(MessageStatus.ERROR),
                    timestamp = cursor.getLong(timestampIndex),
                )
            )
        }
    }
}

private fun decodeSelections(raw: String?): Map<String?, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        Json.decodeFromString<Map<String, String>>(raw)
            .mapKeys { if (it.key == "null") null else it.key }
    }.getOrDefault(emptyMap())
}
