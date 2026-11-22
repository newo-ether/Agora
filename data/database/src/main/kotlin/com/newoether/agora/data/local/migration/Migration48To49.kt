package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * One-time backfill of the usage ledger from usage already stored on MODEL messages.
 *
 * v48 shipped the ledger empty, so every request made before the ledger existed was missing from the
 * Usage page. This migration folds that history in once; afterwards only request-time recording
 * ([com.newoether.agora.data.UsageRecorder]) writes rows and the pages never read messages again.
 *
 * Existing ledger rows are preserved and never counted twice: history stops at the earliest ledger
 * bucket already present. A message stores the summed usage of its whole run (all tool rounds), so
 * the historical `requests` value counts runs rather than individual provider requests.
 */
val MIGRATION_48_49 = object : Migration(48, 49) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO usage_ledger (bucket, provider, model, source, requests, totalTokens,
                inputTokens, cachedInputTokens, outputTokens, cacheReportedInputTokens)
            SELECT bucket, provider, model, source, COUNT(*),
                SUM(tokenCount), SUM(inputTokens), SUM(cachedInputTokens), SUM(outputTokens),
                SUM(cacheReportedInputTokens)
            FROM (
                SELECT m.timestamp / 900000 AS bucket,
                    CASE WHEN instr(COALESCE(m.modelName, ''), ':') > 1
                        THEN substr(m.modelName, 1, instr(m.modelName, ':') - 1) ELSE '' END AS provider,
                    COALESCE(m.modelName, '') AS model,
                    CASE WHEN c.taskId IS NOT NULL THEN 'task'
                        WHEN c.parentConversationId IS NOT NULL THEN 'subagent' ELSE 'chat' END AS source,
                    m.tokenCount AS tokenCount,
                    COALESCE(m.inputTokenCount, 0) AS inputTokens,
                    COALESCE(m.cachedInputTokenCount, 0) AS cachedInputTokens,
                    COALESCE(m.outputTokenCount, 0) AS outputTokens,
                    CASE WHEN m.cachedInputTokenCount IS NOT NULL
                        THEN COALESCE(m.inputTokenCount, 0) ELSE 0 END AS cacheReportedInputTokens
                FROM messages m JOIN conversations c ON c.id = m.conversationId
                WHERE m.participant = 'MODEL' AND m.timestamp > 0
                    AND (m.tokenCount > 0 OR m.inputTokenCount IS NOT NULL
                        OR m.cachedInputTokenCount IS NOT NULL OR m.outputTokenCount IS NOT NULL)
                    AND m.timestamp / 900000 < COALESCE(
                        (SELECT MIN(bucket) FROM usage_ledger), 9223372036854775807)
            )
            GROUP BY bucket, provider, model, source
            """.trimIndent(),
        )
    }
}
