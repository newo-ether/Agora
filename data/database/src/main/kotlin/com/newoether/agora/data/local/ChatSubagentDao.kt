package com.newoether.agora.data.local

import androidx.room.Query
import com.newoether.agora.model.SubagentSummary
import kotlinx.coroutines.flow.Flow

data class SubagentOutboxRow(
    val id: String,
    val title: String,
    val resultJson: String,
    val resultId: String,
)

// Shared by observation and explicit-open repair; no message or completion payload is selected.
private const val SUBAGENT_STATUS_PROJECTION = """
    SELECT c.id, c.title, c.modelId, c.parentConversationId,
        CASE WHEN EXISTS(SELECT 1 FROM runs r WHERE r.conversationId = c.id AND r.activeSlot = 1)
            THEN 'running' ELSE COALESCE((
                SELECT CASE
                    WHEN r.endReason = 'PROCESS_RECOVERED' THEN 'failed'
                    WHEN r.status = 'COMPLETED' AND m.status = 'SUCCESS' THEN 'completed'
                    WHEN r.status = 'STOPPED' AND m.status = 'STOPPED' THEN 'stopped'
                    WHEN r.status = 'FAILED' AND m.status = 'ERROR' THEN 'failed'
                    ELSE NULL END
                FROM runs r JOIN messages m ON m.runId = r.id AND m.conversationId = c.id
                WHERE r.conversationId = c.id AND r.activeSlot IS NULL AND r.legacyAmbiguous = 0
                    AND m.participant = 'MODEL' AND substr(m.id, 1, 8) != 'compact_'
                    AND m.runSequence = (SELECT MAX(tail.runSequence) FROM messages tail
                        WHERE tail.runId = r.id AND tail.conversationId = c.id AND tail.participant = 'MODEL')
                    AND NOT EXISTS(SELECT 1 FROM messages tied WHERE tied.runId = r.id
                        AND tied.participant = 'MODEL' AND tied.runSequence = m.runSequence AND tied.id != m.id)
                    AND NOT EXISTS(SELECT 1 FROM runs newer WHERE newer.conversationId = c.id
                        AND newer.id != r.id AND newer.startedAt >= r.startedAt
                        AND EXISTS(SELECT 1 FROM messages ordinary WHERE ordinary.runId = newer.id
                            AND ordinary.conversationId = c.id AND ordinary.participant = 'MODEL'
                            AND substr(ordinary.id, 1, 8) != 'compact_'))
                    AND (c.subagentResultId IS NULL OR EXISTS(
                        SELECT 1 FROM messages cached JOIN runs cachedRun ON cachedRun.id = cached.runId
                        WHERE cached.id = c.subagentResultId AND cached.conversationId = c.id
                            AND cached.participant = 'MODEL'
                            AND cachedRun.conversationId = c.id AND cachedRun.legacyAmbiguous = 0
                            AND (cachedRun.id = r.id OR r.startedAt > cachedRun.startedAt)))
            ), c.subagentResultStatus, 'idle') END AS status,
        NULL AS errorText, NULL AS errorCode
    FROM conversations c
"""

/** Indexed parent-scoped reads. No startup enumeration or full child payload observation. */
interface ChatSubagentDao {
    @Query("""
        SELECT id, title, subagentResultJson AS resultJson, subagentResultId AS resultId
        FROM conversations WHERE parentConversationId = :parentId
            AND subagentResultJson IS NOT NULL AND subagentResultId IS NOT NULL
            AND (subagentDeliveredResultId IS NULL OR subagentDeliveredResultId != subagentResultId)
            AND NOT EXISTS(SELECT 1 FROM runs WHERE conversationId = conversations.id AND activeSlot = 1)
        ORDER BY lastUpdated, id
    """)
    suspend fun pendingSubagentResults(parentId: String): List<SubagentOutboxRow>

    @Query("SELECT parentConversationId FROM conversations WHERE id = :id")
    fun observeParentConversationId(id: String): Flow<String?>

    @Query("""
        SELECT DISTINCT parentConversationId FROM conversations
        WHERE id IN (:conversationIds) AND parentConversationId IS NOT NULL
    """)
    fun observeSubagentParentIds(conversationIds: Set<String>): Flow<List<String>>

    @Query("""
        SELECT root.id FROM conversations selected
        JOIN conversations root ON root.id = COALESCE(selected.parentConversationId, selected.id)
        WHERE selected.id = :conversationId AND root.parentConversationId IS NULL
    """)
    suspend fun getSubagentFamilyRootId(conversationId: String): String?

    @Query("SELECT id FROM conversations WHERE parentConversationId = :parentId ORDER BY createdAt, id")
    suspend fun getSubagentIds(parentId: String): List<String>

    @Query("SELECT COUNT(*) FROM conversations WHERE parentConversationId = :parentId")
    suspend fun countSubagents(parentId: String): Int

    @Query(SUBAGENT_STATUS_PROJECTION + " WHERE c.parentConversationId = :parentId ORDER BY c.createdAt, c.id")
    fun observeSubagents(parentId: String): Flow<List<SubagentSummary>>

    @Query(SUBAGENT_STATUS_PROJECTION + " WHERE c.id = :id AND c.parentConversationId IS NOT NULL")
    suspend fun getValidatedSubagentStatus(id: String): SubagentSummary?

    @Query("SELECT subagentResultJson FROM conversations WHERE id = :id AND parentConversationId = :parentId")
    suspend fun getSubagentCompletionJson(id: String, parentId: String): String?

    @Query("SELECT subagentResultStatus FROM conversations WHERE id = :id")
    suspend fun getSubagentCachedStatus(id: String): String?

    @Query("""
        UPDATE conversations SET subagentResultStatus = :status,
            dataChangedAt = MAX(dataChangedAt + 1, :at)
        WHERE id = :id AND parentConversationId = :parentId
            AND subagentResultStatus IS :expectedStatus AND subagentResultStatus IS NOT :status
            AND NOT EXISTS(SELECT 1 FROM runs WHERE conversationId = :id AND activeSlot = 1)
    """)
    suspend fun compareAndSetSubagentStatus(
        id: String, parentId: String, expectedStatus: String?, status: String, at: Long,
    ): Int

    @Query("""
        SELECT EXISTS(SELECT 1 FROM runs r JOIN conversations c ON c.id = r.conversationId
            WHERE c.id = :conversationId AND c.parentConversationId IS NULL
            AND r.id = :runId AND r.status = 'ACTIVE' AND r.activeSlot = 1
            AND r.currentPass = :pass AND r.stopRequestedAt IS NULL)
    """)
    suspend fun isSubagentToolOwnerActive(conversationId: String, runId: String, pass: Int): Boolean

    @Query("""
        UPDATE conversations SET subagentResultJson = :result, subagentResultId = :resultId,
            subagentResultStatus = :status, dataChangedAt = MAX(dataChangedAt + 1, :at)
        WHERE id = :id AND parentConversationId = :parentId
            AND NOT EXISTS(SELECT 1 FROM runs WHERE conversationId = :id AND activeSlot = 1)
    """)
    suspend fun setSubagentResult(
        id: String, parentId: String, result: String, resultId: String, status: String, at: Long,
    ): Int

    @Query("""
        UPDATE conversations SET subagentDeliveredResultId = :resultId,
            dataChangedAt = MAX(dataChangedAt + 1, :at)
        WHERE id = :id AND parentConversationId = :parentId AND subagentResultId = :resultId
            AND (subagentDeliveredResultId IS NULL OR subagentDeliveredResultId != :resultId)
    """)
    suspend fun acknowledgeSubagentResult(id: String, parentId: String, resultId: String, at: Long): Int
}
