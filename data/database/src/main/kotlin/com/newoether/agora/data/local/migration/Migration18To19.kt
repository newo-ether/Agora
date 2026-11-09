package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.newoether.agora.model.Participant

/**
 * Forward-repairs devices that opened the rejected first v18 build.
 *
 * The table schema is unchanged. Correct v18 databases produce an empty repair plan.
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val updates = V18RegenerationEdgeRepairPlanner.plan(
            runs = readV18Runs(db),
            messages = readV18Messages(db),
        )
        for ((messageId, parentId) in updates) {
            db.execSQL(
                "UPDATE messages SET parentId = ? WHERE id = ?",
                arrayOf(parentId, messageId),
            )
        }
    }
}

private fun readV18Runs(db: SupportSQLiteDatabase): List<V18RunRecord> = db.query(
    "SELECT id, parentRunId, legacyAmbiguous FROM runs"
).use { cursor ->
    val idIndex = cursor.getColumnIndexOrThrow("id")
    val parentIndex = cursor.getColumnIndexOrThrow("parentRunId")
    val ambiguousIndex = cursor.getColumnIndexOrThrow("legacyAmbiguous")
    buildList {
        while (cursor.moveToNext()) {
            add(
                V18RunRecord(
                    id = cursor.getString(idIndex),
                    parentRunId = if (cursor.isNull(parentIndex)) null else cursor.getString(parentIndex),
                    legacyAmbiguous = cursor.getInt(ambiguousIndex) != 0,
                )
            )
        }
    }
}

private fun readV18Messages(db: SupportSQLiteDatabase): List<V18MessageRecord> = db.query(
    "SELECT id, parentId, participant, runId, runSequence FROM messages"
).use { cursor ->
    val idIndex = cursor.getColumnIndexOrThrow("id")
    val parentIndex = cursor.getColumnIndexOrThrow("parentId")
    val participantIndex = cursor.getColumnIndexOrThrow("participant")
    val runIdIndex = cursor.getColumnIndexOrThrow("runId")
    val sequenceIndex = cursor.getColumnIndexOrThrow("runSequence")
    buildList {
        while (cursor.moveToNext()) {
            add(
                V18MessageRecord(
                    id = cursor.getString(idIndex),
                    parentId = if (cursor.isNull(parentIndex)) null else cursor.getString(parentIndex),
                    participant = runCatching {
                        Participant.valueOf(cursor.getString(participantIndex))
                    }.getOrDefault(Participant.ERROR),
                    runId = cursor.getString(runIdIndex),
                    runSequence = cursor.getLong(sequenceIndex),
                )
            )
        }
    }
}
