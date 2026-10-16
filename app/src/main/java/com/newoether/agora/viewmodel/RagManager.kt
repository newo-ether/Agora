package com.newoether.agora.viewmodel

import android.content.Context
import androidx.work.WorkManager
import com.newoether.agora.R
import com.newoether.agora.api.ProviderDefaults
import com.newoether.agora.data.EmbeddingCacheLocks
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.data.local.SemanticIndexLedgerEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.service.EmbeddingCacheWorker
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.SnackbarEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal fun isEmbeddingMessageIdEligible(messageId: String): Boolean =
    !messageId.startsWith(Constants.COMPACT_MSG_PREFIX) &&
        !messageId.startsWith(Constants.TOOL_MSG_PREFIX) &&
        !messageId.startsWith(Constants.RESULT_MSG_PREFIX)

/**
 * Owns embedding-model settings, semantic-ledger admission, durable worker scheduling,
 * and the retained aggregate presentation used by Conversation Search settings.
 * Embedding generation belongs only to [EmbeddingCacheWorker] and the read-only RAG query path.
 */
class RagManager(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val emitSnackbar: suspend (SnackbarEvent) -> Unit,
) {
    val activeEmbeddingModel: StateFlow<EmbeddingModelConfig?> =
        combine(settings.embeddingModels, settings.activeEmbeddingModelId) { models, id ->
            models.find { it.id == id }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    private val workManager = WorkManager.getInstance(appContext)
    private val _cacheRows =
        MutableStateFlow<Map<String, EmbeddingCacheRowSnapshot>>(emptyMap())
    internal val cacheRows: StateFlow<Map<String, EmbeddingCacheRowSnapshot>> =
        _cacheRows.asStateFlow()
    private val scheduledCacheWorkIds =
        java.util.concurrent.ConcurrentHashMap<String, java.util.UUID>()

    @Volatile private var cacheCountRefreshJob: Job? = null
    @Volatile private var pendingRefreshModels: List<EmbeddingModelConfig>? = null
    private var refreshingModelIds: Set<String> = emptySet()
    private var pendingRetryFailed = false
    @Volatile private var cacheWorkObservationJob: Job? = null
    @Volatile private var observedModelIds: Set<String> = emptySet()
    @Volatile private var pendingReminderModelId: String? = null
    @Volatile private var postListStarted = false

    @Synchronized
    fun startPostList() {
        if (postListStarted) return
        postListStarted = true
        scope.launch(Dispatchers.IO) {
            settings.awaitInitialLoad()
            val activeId = settings.activeEmbeddingModelId.value
            if (settings.embeddingModels.value.any { it.id == activeId }) {
                admitActiveModel(activeId)
            }
        }
    }

    fun loadCacheCounts() {
        scope.launch(Dispatchers.IO) {
            settings.awaitInitialLoad()
            val models = settings.embeddingModels.value
            observeCacheWork(models.mapTo(linkedSetOf(), EmbeddingModelConfig::id))
            requestCacheCountRefresh(models = models, dataChanged = false, retryFailed = true)
            models.forEach { model ->
                runCatching {
                    EmbeddingCacheWorker.repairLegacyChain(model.id, workManager)
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    DebugLog.e(
                        "RagManager",
                        "Failed to repair legacy cache work for ${model.id}",
                        error,
                    )
                }
            }
        }
    }

    @Synchronized
    private fun requestCacheCountRefresh(
        reminderModelId: String? = null,
        models: List<EmbeddingModelConfig> = settings.embeddingModels.value,
        dataChanged: Boolean = true,
        retryFailed: Boolean = false,
    ) {
        if (reminderModelId != null) pendingReminderModelId = reminderModelId
        val configuredIds = settings.embeddingModels.value.mapTo(linkedSetOf(), EmbeddingModelConfig::id)
        pruneRemovedModels(configuredIds)
        if (configuredIds.isEmpty()) {
            pendingReminderModelId = null
            pendingRefreshModels = null
            pendingRetryFailed = false
            return
        }
        val eligibleModels = models.filter { model ->
            val row = _cacheRows.value[model.id]
            model.id in configuredIds && (retryFailed || row?.countFailed != true || row.cached != null)
        }
        if (eligibleModels.isEmpty()) return
        val modelIds = eligibleModels.mapTo(linkedSetOf(), EmbeddingModelConfig::id)
        if (cacheCountRefreshJob != null) {
            if (dataChanged || modelIds != refreshingModelIds ||
                retryFailed && modelIds.any { _cacheRows.value[it]?.countFailed == true }) {
                pendingRefreshModels = models
                pendingRetryFailed = pendingRetryFailed || retryFailed
            }
            return
        }
        refreshingModelIds = modelIds
        lateinit var refreshJob: Job
        refreshJob = scope.launch(
            context = Dispatchers.IO,
            start = CoroutineStart.LAZY,
        ) {
            _cacheRows.update { rows ->
                modelIds.fold(rows) { current, modelId ->
                    current + (modelId to EmbeddingCacheRowReducer.refreshRequested(current[modelId]))
                }
            }
            try {
                repeat(2) { attempt ->
                    try {
                        refreshCachePresentation(eligibleModels)
                        return@launch
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        val unknown = modelIds.any { _cacheRows.value[it]?.cached == null }
                        if (attempt == 1 || !unknown) {
                            markCacheCountFailure(modelIds)
                            clearPendingReminder(modelIds)
                            DebugLog.e("RagManager", "Failed to refresh semantic cache presentation", error)
                            return@launch
                        }
                    }
                }
            } finally {
                _cacheRows.update { rows -> rows.mapValues { (id, row) ->
                    if (id in modelIds) row.copy(countLoading = false) else row
                } }
                val active = currentCoroutineContext().isActive
                synchronized(this@RagManager) {
                    val retryPending = pendingRetryFailed
                    val pending = takePendingRefresh(refreshJob)
                    pendingRetryFailed = false
                    if (pending != null && active) requestCacheCountRefresh(retryFailed = retryPending)
                }
            }
        }
        cacheCountRefreshJob = refreshJob
        refreshJob.start()
    }

    private suspend fun refreshCachePresentation(models: List<EmbeddingModelConfig>) {
        val requestedModelIds = models.map(EmbeddingModelConfig::id).toSet()
        val configuredIds = settings.embeddingModels.value
            .mapTo(linkedSetOf(), EmbeddingModelConfig::id)
            .intersect(requestedModelIds)
        if (configuredIds.isEmpty()) {
            _cacheRows.update { it - requestedModelIds }
            clearPendingReminder(requestedModelIds)
            return
        }
        val (total, cachedByModel, ledgers) = coroutineScope {
            val totalDeferred = async { conversations.getIndexableMessageCount() }
            val countsDeferred = async {
                conversations.getEmbeddingCountsByModels(configuredIds.toList())
                    .associate { it.modelId to it.count }
            }
            val ledgersDeferred = async {
                conversations.getSemanticLedgers(configuredIds.toList())
                    .associate { it.modelId to it.state }
            }
            Triple(totalDeferred.await(), countsDeferred.await(), ledgersDeferred.await())
        }
        val stillConfigured = settings.embeddingModels.value
            .mapTo(linkedSetOf(), EmbeddingModelConfig::id)
            .intersect(configuredIds)
        val counts = stillConfigured.associateWith { modelId ->
            (cachedByModel[modelId] ?: 0) to total
        }
        check(total >= 0 && counts.values.all { it.first in 0..total })
        _cacheRows.update { current ->
            stillConfigured.fold(current - requestedModelIds) { rows, modelId ->
                val count = checkNotNull(counts[modelId])
                rows + (
                    modelId to EmbeddingCacheRowReducer.refreshed(
                        previous = current[modelId],
                        cached = count.first,
                        total = count.second,
                    )
                )
            }
        }
        takePendingReminder(requestedModelIds)?.let { modelId ->
            emitUncachedReminder(modelId, ledgers[modelId], counts[modelId])
        }
    }

    private fun markCacheCountFailure(modelIds: Set<String>) {
        val configuredIds = settings.embeddingModels.value
            .mapTo(linkedSetOf(), EmbeddingModelConfig::id)
        _cacheRows.update { current ->
            (modelIds intersect configuredIds).fold(current) { rows, modelId ->
                rows + (modelId to EmbeddingCacheRowReducer.refreshFailed(rows[modelId]))
            }
        }
    }

    @Synchronized
    private fun takePendingRefresh(completedJob: Job): List<EmbeddingModelConfig>? {
        if (cacheCountRefreshJob !== completedJob) return null
        cacheCountRefreshJob = null
        refreshingModelIds = emptySet()
        return pendingRefreshModels.also { pendingRefreshModels = null }
    }

    @Synchronized
    private fun takePendingReminder(modelIds: Set<String>): String? {
        val modelId = pendingReminderModelId?.takeIf { it in modelIds } ?: return null
        pendingReminderModelId = null
        return modelId
    }

    @Synchronized
    private fun clearPendingReminder(modelIds: Set<String>) {
        if (pendingReminderModelId in modelIds) pendingReminderModelId = null
    }

    private fun pruneRemovedModels(modelIds: Set<String>) {
        _cacheRows.update { rows -> rows.filterKeys { it in modelIds } }
        scheduledCacheWorkIds.keys.retainAll(modelIds)
    }

    @Synchronized
    private fun observeCacheWork(modelIds: Set<String>) {
        if (cacheWorkObservationJob?.isActive == true && observedModelIds == modelIds) return
        cacheWorkObservationJob?.cancel()
        observedModelIds = modelIds
        if (modelIds.isEmpty()) {
            cacheWorkObservationJob = null
            return
        }
        cacheWorkObservationJob = scope.launch(Dispatchers.IO) {
            coroutineScope {
                modelIds.forEach { modelId ->
                    launch {
                        var previousActiveIds = emptySet<java.util.UUID>()
                        workManager.getWorkInfosForUniqueWorkFlow(
                            EmbeddingCacheWorker.workNameFor(modelId),
                        ).collect { infos ->
                            if (settings.embeddingModels.value.none { it.id == modelId }) {
                                return@collect
                            }
                            val unfinished = infos.filter { !it.state.isFinished }
                            val activeIds = unfinished.mapTo(linkedSetOf()) { it.id }
                            val running = unfinished.firstOrNull {
                                it.state == androidx.work.WorkInfo.State.RUNNING
                            }
                            val wasActive = _cacheRows.value[modelId]?.workActive == true
                            // Enqueued counts as caching. Waiting for WorkManager to start the
                            // worker is part of caching, and reporting "not cached" in that window
                            // made pressing Cache look like it did nothing.
                            _cacheRows.update { rows ->
                                rows + (
                                    modelId to EmbeddingCacheRowReducer.workChanged(
                                        rows[modelId],
                                        unfinished.isNotEmpty(),
                                        running?.cacheProgressOrNull(),
                                    )
                                )
                            }
                            val scheduledId = scheduledCacheWorkIds[modelId]
                            val scheduledFinished = scheduledId != null && infos.any {
                                it.id == scheduledId && it.state.isFinished
                            }
                            if (scheduledFinished) scheduledCacheWorkIds.remove(modelId, scheduledId)
                            if (wasActive && unfinished.isEmpty() || scheduledFinished ||
                                previousActiveIds.any { it !in activeIds }) {
                                requestCacheCountRefresh()
                            }
                            previousActiveIds = activeIds
                        }
                    }
                }
            }
        }
    }

    private fun androidx.work.WorkInfo.cacheProgressOrNull(): EmbeddingCacheWorkSnapshot? {
        val data = progress
        if (data.getString(EmbeddingCacheWorker.KEY_GENERATION_KIND) != "EXACT") return null
        return embeddingCacheWorkSnapshotOrNull(
            generationRevision = data.getLong(EmbeddingCacheWorker.KEY_GENERATION_REVISION, -1L),
            kind = data.getString(EmbeddingCacheWorker.KEY_GENERATION_KIND),
            processed = data.getInt(EmbeddingCacheWorker.KEY_PROCESSED, -1),
            total = data.getInt(EmbeddingCacheWorker.KEY_WORK_TOTAL, -1),
            remaining = data.getInt(EmbeddingCacheWorker.KEY_REMAINING, -1),
            progressPermille = data.getInt(EmbeddingCacheWorker.KEY_PROGRESS_PERMILLE, -1),
        )
    }

    private suspend fun emitUncachedReminder(
        modelId: String,
        ledgerState: String?,
        counts: Pair<Int, Int>?,
    ) {
        EmbeddingCacheLocks.forModel(modelId).withLock {
            if (settings.embeddingModels.value.none { it.id == modelId }) return@withLock
            if (ledgerState == null || ledgerState == SemanticIndexLedgerEntity.STATE_CURRENT) {
                return@withLock
            }
            if (settings.activeEmbeddingModelId.value != modelId) return@withLock
            if (settings.getAutoCacheEnabled() || !settings.getShowUncachedNotification()) {
                return@withLock
            }
            val notCached = counts?.let { (cached, total) -> (total - cached).coerceAtLeast(0) }
            val message = if (counts != null && notCached != null && notCached > 0) {
                appContext.getString(R.string.messages_not_cached, notCached, counts.second)
            } else {
                appContext.getString(R.string.not_cached)
            }
            emitSnackbar(
                SnackbarEvent(message, appContext.getString(R.string.cache_now)) {
                    cacheMessagesForModel(modelId)
                },
            )
        }
    }

    // -- Embedding-model CRUD ---------------------------------------------------------------

    fun addEmbeddingModel(config: EmbeddingModelConfig) {
        scope.launch(Dispatchers.IO) {
            settings.awaitInitialLoad()
            val wasEmpty = settings.embeddingModels.value.isEmpty()
            val models = settings.embeddingModels.value + config
            settings.saveEmbeddingModels(models)
            var added = false
            EmbeddingCacheLocks.forModel(config.id).withLock {
                if (settings.embeddingModels.value.any { it.id == config.id }) {
                    conversations.invalidateSemanticModel(config.id)
                    if (wasEmpty) settings.setActiveEmbeddingModelId(config.id)
                    added = true
                }
            }
            if (!added) return@launch
            if (wasEmpty) admitActiveModel(config.id)
            val currentModels = settings.embeddingModels.value
            observeCacheWork(currentModels.mapTo(linkedSetOf(), EmbeddingModelConfig::id))
            requestCacheCountRefresh(models = currentModels)
        }
    }

    fun deleteEmbeddingModel(id: String) {
        val workName = EmbeddingCacheWorker.workNameFor(id)
        workManager.cancelUniqueWork(workName)
        scope.launch(Dispatchers.IO) {
            withTimeoutOrNull(10_000) {
                workManager.getWorkInfosForUniqueWorkFlow(workName)
                    .first { infos -> infos.all { it.state.isFinished } }
            }
            var nextActiveModelId: String? = null
            val remainingModels = EmbeddingCacheLocks.forModel(id).withLock {
                val model = settings.embeddingModels.value.find { it.id == id }
                conversations.deleteSemanticModel(id)
                val models = settings.embeddingModels.value.filter { it.id != id }
                settings.saveEmbeddingModels(models)
                if (settings.activeEmbeddingModelId.value == id && models.isNotEmpty()) {
                    nextActiveModelId = models.first().id
                    settings.setActiveEmbeddingModelId(models.first().id)
                }
                models
            }
            nextActiveModelId?.let { admitActiveModel(it) }
            observeCacheWork(remainingModels.mapTo(linkedSetOf(), EmbeddingModelConfig::id))
            requestCacheCountRefresh(models = remainingModels)
        }
    }

    fun renameEmbeddingModel(id: String, newName: String, batchSize: Int? = null) {
        scope.launch(Dispatchers.IO) {
            EmbeddingCacheLocks.forModel(id).withLock {
                val models = settings.embeddingModels.value.map {
                    if (it.id == id) it.copy(name = newName, batchSize = batchSize ?: it.batchSize) else it
                }
                settings.saveEmbeddingModels(models)
            }
        }
    }

    fun setActiveEmbeddingModel(id: String) {
        if (id == settings.activeEmbeddingModelId.value) return
        scope.launch(Dispatchers.IO) {
            EmbeddingCacheLocks.forModel(id).withLock {
                if (settings.embeddingModels.value.none { it.id == id }) return@withLock
                settings.setActiveEmbeddingModelId(id)
            }
            admitActiveModel(id)
        }
    }

    fun setAutoCacheEnabled(enabled: Boolean) {
        settings.setAutoCacheEnabled(enabled)
        if (!enabled) return
        scope.launch(Dispatchers.IO) {
            settings.awaitInitialLoad()
            val activeId = settings.activeEmbeddingModelId.value
            if (settings.embeddingModels.value.any { it.id == activeId }) {
                admitActiveModel(activeId, autoCacheOverride = true)
            }
        }
    }

    // -- Semantic ledger and durable cache work --------------------------------------------

    /**
     * Schedules the cache work for one model. No snackbar: the row's own CACHING state is the
     * feedback, and a message repeating it can only drift from what the row shows.
     */
    fun cacheMessagesForModel(modelId: String, recache: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            settings.awaitInitialLoad()
            val needsWork = EmbeddingCacheLocks.forModel(modelId).withLock {
                if (settings.embeddingModels.value.none { it.id == modelId }) return@withLock false
                if (recache) conversations.invalidateSemanticModel(modelId)
                val state = conversations.getOrAdmitSemanticLedgerState(modelId)
                recache || state != SemanticIndexLedgerEntity.STATE_CURRENT
            }
            if (!needsWork) return@launch
            if (!scheduleCacheWork(modelId)) return@launch
            val models = settings.embeddingModels.value
            observeCacheWork(models.mapTo(linkedSetOf(), EmbeddingModelConfig::id))
            requestCacheCountRefresh(models = models)
        }
    }

    private suspend fun admitActiveModel(
        modelId: String,
        autoCacheOverride: Boolean? = null,
    ) {
        val shouldSchedule = EmbeddingCacheLocks.forModel(modelId).withLock {
            if (settings.embeddingModels.value.none { it.id == modelId }) return@withLock false
            val state = conversations.getOrAdmitSemanticLedgerState(modelId)
            if (state == SemanticIndexLedgerEntity.STATE_CURRENT) return@withLock false
            if (autoCacheOverride ?: settings.getAutoCacheEnabled()) {
                true
            } else {
                if (settings.getShowUncachedNotification()) {
                    requestCacheCountRefresh(
                        reminderModelId = modelId,
                        models = settings.embeddingModels.value,
                    )
                }
                false
            }
        }
        if (shouldSchedule) scheduleCacheWork(modelId)
    }

    private suspend fun scheduleCacheWork(modelId: String): Boolean {
        return runCatching {
            if (settings.embeddingModels.value.none { it.id == modelId }) return false
            if (conversations.getSemanticLedgers(listOf(modelId)).none { it.modelId == modelId }) return false
            if (settings.embeddingModels.value.none { it.id == modelId }) return false
            EmbeddingCacheWorker.schedule(modelId, workManager) {
                if (settings.embeddingModels.value.any { model -> model.id == modelId }) {
                    scheduledCacheWorkIds[modelId] = it
                }
            }
            if (settings.embeddingModels.value.none { it.id == modelId }) {
                scheduledCacheWorkIds.remove(modelId)
                return false
            }
            true
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            scheduledCacheWorkIds.remove(modelId)
            DebugLog.e("RagManager", "Failed to schedule semantic cache work for $modelId", error)
            false
        }
    }
    /**
     * Searchable message persistence already enqueues exact ledger work transactionally.
     * This callback only wakes the one durable consumer when Auto Cache is enabled.
     */
    fun indexMessageForRag(messageId: String, @Suppress("UNUSED_PARAMETER") text: String) {
        if (!isEmbeddingMessageIdEligible(messageId) || !settings.autoCacheEnabled.value) return
        activeEmbeddingModel.value?.id?.let { modelId ->
            scope.launch(Dispatchers.IO) {
                if (
                    settings.autoCacheEnabled.value &&
                    settings.embeddingModels.value.any { it.id == modelId }
                ) {
                    // Enqueueing is a wake-up, not a cache mutation, and must never wait behind
                    // remote/JNI embedding work.
                    scheduleCacheWork(modelId)
                }
            }
        }
    }

    // -- Embedding key / base-URL resolution ------------------------------------------------

    fun resolveEmbeddingApiKey(): String? {
        val keys = settings.apiKeys.value
        for (entry in keys) {
            if (ProviderDefaults.isOpenAiCompatibleEmbedding(entry.provider)) return entry.key
        }
        return keys.firstOrNull()?.key
    }

    fun resolveEmbeddingBaseUrl(): String =
        ProviderDefaults.openAiCompatibleBaseUrl(settings.providerBaseUrls.value)

    data class EmbeddingKeyInfo(val provider: String, val key: String, val baseUrl: String)

    /** Exact match only -- for UI display in the embedding dialog. No fallback. */
    fun resolveEmbeddingKeyForProviderExact(targetProvider: String): EmbeddingKeyInfo? {
        val match = settings.apiKeys.value.find {
            it.provider.equals(targetProvider, ignoreCase = true)
        } ?: return null
        val baseUrl = settings.providerBaseUrls.value[match.provider]
            ?: ProviderDefaults.embeddingBaseUrl(match.provider)
        return EmbeddingKeyInfo(match.provider, match.key, baseUrl)
    }
}
