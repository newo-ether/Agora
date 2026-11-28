package com.newoether.agora.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Transaction

/**
 * Provider usage totals for one 15-minute UTC bucket, provider, model and request source.
 *
 * Rows are written when a Provider request ends. Existing history is folded in once by
 * [com.newoether.agora.data.local.migration.MIGRATION_48_49]; after that the ledger is never derived
 * from messages, so rows survive conversation deletion. Buckets this fine let the reader assign each row to the correct
 * local day in any time zone, including half-hour offsets and daylight-saving changes.
 */
@Entity(
    tableName = "usage_ledger",
    primaryKeys = ["bucket", "provider", "model", "source"],
)
data class UsageLedgerRow(
    val bucket: Long,
    val provider: String,
    /** Prefixed model ID as stored on model messages. */
    val model: String,
    /** One of [UsageLedgerSource]. */
    val source: String,
    val requests: Long = 0,
    val totalTokens: Long = 0,
    val inputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val outputTokens: Long = 0,
    /** Input tokens of requests that also reported a cache split; the cache-hit denominator. */
    val cacheReportedInputTokens: Long = 0,
)

object UsageLedgerSource {
    const val CHAT = "chat"
    const val TASK = "task"
    const val SUBAGENT = "subagent"
    const val TITLE = "title"
    const val TRANSCRIPTION = "transcription"
}

/** Conversation kind used to attribute a chat request to Chat, Task or Subagent. */
data class UsageConversationKind(
    val taskId: String?,
    val parentConversationId: String?,
)

@Dao
interface UsageLedgerDao {
    @Query("SELECT * FROM usage_ledger")
    suspend fun all(): List<UsageLedgerRow>

    @Query("SELECT taskId, parentConversationId FROM conversations WHERE id = :conversationId")
    suspend fun conversationKind(conversationId: String): UsageConversationKind?

    @Query(
        """
        INSERT OR IGNORE INTO usage_ledger (bucket, provider, model, source, requests, totalTokens,
            inputTokens, cachedInputTokens, outputTokens, cacheReportedInputTokens)
        VALUES (:bucket, :provider, :model, :source, 0, 0, 0, 0, 0, 0)
        """
    )
    suspend fun ensureRow(bucket: Long, provider: String, model: String, source: String)

    @Query(
        """
        UPDATE usage_ledger SET requests = requests + :requests,
            totalTokens = totalTokens + :totalTokens,
            inputTokens = inputTokens + :inputTokens,
            cachedInputTokens = cachedInputTokens + :cachedInputTokens,
            outputTokens = outputTokens + :outputTokens,
            cacheReportedInputTokens = cacheReportedInputTokens + :cacheReportedInputTokens
        WHERE bucket = :bucket AND provider = :provider AND model = :model AND source = :source
        """
    )
    suspend fun addTo(
        bucket: Long,
        provider: String,
        model: String,
        source: String,
        requests: Long,
        totalTokens: Long,
        inputTokens: Long,
        cachedInputTokens: Long,
        outputTokens: Long,
        cacheReportedInputTokens: Long,
    )

    /** Adds [row]'s counts to its key; SQLite on API < 30 has no UPSERT, so insert-then-add. */
    @Transaction
    suspend fun add(row: UsageLedgerRow) {
        ensureRow(row.bucket, row.provider, row.model, row.source)
        addTo(
            bucket = row.bucket,
            provider = row.provider,
            model = row.model,
            source = row.source,
            requests = row.requests,
            totalTokens = row.totalTokens,
            inputTokens = row.inputTokens,
            cachedInputTokens = row.cachedInputTokens,
            outputTokens = row.outputTokens,
            cacheReportedInputTokens = row.cacheReportedInputTokens,
        )
    }
}
