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
import com.newoether.agora.api.ProviderDefaults
import com.newoether.agora.data.EmbeddingIndexer
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.data.OnnxEmbeddingProvider
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.EmbeddingEntity
import com.newoether.agora.data.local.IndexableMessage
import com.newoether.agora.data.local.ReconcileIndexableMessage
import com.newoether.agora.data.local.SemanticIndexLedgerEntity
import com.newoether.agora.data.local.commitSemanticEmbedding
import com.newoether.agora.data.local.semanticSourceFingerprint
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        dimension != null && dimension > 0 && embeddingBytes == dimension * Float.SIZE_BYTES
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
            val completed = when (ledger.state) {
                SemanticIndexLedgerEntity.STATE_CURRENT -> true
                SemanticIndexLedgerEntity.STATE_PENDING ->
                    consumeExactWork(
                        model = model,
                        settingsManager = settingsManager,
                        admittedRevision = ledger.sourceRevision,
                        database = database,
                    )

                SemanticIndexLedgerEntity.STATE_NEEDS_RECONCILE ->
                    reconcileModel(
                        model = model,
                        settingsManager = settingsManager,
                        expectedReconcileRevision = ledger.reconcileRevision,
                        database = database,
                    )

                else -> true
            }
            if (completed) {
                Result.success(workDataOf(KEY_FAILED to 0))
            } else {
                val latest = semanticDao.getLedger(modelId)
                val generationWasSuperseded = when (ledger.state) {
                    SemanticIndexLedgerEntity.STATE_PENDING ->
                        latest?.sourceRevision != ledger.sourceRevision
                    SemanticIndexLedgerEntity.STATE_NEEDS_RECONCILE ->
                        latest?.reconcileRevision != ledger.reconcileRevision
                    else -> false
                }
                if (generationWasSuperseded) {
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
        database: ChatDatabase,
    ): Boolean {
        val semanticDao = database.semanticIndexDao()
        val workTotal = semanticDao.getWorkCountThroughRevision(model.id, admittedRevision)
        if (workTotal == 0) return true
        var processed = 0
        var afterRevision = 0L
        var afterMessageId = ""
        var embeddingConfigResolved = false
        var remoteConfig: Pair<String, String>? = null
        var complete = true
        publishProgress(
            EmbeddingCacheProgress(
                generationRevision = admittedRevision,
                kind = EmbeddingCacheWorkKind.EXACT,
                processed = processed,
                total = workTotal,
            ),
        )
        while (processed < workTotal) {
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
            if (!embedCandidates(model, remoteConfig, candidates, database)) complete = false
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
        expectedReconcileRevision: Long,
        database: ChatDatabase,
    ): Boolean {
        val semanticDao = database.semanticIndexDao()
        var afterMessageId: String? = null
        var embeddingConfigResolved = false
        var remoteConfig: Pair<String, String>? = null
        var complete = true
        val embeddingBatchSize = model.batchSize.coerceIn(1, MAX_BATCH_SIZE)
        while (true) {
            if (semanticDao.getLedger(model.id)?.reconcileRevision != expectedReconcileRevision) {
                return false
            }
            val page = semanticDao.getReconcileMessagesPage(
                modelId = model.id,
                maxWorkRevision = expectedReconcileRevision,
                afterId = afterMessageId,
                limit = RECONCILE_SCAN_PAGE_SIZE,
            )
            if (page.isEmpty()) break
            afterMessageId = page.last().id
            val candidates = mutableListOf<EmbeddingCandidate>()
            page.forEach { row ->
                val fingerprint = semanticSourceFingerprint(row.text)
                // An extant legacy row is already source-safe: all semantic source mutations
                // delete its embedding transactionally before enqueuing work. Rewriting a nullable
                // fingerprint into every pre-v30 row would rewrite gigabytes of BLOB-bearing rows.
                val reusable = shouldReuseReconciledEmbedding(
                    embeddingId = row.embeddingId,
                    dimension = row.dimension,
                    embeddingBytes = row.embeddingBytes,
                    existingFingerprint = row.embeddingFingerprint,
                    expectedFingerprint = fingerprint,
                )
                if (!reusable) candidates += row.toCandidate(fingerprint)
            }
            if (candidates.isNotEmpty() && !embeddingConfigResolved) {
                remoteConfig = resolveEmbeddingConfig(model, settingsManager)
                embeddingConfigResolved = true
            }
            candidates.chunked(embeddingBatchSize).forEach { batch ->
                if (
                    !embedCandidates(
                        model = model,
                        remoteConfig = remoteConfig,
                        candidates = batch,
                        database = database,
                        expectedReconcileRevision = expectedReconcileRevision,
                    )
                ) {
                    complete = false
                }
            }
            yield()
        }
        if (!complete) return false
        return semanticDao.completeReconcile(
            modelId = model.id,
            expectedReconcileRevision = expectedReconcileRevision,
            updatedAt = System.currentTimeMillis(),
        )
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
        val embeddings = when (model.type) {
            EmbeddingModelType.LOCAL_ONNX -> {
                val engine = OnnxEmbeddingProvider.engine(applicationContext)
                if (engine == null) texts.map { null } else texts.map { engine.embed(it) }
            }
            EmbeddingModelType.REMOTE -> {
                val (apiKey, baseUrl) = requireNotNull(remoteConfig)
                EmbeddingClient.computeEmbeddings(
                    texts = texts,
                    apiKey = apiKey,
                    model = model.remoteModelName,
                    baseUrl = baseUrl,
                )
            }
        }
        if (embeddings.size != candidates.size) return false
        candidates.forEachIndexed { index, candidate ->
            val embedding = embeddings[index] ?: return false
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
        return true
    }

    private fun ReconcileIndexableMessage.toCandidate(
        fingerprint: String,
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
        if (model.type == EmbeddingModelType.LOCAL_ONNX) {
            check(OnnxEmbeddingProvider.isReady(applicationContext)) { "ONNX embedding model not installed" }
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
