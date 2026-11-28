package com.newoether.agora.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.newoether.agora.AgoraApplication
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.SemanticIndexLedgerEntity
import com.newoether.agora.data.local.SemanticSourceEntity
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Resumes the one-time legacy source bootstrap independently of any model or settings page. */
class SemanticMetadataWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val container = (applicationContext as AgoraApplication).awaitContainer()
                ?: return@withContext Result.retry()
            backfillMetadata(container.database)
            wakeActiveCache(container.database, container.settingsManager)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DebugLog.e(TAG, "Legacy semantic metadata bootstrap failed", error)
            Result.retry()
        }
    }

    internal suspend fun backfillMetadata(database: ChatDatabase) {
        val metadata = database.semanticMetadataDao()
        while (true) {
            currentCoroutineContext().ensureActive()
            val state = metadata.ensureMetadataState()
            if (state.complete) return
            val ids = metadata.getLegacyMessageIds(state.afterMessageId, PAGE_SIZE)
            val sources = ArrayList<SemanticSourceEntity>(ids.size)
            for (messageId in ids) {
                currentCoroutineContext().ensureActive()
                // New writes already maintain metadata; never reopen their original text.
                if (metadata.getSemanticSource(messageId) == null) {
                    metadata.getLegacySource(messageId)?.let(sources::add)
                }
                yield()
            }
            currentCoroutineContext().ensureActive()
            // The DAO rejects a superseded cursor and preserves metadata from concurrent writes.
            metadata.commitLegacyPage(
                expectedAfterId = state.afterMessageId,
                sources = sources,
                lastMessageId = ids.lastOrNull() ?: state.afterMessageId,
                exhausted = ids.size < PAGE_SIZE,
            )
            yield()
        }
    }

    private suspend fun wakeActiveCache(database: ChatDatabase, settings: SettingsManager) {
        if (!settings.autoCacheEnabled.first()) return
        val modelId = settings.activeEmbeddingModelId.first()
        if (modelId.isBlank() || settings.embeddingModels.first().none { it.id == modelId }) return
        val ledger = database.semanticIndexDao().getLedger(modelId) ?: return
        if (ledger.state != SemanticIndexLedgerEntity.STATE_CURRENT) {
            EmbeddingCacheWorker.schedule(modelId, WorkManager.getInstance(applicationContext))
        }
    }

    companion object {
        private const val TAG = "SemanticMetadata"
        internal const val UNIQUE_WORK_NAME = "semantic_metadata_bootstrap"
        internal const val PAGE_SIZE = 16

        /** Callers check the durable completion row before requesting this one-time worker. */
        suspend fun schedule(workManager: WorkManager = WorkManager.getInstance()) {
            val request = OneTimeWorkRequestBuilder<SemanticMetadataWorker>()
                .addTag(TAG)
                .build()
            workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request).await()
        }
    }
}
