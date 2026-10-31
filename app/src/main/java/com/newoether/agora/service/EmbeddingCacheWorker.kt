package com.newoether.agora.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.newoether.agora.AgoraApplication
import com.newoether.agora.api.EmbeddingClient
import com.newoether.agora.api.LlamaEngine
import com.newoether.agora.api.ProviderDefaults
import com.newoether.agora.data.EmbeddingIndexer
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.EmbeddingEntity
import com.newoether.agora.data.local.IndexableMessage
import com.newoether.agora.data.local.ReconcileIndexableMessage
import com.newoether.agora.data.local.SEMANTIC_SOURCE_PREFIX_BYTES
import com.newoether.agora.data.local.SemanticIndexLedgerEntity
import com.newoether.agora.data.local.commitSemanticEmbedding
import com.newoether.agora.data.local.semanticSourceFingerprint
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal enum class EmbeddingCacheScheduleDecision {
    KEEP_NEW,
    APPEND_FOLLOWER,
    REPLACE_LEGACY_CHAIN,
    NO_OP,
}

internal fun shouldReuseReconciledEmbedding(
    embeddingId: Long?,
    dimension: Int?,
    embeddingBytes: Int?,
    existingFingerprint: String?,
    expectedFingerprint: String,
): Boolean {
    val validShape =
        dimension != null && dimension > 0 && dimension.toLong() * Float.SIZE_BYTES == embeddingBytes?.toLong()
    return embeddingId != null && validShape &&
        (existingFingerprint == null || existingFingerprint == expectedFingerprint)
}

internal fun embeddingCacheScheduleDecision(
    states: List<WorkInfo.State>,
): EmbeddingCacheScheduleDecision {
    val unfinished = states.filterNot(WorkInfo.State::isFinished)
    val runningCount = unfinished.count { it == WorkInfo.State.RUNNING }
    return when {
        unfinished.size > 2 || runningCount > 1 ->
            EmbeddingCacheScheduleDecision.REPLACE_LEGACY_CHAIN
        unfinished.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } ->
            EmbeddingCacheScheduleDecision.NO_OP
        runningCount == 1 -> EmbeddingCacheScheduleDecision.APPEND_FOLLOWER
        else -> EmbeddingCacheScheduleDecision.KEEP_NEW
    }
}

private data class EmbeddingCandidate(
    val workRevision: Long?,
    val fingerprint: String,
    val message: IndexableMessage,
    val existingEmbeddingId: Long? = null,
)

/** The single durable consumer for one embedding model's semantic ledger work. */
class EmbeddingCacheWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID)
        if (modelId.isNullOrBlank()) {
            DebugLog.w(TAG, "No model_id in input data")
            return@withContext Result.failure()
        }
        val container = (applicationContext as AgoraApplication).awaitContainer()
            ?: return@withContext Result.retry()

        cacheModel(modelId, container.database, container.settingsManager)
    }

    internal suspend fun cacheModel(
        modelId: String,
        database: ChatDatabase,
        settingsManager: SettingsManager,
    ): Result {
        val model = settingsManager.embeddingModels.first().find { it.id == modelId }
            ?: return Result.success()
        val semanticDao = database.semanticIndexDao()

        return try {
            val ledger = semanticDao.getLedger(modelId) ?: return Result.success()
            if (!database.semanticMetadataDao().ensureMetadataState().complete) return Result.retry()
            val completed = if (ledger.state == SemanticIndexLedgerEntity.STATE_CURRENT) {
                true
            } else {
                val exactWorkCompleted = consumeExactWork(
                    model = model,
                    settingsManager = settingsManager,
                    admittedRevision = ledger.sourceRevision,
                    expectedReconcileRevision = ledger.reconcileRevision,
                    database = database,
                )
                exactWorkCompleted && reconcileModel(
                    model = model,
                    settingsManager = settingsManager,
                    admittedRevision = ledger.sourceRevision,
                    expectedReconcileRevision = ledger.reconcileRevision,
                    database = database,
                )
            }
            if (completed) {
                Result.success(workDataOf(KEY_FAILED to 0))
            } else {
                val latest = semanticDao.getLedger(modelId)
                val generationWasSuperseded = latest == null ||
                    latest.reconcileRevision != ledger.reconcileRevision
                if (latest?.state == SemanticIndexLedgerEntity.STATE_CURRENT || generationWasSuperseded) {
                    Result.success(workDataOf(KEY_FAILED to 0))
                } else {
                    Result.retry()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DebugLog.e(TAG, "Cache worker failed", error)
            val displayError = replaceCustomProviderIdsForDisplay(
                error.localizedMessage ?: "Unknown error",
                settingsManager.customProviders.first(),
            )
            Result.failure(workDataOf(KEY_ERROR to displayError))
        }
    }

    private suspend fun consumeExactWork(
        model: EmbeddingModelConfig,
        settingsManager: SettingsManager,
        admittedRevision: Long,
        expectedReconcileRevision: Long,
        database: ChatDatabase,
    ): Boolean {
        val semanticDao = database.semanticIndexDao()
        val workTotal = semanticDao.getWorkCountThroughRevision(model.id, admittedRevision)
        var processed = 0
        var afterRevision = 0L
        var afterMessageId = ""
        var embeddingConfigResolved = false
        var remoteConfig: Pair<String, String>? = null
        var complete = true
        if (workTotal > 0) {
            publishProgress(
                EmbeddingCacheProgress(
                    generationRevision = admittedRevision,
                    kind = EmbeddingCacheWorkKind.EXACT,
                    processed = processed,
                    total = workTotal,
                ),
            )
        }
        while (processed < workTotal) {
            currentCoroutineContext().ensureActive()
            if (semanticDao.getLedger(model.id)?.reconcileRevision != expectedReconcileRevision) {
                return false
            }
            val page = semanticDao.getWorkPage(
                modelId = model.id,
                maxSourceRevision = admittedRevision,
                afterSourceRevision = afterRevision,
                afterMessageId = afterMessageId,
                limit = model.batchSize.coerceIn(1, MAX_BATCH_SIZE),
            )
            if (page.isEmpty()) break
            afterRevision = page.last().sourceRevision
            afterMessageId = page.last().messageId
            val candidates = mutableListOf<EmbeddingCandidate>()
            page.forEach { work ->
                currentCoroutineContext().ensureActive()
                if (work.sourceFingerprint == null) {
                    semanticDao.completeExactWork(work, System.currentTimeMillis())
                } else {
                    val text = semanticDao.getSearchableMessageText(work.messageId)
                    if (text != null && semanticSourceFingerprint(text) == work.sourceFingerprint) {
                        candidates += EmbeddingCandidate(
                            workRevision = work.sourceRevision,
                            fingerprint = work.sourceFingerprint,
                            message = IndexableMessage(work.messageId, text),
                        )
                    } else {
                        // A newer mutation either replaced this work row or made the source
                        // ineligible. Complete only the exact row we actually observed.
                        semanticDao.completeExactWork(work, System.currentTimeMillis())
                    }
                }
            }
            if (candidates.isNotEmpty() && !embeddingConfigResolved) {
                remoteConfig = resolveEmbeddingConfig(model, settingsManager)
                embeddingConfigResolved = true
            }
            if (
                !embedCandidates(
                    model = model,
                    remoteConfig = remoteConfig,
                    candidates = candidates,
                    database = database,
                    expectedReconcileRevision = expectedReconcileRevision,
                )
            ) {
                complete = false
            }
            processed += page.size
            publishProgress(
                EmbeddingCacheProgress(
                    generationRevision = admittedRevision,
                    kind = EmbeddingCacheWorkKind.EXACT,
                    processed = processed.coerceAtMost(workTotal),
                    total = workTotal,
                ),
            )
            yield()
        }
        val latest = semanticDao.getLedger(model.id) ?: return true
        if (latest.reconcileRevision != expectedReconcileRevision) return false
        if (latest.state == SemanticIndexLedgerEntity.STATE_PENDING) {
            semanticDao.markCurrentAfterExactWork(
                modelId = model.id,
                sourceRevision = latest.sourceRevision,
                updatedAt = System.currentTimeMillis(),
            )
        }
        return complete && processed == workTotal
    }

    private suspend fun reconcileModel(
        model: EmbeddingModelConfig,
        settingsManager: SettingsManager,
        admittedRevision: Long,
        expectedReconcileRevision: Long,
        database: ChatDatabase,
    ): Boolean {
        val semanticDao = database.semanticIndexDao()
        val metadataDao = database.semanticMetadataDao()
        var afterConversationId: String? = null
        var embeddingConfigResolved = false
        var remoteConfig: Pair<String, String>? = null
        var complete = true
        val embeddingBatchSize = model.batchSize.coerceIn(1, MAX_BATCH_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (semanticDao.getLedger(model.id)?.reconcileRevision != expectedReconcileRevision) {
                return false
            }
            val conversationCursor = afterConversationId
            val conversations = metadataDao.getDirtyConversations(
                modelId = model.id,
                afterConversationId = conversationCursor,
                limit = RECONCILE_CONVERSATION_PAGE_SIZE,
            )
            // The work-revision filter can hide newer exact rows. Admit these conversation
            // snapshots only before such work exists; later source changes fail their own fence.
            val pageLedger = semanticDao.getLedger(model.id) ?: return false
            if (pageLedger.reconcileRevision != expectedReconcileRevision ||
                pageLedger.sourceRevision != admittedRevision
            ) {
                return false
            }
            if (conversations.isEmpty()) break
            check((conversationCursor == null ||
                conversations.first().conversationId.isAfterSqliteId(conversationCursor)) &&
                conversations.zipWithNext().all { (previous, next) ->
                    next.conversationId.isAfterSqliteId(previous.conversationId)
                }) {
                "Semantic conversation reconciliation cursor did not advance"
            }
            afterConversationId = conversations.last().conversationId
            for (conversation in conversations) {
                currentCoroutineContext().ensureActive()
                if (semanticDao.getLedger(model.id)?.reconcileRevision != expectedReconcileRevision) {
                    return false
                }
                var afterMessageId: String? = null
                var conversationComplete = true
                while (conversation.cacheEligible && conversation.eligibleCount > 0) {
                    currentCoroutineContext().ensureActive()
                    if (semanticDao.getLedger(model.id)?.reconcileRevision != expectedReconcileRevision) {
                        return false
                    }
                    val messageCursor = afterMessageId
                    val messageIds = metadataDao.getConversationSourceIds(
                        conversationId = conversation.conversationId,
                        afterMessageId = messageCursor,
                        limit = RECONCILE_SCAN_PAGE_SIZE,
                    )
                    if (messageIds.isEmpty()) break
                    check((messageCursor == null || messageIds.first().isAfterSqliteId(messageCursor)) &&
                        messageIds.zipWithNext().all { (previous, next) -> next.isAfterSqliteId(previous) }) {
                        "Semantic reconciliation cursor did not advance"
                    }
                    afterMessageId = messageIds.last()
                    val page = semanticDao.getReconcileMessagesByIds(
                        modelId = model.id,
                        maxWorkRevision = admittedRevision,
                        messageIds = messageIds,
                        sourcePrefixBytes = SEMANTIC_SOURCE_PREFIX_BYTES,
                    )
                    val candidates = mutableListOf<EmbeddingCandidate>()
                    page.forEach { row ->
                        currentCoroutineContext().ensureActive()
                        val text = row.text
                        val fingerprint = semanticSourceFingerprint(text)
                        // Source mutations remove old embeddings transactionally. Reusing a valid
                        // legacy row avoids rewriting its BLOB just to fill a nullable fingerprint.
                        val reusable = shouldReuseReconciledEmbedding(
                            embeddingId = row.embeddingId,
                            dimension = row.dimension,
                            embeddingBytes = row.embeddingBytes,
                            existingFingerprint = row.embeddingFingerprint,
                            expectedFingerprint = fingerprint,
                        )
                        if (!reusable) candidates += row.toCandidate(fingerprint, text)
                    }
                    if (candidates.isNotEmpty() && !embeddingConfigResolved) {
                        remoteConfig = resolveEmbeddingConfig(model, settingsManager)
                        embeddingConfigResolved = true
                    }
                    for (batch in candidates.chunked(embeddingBatchSize)) {
                        currentCoroutineContext().ensureActive()
                        if (semanticDao.getLedger(model.id)?.reconcileRevision != expectedReconcileRevision) {
                            return false
                        }
                        if (
                            !embedCandidates(
                                model = model,
                                remoteConfig = remoteConfig,
                                candidates = batch,
                                database = database,
                                expectedReconcileRevision = expectedReconcileRevision,
                            )
                        ) {
                            conversationComplete = false
                        }
                    }
                    yield()
                }
                if (conversationComplete) {
                    // Persist each completed conversation before advancing. A retry then reads only
                    // dirty owners; concurrent mutations and re-cache requests fail this atomic fence.
                    conversationComplete = metadataDao.markConversationIndexed(
                        modelId = model.id,
                        conversationId = conversation.conversationId,
                        sourceRevision = conversation.sourceRevision,
                        expectedReconcileRevision = expectedReconcileRevision,
                    )
                }
                if (!conversationComplete) complete = false
                yield()
            }
        }
        if (!complete) return false
        if (
            !semanticDao.completeReconcile(
                modelId = model.id,
                expectedReconcileRevision = expectedReconcileRevision,
                updatedAt = System.currentTimeMillis(),
            )
        ) {
            return false
        }
        val latest = semanticDao.getLedger(model.id) ?: return true
        return latest.state == SemanticIndexLedgerEntity.STATE_CURRENT &&
            latest.reconcileRevision == expectedReconcileRevision
    }

    // SQLite's BINARY text collation compares unsigned UTF-8 bytes, not UTF-16 chars.
    private fun String.isAfterSqliteId(previous: String): Boolean {
        val nextBytes = toByteArray(Charsets.UTF_8)
        val previousBytes = previous.toByteArray(Charsets.UTF_8)
        for (index in 0 until minOf(nextBytes.size, previousBytes.size)) {
            val comparison = (nextBytes[index].toInt() and 0xff)
                .compareTo(previousBytes[index].toInt() and 0xff)
            if (comparison != 0) return comparison > 0
        }
        return nextBytes.size > previousBytes.size
    }

    private suspend fun embedCandidates(
        model: EmbeddingModelConfig,
        remoteConfig: Pair<String, String>?,
        candidates: List<EmbeddingCandidate>,
        database: ChatDatabase,
        expectedReconcileRevision: Long? = null,
    ): Boolean {
        if (candidates.isEmpty()) return true
        val texts = candidates.map { candidate ->
            candidate.message.text.take(Constants.MAX_EMBEDDING_TEXT_LENGTH)
        }
        val embeddings = if (model.type == EmbeddingModelType.LOCAL) {
            LlamaEngine.computeEmbeddings(texts, model.localFilePath)
        } else {
            val (apiKey, baseUrl) = requireNotNull(remoteConfig)
            EmbeddingClient.computeEmbeddings(
                texts = texts,
                apiKey = apiKey,
                model = model.remoteModelName,
                baseUrl = baseUrl,
            )
        }
        if (embeddings.size != candidates.size) return false
        var complete = true
        candidates.forEachIndexed { index, candidate ->
            currentCoroutineContext().ensureActive()
            val embedding = embeddings[index]
            if (embedding == null || embedding.isEmpty() || embedding.any { !it.isFinite() }) {
                complete = false
                return@forEachIndexed
            }
            database.commitSemanticEmbedding(
                embedding = EmbeddingEntity(
                    id = candidate.existingEmbeddingId ?: 0L,
                    messageId = candidate.message.id,
                    modelId = model.id,
                    embedding = EmbeddingIndexer.floatsToBytes(embedding),
                    chunkText = candidate.message.text.take(Constants.MAX_CHUNK_TEXT_LENGTH),
                    dimension = embedding.size,
                    sourceFingerprint = candidate.fingerprint,
                ),
                expectedFingerprint = candidate.fingerprint,
                expectedWorkRevision = candidate.workRevision,
                expectedReconcileRevision = expectedReconcileRevision,
                completePendingWork = candidate.workRevision != null,
                updatedAt = System.currentTimeMillis(),
            )
            // A false commit means the source/work/reconcile generation was superseded. It is not
            // an embedding failure; the ledger already owns the newer work and this worker loops.
        }
        return complete
    }

    private fun ReconcileIndexableMessage.toCandidate(
        fingerprint: String,
        text: String,
    ): EmbeddingCandidate = EmbeddingCandidate(
        workRevision = null,
        fingerprint = fingerprint,
        message = IndexableMessage(id, text),
        existingEmbeddingId = embeddingId,
    )

    private suspend fun publishProgress(progress: EmbeddingCacheProgress) {
        setProgress(
            workDataOf(
                KEY_GENERATION_REVISION to progress.generationRevision,
                KEY_GENERATION_KIND to progress.kind.name,
                KEY_PROCESSED to progress.processed,
                KEY_WORK_TOTAL to progress.total,
                KEY_REMAINING to progress.remaining,
                KEY_PROGRESS_PERMILLE to progress.progressPermille,
            ),
        )
    }

    private suspend fun resolveEmbeddingConfig(
        model: EmbeddingModelConfig,
        settingsManager: SettingsManager,
    ): Pair<String, String>? {
        if (model.type == EmbeddingModelType.LOCAL) {
            check(LlamaEngine.isModelReady(model.localFilePath)) { "Local model file not found" }
            return null
        }
        val apiKey = model.remoteApiKey.ifBlank { resolveApiKey(settingsManager) ?: "" }
        check(apiKey.isNotBlank()) { "No API key configured" }
        return apiKey to model.remoteBaseUrl.ifBlank { resolveBaseUrl(settingsManager) }
    }

    private suspend fun resolveApiKey(settingsManager: SettingsManager): String? {
        val keys = settingsManager.apiKeys.first()
        return keys.firstOrNull { ProviderDefaults.isOpenAiCompatibleEmbedding(it.provider) }?.key
            ?: keys.firstOrNull()?.key
    }

    private suspend fun resolveBaseUrl(settingsManager: SettingsManager): String =
        ProviderDefaults.openAiCompatibleBaseUrl(settingsManager.providerBaseUrls.first())

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_GENERATION_REVISION = "generation_revision"
        const val KEY_GENERATION_KIND = "generation_kind"
        const val KEY_PROCESSED = "processed"
        const val KEY_WORK_TOTAL = "work_total"
        const val KEY_REMAINING = "remaining"
        const val KEY_PROGRESS_PERMILLE = "progress_permille"
        const val KEY_FAILED = "failed"
        const val KEY_ERROR = "error"
        const val TAG = "EmbeddingCache"
        private const val MAX_BATCH_SIZE = 32
        private const val RECONCILE_CONVERSATION_PAGE_SIZE = 16
        private const val RECONCILE_SCAN_PAGE_SIZE = 128
        private val schedulingLock = Mutex()

        fun workNameFor(modelId: String) = "embedding_cache_$modelId"

        suspend fun schedule(
            modelId: String,
            workManager: WorkManager,
            onScheduled: (java.util.UUID) -> Unit = {},
        ) {
            require(modelId.isNotBlank())
            schedulingLock.withLock {
                val infos = workManager.getWorkInfosForUniqueWorkFlow(workNameFor(modelId)).first()
                infos.firstOrNull { !it.state.isFinished }?.id?.let(onScheduled)
                enqueueForDecision(
                    modelId = modelId,
                    workManager = workManager,
                    decision = embeddingCacheScheduleDecision(infos.map { it.state }),
                    onScheduled = onScheduled,
                )
            }
        }

        /** Collapses pre-v30 APPEND chains without creating work for an otherwise idle model. */
        suspend fun repairLegacyChain(
            modelId: String,
            workManager: WorkManager,
        ) {
            require(modelId.isNotBlank())
            schedulingLock.withLock {
                val infos = workManager.getWorkInfosForUniqueWorkFlow(workNameFor(modelId)).first()
                if (
                    embeddingCacheScheduleDecision(infos.map { it.state }) ==
                    EmbeddingCacheScheduleDecision.REPLACE_LEGACY_CHAIN
                ) {
                    enqueueForDecision(
                        modelId = modelId,
                        workManager = workManager,
                        decision = EmbeddingCacheScheduleDecision.REPLACE_LEGACY_CHAIN,
                    )
                }
            }
        }

        private suspend fun enqueueForDecision(
            modelId: String,
            workManager: WorkManager,
            decision: EmbeddingCacheScheduleDecision,
            onScheduled: (java.util.UUID) -> Unit = {},
        ) {
            if (decision == EmbeddingCacheScheduleDecision.NO_OP) return
            val request = OneTimeWorkRequestBuilder<EmbeddingCacheWorker>()
                .setInputData(workDataOf(KEY_MODEL_ID to modelId))
                .addTag(TAG)
                .build()
            val policy = when (decision) {
                EmbeddingCacheScheduleDecision.KEEP_NEW -> ExistingWorkPolicy.KEEP
                EmbeddingCacheScheduleDecision.APPEND_FOLLOWER ->
                    ExistingWorkPolicy.APPEND_OR_REPLACE
                EmbeddingCacheScheduleDecision.REPLACE_LEGACY_CHAIN ->
                    ExistingWorkPolicy.REPLACE
                EmbeddingCacheScheduleDecision.NO_OP -> return
            }
            onScheduled(request.id)
            workManager.enqueueUniqueWork(workNameFor(modelId), policy, request).await()
        }
    }
}
