package com.newoether.agora.data.local

import androidx.room.Query

/**
 * Draft operations inherited by [ChatDao].
 *
 * This is not a second database access object: [ChatDao] remains the sole `@Dao`. The split keeps
 * the draft contract reviewable and under the repository's permanent source-size gate.
 */
interface ChatDraftDao {
    @Query("UPDATE conversations SET draftText = :text, draftAttachments = :attachments WHERE id = :id")
    suspend fun updateDraft(id: String, text: String, attachments: String?)
}