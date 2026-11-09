package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.newoether.agora.model.Participant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * v18 is a data-shape correction; the Room table schema is unchanged.
 *
 * v17 persisted Regenerate exactly like Edit by cloning the source user message. Repair every
 * inferable repeated-input branch so the existing shared user owns assistant siblings, while
 * leaving different-input Edit branches untouched.
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query(
            "SELECT id, selectedBranchesJson, selectedRunBranchesJson FROM conversations"
        ).use { conversations ->
            val idIndex = conversations.getColumnIndexOrThrow("id")
            val messageSelectionsIndex =
                conversations.getColumnIndexOrThrow("selectedBranchesJson")
            val runSelectionsIndex =
                conversations.getColumnIndexOrThrow("selectedRunBranchesJson")

            while (conversations.moveToNext()) {
                val conversationId = conversations.getString(idIndex)
                val messageSelections = decodeSelections(
                    conversations.nullableString(messageSelectionsIndex)
                )
                val runSelections = decodeSelections(
                    conversations.nullableString(runSelectionsIndex)
                )
                val plan = RegenerationTreeRepairPlanner.plan(
                    runs = readRuns(db, conversationId),
                    messages = readMessages(db, conversationId),
                    messageSelections = messageSelections,
                    runSelections = runSelections,
                )
                if (plan.inferredRunIds.isEmpty()) continue

                for ((runId, parentRunId) in plan.runParentUpdates) {
                    db.execSQL(
                        "UPDATE runs SET parentRunId = ?, legacyAmbiguous = 1 WHERE id = ?",
                        arrayOf(parentRunId, runId),
                    )
                }
                for ((messageId, parentId) in plan.messageParentUpdates) {
                    db.execSQL(
                        "UPDATE messages SET parentId = ? WHERE id = ?",
                        arrayOf(parentId, messageId),
                    )
                }
                for (messageId in plan.deletedMessageIds) {
                    db.execSQL(
                        "DELETE FROM embeddings WHERE messageId = ?",
                        arrayOf(messageId),
                    )
                    db.execSQL(
                        "DELETE FROM messages WHERE id = ?",
                        arrayOf(messageId),
                    )
                }
                for ((messageId, runSequence) in plan.runSequenceUpdates) {
                    db.execSQL(
                        "UPDATE messages SET runSequence = ? WHERE id = ?",
                        arrayOf<Any?>(runSequence, messageId),
                    )
                }
                db.execSQL(
                    """
                    UPDATE conversations
                    SET selectedBranchesJson = ?, selectedRunBranchesJson = ?
                    WHERE id = ?
                    """.trimIndent(),
                    arrayOf(
                        encodeSelections(plan.messageSelections),
                        encodeSelections(plan.runSelections),
                        conversationId,
                    ),
                )
            }
        }
    }
}

private fun readRuns(
    db: SupportSQLiteDatabase,
    conversationId: String,
): List<V17RunRecord> = db.query(
    """
    SELECT id, parentRunId, startedAt
    FROM runs
    WHERE conversationId = ?
    """.trimIndent(),
    arrayOf(conversationId),
).use { cursor ->
    val idIndex = cursor.getColumnIndexOrThrow("id")
    val parentIndex = cursor.getColumnIndexOrThrow("parentRunId")
    val startedAtIndex = cursor.getColumnIndexOrThrow("startedAt")
    buildList {
        while (cursor.moveToNext()) {
            add(
                V17RunRecord(
                    id = cursor.getString(idIndex),
                    parentRunId = cursor.nullableString(parentIndex),
                    startedAt = cursor.getLong(startedAtIndex),
                )
            )
        }
    }
}

private fun readMessages(
    db: SupportSQLiteDatabase,
    conversationId: String,
): List<V17MessageRecord> = db.query(
    """
    SELECT id, parentId, text, images, attachmentMeta, participant, timestamp, runId, runSequence
    FROM messages
    WHERE conversationId = ?
    """.trimIndent(),
    arrayOf(conversationId),
).use { cursor ->
    val idIndex = cursor.getColumnIndexOrThrow("id")
    val parentIndex = cursor.getColumnIndexOrThrow("parentId")
    val textIndex = cursor.getColumnIndexOrThrow("text")
    val imagesIndex = cursor.getColumnIndexOrThrow("images")
    val attachmentMetaIndex = cursor.getColumnIndexOrThrow("attachmentMeta")
    val participantIndex = cursor.getColumnIndexOrThrow("participant")
    val timestampIndex = cursor.getColumnIndexOrThrow("timestamp")
    val runIdIndex = cursor.getColumnIndexOrThrow("runId")
    val runSequenceIndex = cursor.getColumnIndexOrThrow("runSequence")
    buildList {
        while (cursor.moveToNext()) {
            val images = cursor.getString(imagesIndex)
            val attachmentMeta = cursor.nullableString(attachmentMetaIndex)
            add(
                V17MessageRecord(
                    id = cursor.getString(idIndex),
                    parentId = cursor.nullableString(parentIndex),
                    participant = runCatching {
                        Participant.valueOf(cursor.getString(participantIndex))
                    }.getOrDefault(Participant.ERROR),
                    timestamp = cursor.getLong(timestampIndex),
                    runId = cursor.getString(runIdIndex),
                    runSequence = cursor.getLong(runSequenceIndex),
                    inputFingerprint = inputFingerprint(
                        text = cursor.getString(textIndex),
                        images = images,
                        attachmentMeta = attachmentMeta,
                    ),
                )
            )
        }
    }
}

private fun inputFingerprint(
    text: String,
    images: String,
    attachmentMeta: String?,
): String = regenerationInputFingerprint(text, imageCount(images), attachmentMeta)

private fun imageCount(raw: String): Int {
    if (raw.isEmpty()) return 0
    return runCatching { Json.decodeFromString<List<String>>(raw).size }
        .getOrElse { raw.split("|||").size }
}

private fun decodeSelections(raw: String?): Map<String?, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        Json.decodeFromString<Map<String, String>>(raw)
            .mapKeys { if (it.key == "null") null else it.key }
    }.getOrDefault(emptyMap())
}

private fun encodeSelections(selections: Map<String?, String>): String =
    Json.encodeToString(selections.mapKeys { it.key ?: "null" })

private fun android.database.Cursor.nullableString(index: Int): String? =
    if (isNull(index)) null else getString(index)
