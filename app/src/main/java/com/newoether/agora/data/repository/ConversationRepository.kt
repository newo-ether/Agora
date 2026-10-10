package com.newoether.agora.data.repository

import androidx.room.withTransaction
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.DrawerConversationRow
import com.newoether.agora.data.local.ConversationSettingsImportTransferEntity
import com.newoether.agora.data.local.ConversationSettingsTransferEntity
import com.newoether.agora.data.local.EmbeddingEntity
import com.newoether.agora.data.local.EmbeddingModelCount
import com.newoether.agora.data.local.EmbeddingSearchRow
import com.newoether.agora.data.local.IndexableMessage
import com.newoether.agora.data.local.MaintenanceDebtDao
import com.newoether.agora.data.local.MaintenanceDebtEntity
import com.newoether.agora.data.local.MessageAttachmentReference
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.NewChatPersistEntity
import com.newoether.agora.data.local.ProviderContextTopologySnapshot
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.local.RunGraphCommit
import com.newoether.agora.data.local.SemanticIndexLedgerEntity
import com.newoether.agora.data.local.SemanticModelSnapshot
import com.newoether.agora.data.local.ToolRoundCommit
import com.newoether.agora.data.local.commitSemanticEmbedding
import com.newoether.agora.data.local.deleteSemanticModel
import com.newoether.agora.data.local.invalidateSemanticModel
import com.newoether.agora.data.local.semanticModelSnapshot
import com.newoether.agora.data.local.withSemanticEligibilityMutation
import com.newoether.agora.data.local.withSemanticGraphMutation
import com.newoether.agora.data.local.withSemanticSourceMutation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.service.MaintenanceDebtWorker
import com.newoether.agora.util.AttachmentFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConversationRepository(
    internal val chatDao: ChatDao,
    /** Non-null in production; null is an explicit DAO-isolated unit-test seam. */
    private val database: ChatDatabase?,
    private val scheduleMaintenance: () -> Unit = { MaintenanceDebtWorker.schedule() },
    private val maintenanceDebtDao: MaintenanceDebtDao? = database?.maintenanceDebtDao(),
    private val semanticModelSnapshotProvider: suspend () -> SemanticModelSnapshot = {
        semanticModelSnapshot("", emptyList())
    },
) {
    private val branchSelections = ConversationBranchSelections(chatDao)

    // ── Conversations ─────────────────────────────────────────

    fun getAllConversations(): Flow<List<ChatConversation>> = chatDao.getAllConversations()
    fun observeDrawerConversations(limit: Int): Flow<List<DrawerConversationRow>> =
        chatDao.observeDrawerConversations(limit)
    suspend fun getDrawerConversations(ids: List<String>): List<DrawerConversationRow> =
        if (ids.isEmpty()) emptyList() else chatDao.getDrawerConversations(ids)

    fun observeConversation(id: String): Flow<ChatConversation?> =
        chatDao.observeConversation(id).map { it?.toConversation() }

    fun observeNewChatPersist(): Flow<NewChatPersistEntity?> =
        chatDao.observeNewChatPersist()

    suspend fun getNewChatPersist(): NewChatPersistEntity? =
        chatDao.getNewChatPersist()

    suspend fun upsertNewChatPersist(entity: NewChatPersistEntity) {
        var scheduled = false
        withMaintenanceTransaction {
            val previousRaw = chatDao.getNewChatPersist()?.draftAttachments
            val previous = previousRaw.decodeSelectedAttachments()
            val replacement = entity.draftAttachments.decodeSelectedAttachments()
            chatDao.upsertNewChatPersist(entity)
            scheduled = when {
                previousRaw == null -> false
                previous == null || (entity.draftAttachments != null && replacement == null) ->
                    enqueueAttachmentReconcile()
                else -> enqueueAttachmentDebt(previous.removedReclaimablePaths(replacement))
            }
        }
        if (scheduled) scheduleMaintenance()
    }

    suspend fun deleteNewChatPersist(reclaimAttachments: Boolean = true): Boolean {
        var deleted = false
        var scheduled = false
        withMaintenanceTransaction {
            val previousRaw = chatDao.getNewChatPersist()?.draftAttachments
            val previous = previousRaw.decodeSelectedAttachments()
            deleted = chatDao.deleteNewChatPersist() > 0
            if (deleted && reclaimAttachments) {
                scheduled = if (previousRaw != null && previous == null) {
                    enqueueAttachmentReconcile()
                } else {
                    enqueueAttachmentDebt(previous.orEmpty().reclaimablePaths())
                }
            }
        }
        if (scheduled) scheduleMaintenance()
        return deleted
    }

    suspend fun getConversationSettingsTransfer(
        conversationId: String,
    ): ConversationSettingsTransferEntity? =
        chatDao.getConversationSettingsTransfer(conversationId)

    suspend fun getPendingConversationSettingsTransfers(): List<ConversationSettingsTransferEntity> =
        chatDao.getPendingConversationSettingsTransfers()

    suspend fun deleteConversationSettingsTransfer(conversationId: String): Boolean =
        chatDao.deleteConversationSettingsTransfer(conversationId) > 0

    suspend fun getConversationSettingsImportTransfer(): ConversationSettingsImportTransferEntity? =
        chatDao.getConversationSettingsImportTransfer()

    suspend fun deleteConversationSettingsImportTransfer(transferId: String): Boolean =
        chatDao.deleteConversationSettingsImportTransfer(transferId) > 0

    /** Executions spawned by [taskId], newest first — the task's execution log. */
    fun getExecutionsForTask(taskId: String): Flow<List<ChatConversation>> =
        chatDao.getExecutionsForTask(taskId).map { entities -> entities.map { it.toConversation() } }

    /** Observes message-level changes for every execution belonging to [taskId]. */
    fun observeExecutionMessagesForTask(taskId: String): Flow<List<com.newoether.agora.data.local.ExecutionMessageSummaryRow>> =
        chatDao.observeExecutionMessagesForTask(taskId)

    suspend fun getConversation(id: String): ChatEntity? =
        chatDao.getConversation(id)

    suspend fun createConversation(title: String, systemPromptId: String? = null, modelId: String? = null): String {
        val id = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        chatDao.upsertConversation(
            ChatEntity(
                id = id, title = title, lastUpdated = now, dataChangedAt = now,
                systemPromptId = systemPromptId, modelId = modelId,
            )
        )
        return id
    }

    suspend fun upsertConversation(entity: ChatEntity) = withSemanticTransaction(
        conversationId = entity.id,
    ) {
        chatDao.upsertConversation(entity.copy(dataChangedAt = System.currentTimeMillis()))
    }

    suspend fun updateConversationTitle(id: String, title: String): Boolean =
        chatDao.updateConversationTitle(id, title, System.currentTimeMillis()) == 1

    suspend fun setConversationPinned(id: String, pinned: Boolean): Boolean =
        chatDao.setConversationPinned(id, pinned, System.currentTimeMillis()) == 1
    suspend fun updateConversationSystemPrompt(id: String, promptId: String?): Boolean =
        chatDao.updateConversationSystemPrompt(id, promptId, System.currentTimeMillis()) == 1

    suspend fun setConversationUnreadGeneration(
        id: String,
        unread: Boolean,
    ): Boolean = chatDao.setConversationUnreadGeneration(id, unread) == 1

    suspend fun updateConversationTitleIfUnchanged(
        id: String,
        expectedTitle: String,
        newTitle: String,
    ): Boolean = chatDao.updateConversationTitleIfUnchanged(
        id,
        expectedTitle,
        newTitle,
        System.currentTimeMillis(),
    ) == 1

    suspend fun touchConversationData(conversationId: String): Boolean =
        chatDao.touchConversationData(conversationId, System.currentTimeMillis()) == 1

    suspend fun deleteConversation(id: String) {
        var scheduled = false
        withSemanticTransaction(updatedAt = System.currentTimeMillis()) {
            val conversation = chatDao.getConversation(id) ?: return@withSemanticTransaction
            val draftAttachmentsRaw = conversation.draftAttachments
            val draftAttachments = draftAttachmentsRaw.decodeSelectedAttachments()
            val attachmentReferences = mutableListOf<MessageAttachmentReference>()
            var afterId: String? = null
            while (true) {
                val page = chatDao.getConversationMessageAttachmentReferencesPage(
                    conversationId = id,
                    afterId = afterId,
                    limit = ATTACHMENT_REFERENCE_PAGE_SIZE,
                )
                attachmentReferences += page
                afterId = page.lastOrNull()?.id
                if (page.size < ATTACHMENT_REFERENCE_PAGE_SIZE) break
            }
            chatDao.deleteEmbeddingsByConversation(id)
            chatDao.deleteMessagesByConversation(id)
            chatDao.deleteConversation(id)
            scheduled = enqueueMessageAttachmentDebt(attachmentReferences) or when {
                draftAttachmentsRaw != null && draftAttachments == null -> enqueueAttachmentReconcile()
                else -> enqueueAttachmentDebt(draftAttachments.orEmpty().reclaimablePaths())
            }
        }
        if (scheduled) scheduleMaintenance()
    }

    // ── Messages ──────────────────────────────────────────────

    fun observeMessageTopology(
        conversationId: String,
    ): Flow<List<MessageContextTopology>> =
        chatDao.observeMessageContextTopology(conversationId)

    suspend fun getMessageTopologySnapshot(
        conversationId: String,
    ): List<MessageContextTopology> =
        chatDao.getMessageContextTopology(conversationId)

    fun observeMessage(messageId: String): Flow<MessageEntity?> =
        chatDao.observeMessage(messageId)

    suspend fun getProviderContextTopologySnapshot(
        conversationId: String,
    ): ProviderContextTopologySnapshot? =
        chatDao.getProviderContextTopologySnapshot(conversationId)

    /** Keeps topology and every selected payload row on one immutable Room read snapshot. */
    suspend fun <T> withProviderContextSnapshot(block: suspend () -> T): T =
        database?.withTransaction { block() } ?: block()

    suspend fun getMessage(messageId: String): MessageEntity? =
        chatDao.getMessage(messageId)

    suspend fun getContextMessagesByIds(ids: List<String>): List<MessageEntity> =
        getMessagesByIds(ids)

    suspend fun getLastMessageForConversation(conversationId: String): MessageEntity? =
        chatDao.getLastMessageForConversation(conversationId)

    suspend fun upsertMessage(entity: MessageEntity) {
        require(entity.runId.isNotBlank()) { "Message ${entity.id} has no Run" }
        require(entity.runSequence >= 0) { "Message ${entity.id} has no Run sequence" }
        withSemanticTransaction(listOf(entity.id)) {
            chatDao.upsertMessage(entity)
            check(chatDao.touchConversationData(entity.conversationId, System.currentTimeMillis()) == 1)
        }
    }

    suspend fun createRunWithMessages(
        run: RunEntity,
        messages: List<MessageEntity>,
        messageSelectionUpdates: Map<String?, String>,
        conversationModelId: String?,
        at: Long = System.currentTimeMillis(),
        touchConversationOnAdmission: Boolean,
    ): RunGraphCommit = withSemanticTransaction(messages.map(MessageEntity::id), at) {
        chatDao.createRunWithMessages(
            run,
            messages,
            messageSelectionUpdates,
            conversationModelId,
            at,
            touchConversationOnAdmission,
        )
    }

    suspend fun createConversationRunWithMessages(
        conversation: ChatEntity,
        run: RunEntity,
        messages: List<MessageEntity>,
        messageSelectionUpdates: Map<String?, String>,
        conversationModelId: String,
        conversationSettingsJson: String?,
        expectedNewChatPersist: NewChatPersistEntity?,
        at: Long = System.currentTimeMillis(),
    ): RunGraphCommit {
        lateinit var graph: RunGraphCommit
        var scheduled = false
        withSemanticTransaction(messages.map(MessageEntity::id), at) {
            val previousRaw = expectedNewChatPersist?.draftAttachments
            val previous = previousRaw.decodeSelectedAttachments()
            graph = chatDao.createConversationRunWithMessages(
                conversation,
                run,
                messages,
                messageSelectionUpdates,
                conversationModelId,
                conversationSettingsJson,
                expectedNewChatPersist,
                at,
            )
            scheduled = when {
                previousRaw == null -> false
                previous == null -> enqueueAttachmentReconcile()
                else -> enqueueAttachmentDebt(previous.appPrivatePaths())
            }
        }
        if (scheduled) scheduleMaintenance()
        return graph
    }

    suspend fun importExternalConversationGraph(
        conversations: List<ChatEntity>,
        runs: List<RunEntity>,
        messages: List<MessageEntity>,
        replace: Boolean,
    ) {
        var scheduled = false
        val messageIds = messages.map(MessageEntity::id)
        withSemanticTransaction(
            clearMessageIds = if (replace) emptyList() else messageIds,
            clearAllEmbeddings = replace,
        ) {
            if (replace) {
                chatDao.replaceImportedConversationGraph(conversations, runs, messages)
            } else {
                conversations.forEach { chatDao.upsertConversation(it) }
                chatDao.importRunGraph(runs, messages)
            }
            scheduled = enqueueReconcileDebt()
        }
        if (scheduled) scheduleMaintenance()
    }

    suspend fun createForkGraph(
        conversation: ChatEntity,
        runs: List<RunEntity>,
        messages: List<MessageEntity>,
        sourceToForkMessageIds: Map<String, String>,
    ) = withSemanticTransaction {
        chatDao.createForkGraph(conversation, runs, messages, sourceToForkMessageIds)
    }

    suspend fun appendToolRoundToRun(
        messages: List<MessageEntity>,
        expectedPass: Int,
    ): ToolRoundCommit {
        require(messages.isNotEmpty() && messages.all { it.runId.isNotBlank() })
        return withSemanticTransaction(messages.map(MessageEntity::id)) {
            chatDao.appendToolRoundToRun(messages, expectedPass).also { commit ->
                if (commit.inserted) {
                    check(
                        chatDao.touchConversationData(
                            messages.first().conversationId,
                            System.currentTimeMillis(),
                        ) == 1,
                    )
                }
            }
        }
    }

    suspend fun recoverConversationRuntime(
        conversationId: String,
        at: Long = System.currentTimeMillis(),
    ): Int = chatDao.recoverConversationRuntime(
        conversationId = conversationId,
        at = at,
    )

    suspend fun getRun(runId: String): RunEntity? = chatDao.getRun(runId)

    fun getRunsForConversation(conversationId: String): Flow<List<RunEntity>> =
        chatDao.getRunsForConversation(conversationId)

    suspend fun getRunsForConversationSnapshot(conversationId: String): List<RunEntity> =
        chatDao.getRunsForConversationSnapshot(conversationId)

    suspend fun getMessagesForRuns(runIds: List<String>): List<MessageEntity> =
        if (runIds.isEmpty()) emptyList() else chatDao.getMessagesForRuns(runIds)

    suspend fun deleteMessageSubtree(
        conversationId: String,
        rootMessageId: String,
        staleMessageIds: List<String>,
        rootRunIdsToDelete: List<String>,
        messageSelections: Map<String?, String>,
        runSelections: Map<String?, String>,
        at: Long = System.currentTimeMillis(),
    ): Boolean {
        var deleted = false
        var scheduled = false
        withSemanticTransaction(staleMessageIds, at) {
            val staleMessages = if (staleMessageIds.isEmpty()) {
                emptyList()
            } else {
                chatDao.getMessagesByIds(staleMessageIds)
            }
            deleted = chatDao.deleteMessageSubtree(
                conversationId = conversationId,
                rootMessageId = rootMessageId,
                staleMessageIds = staleMessageIds,
                rootRunIdsToDelete = rootRunIdsToDelete,
                selectedBranchesJson = Json.encodeToString(messageSelections.mapKeys { it.key ?: "null" }),
                selectedRunBranchesJson = Json.encodeToString(runSelections.mapKeys { it.key ?: "null" }),
                at = at,
            )
            if (deleted) {
                scheduled = enqueueMessageAttachmentDebt(
                    staleMessages.map { message -> message.toAttachmentReference() },
                ) or enqueueDebt(MaintenanceDebtEntity.KIND_EMBEDDING_ORPHANS, staleMessageIds) or
                    enqueueDebt(MaintenanceDebtEntity.KIND_RUN_BRANCHES, listOf(conversationId))
            }
        }
        if (scheduled) scheduleMaintenance()
        return deleted
    }

    suspend fun getLiveRun(conversationId: String): RunEntity? =
        chatDao.getLiveRun(conversationId)

    suspend fun requestRunStop(runId: String, at: Long = System.currentTimeMillis()): Boolean =
        withSemanticTransaction(updatedAt = at) {
            val run = chatDao.getRun(runId) ?: return@withSemanticTransaction false
            val changed = chatDao.markRunStopping(runId, at) == 1
            if (changed) check(chatDao.touchConversationData(run.conversationId, at) == 1)
            changed
        }

    suspend fun finishRunStopped(
        runId: String,
        reason: RunEndReason = RunEndReason.USER_STOPPED,
        at: Long = System.currentTimeMillis(),
    ): Boolean = withSemanticTransaction(updatedAt = at) {
        val run = chatDao.getRun(runId) ?: return@withSemanticTransaction false
        val changed = chatDao.terminalizeLiveRun(runId, RunStatus.STOPPED, reason, at) == 1
        if (changed) check(chatDao.touchConversationData(run.conversationId, at) == 1)
        changed
    }

    suspend fun failRun(runId: String, at: Long = System.currentTimeMillis()): Boolean =
        withSemanticTransaction(updatedAt = at) {
            val run = chatDao.getRun(runId) ?: return@withSemanticTransaction false
            val changed = chatDao.terminalizeLiveRun(
                runId,
                RunStatus.FAILED,
                RunEndReason.PROVIDER_ERROR,
                at,
            ) == 1
            if (changed) check(chatDao.touchConversationData(run.conversationId, at) == 1)
            changed
        }

    /**
     * Persist the mutable portion of an in-flight model message without creating a missing row.
     * Returns false when the placeholder was deleted while generation was still unwinding.
     */
    suspend fun updateStreamingMessageCheckpoint(message: ChatMessage): Boolean =
        withSemanticTransaction(listOf(message.id)) {
            val stored = chatDao.getMessage(message.id) ?: return@withSemanticTransaction false
            chatDao.updateConversationMessageCheckpoint(
                conversationId = stored.conversationId,
                checkpoint = message.toStreamCheckpoint(),
                at = System.currentTimeMillis(),
            )
        }

    /** Atomically persists a terminal model snapshot and terminalizes its Run. */
    suspend fun finishGeneration(
        message: ChatMessage,
        conversationId: String,
        runId: String,
        status: RunStatus,
        reason: RunEndReason,
        markConversationUnread: Boolean = false,
        at: Long = System.currentTimeMillis(),
    ): Boolean = withSemanticTransaction(listOf(message.id), at) {
        chatDao.finishGeneration(
            checkpoint = message.toStreamCheckpoint(),
            conversationId = conversationId,
            runId = runId,
            status = status,
            reason = reason,
            at = at,
            markConversationUnread = markConversationUnread,
        )
    }

    /** Atomically persists the final stopped snapshot(s) and terminalizes their Run. */
    suspend fun finishStoppedGeneration(
        messages: List<ChatMessage>,
        runId: String?,
        at: Long = System.currentTimeMillis(),
    ): Boolean {
        val run = runId?.let { chatDao.getRun(it) }
        val conversationId = run?.conversationId
            ?: messages.firstNotNullOfOrNull { message ->
                chatDao.getMessage(message.id)?.conversationId
            }
        // Conversation/Run deletion is itself a durable terminal outcome.
        if (conversationId == null || chatDao.getConversation(conversationId) == null) {
            return runId == null || run == null
        }
        val applied = withSemanticTransaction(messages.map(ChatMessage::id), at) {
            chatDao.finishStoppedGeneration(
                checkpoints = messages.map {
                    it.copy(status = MessageStatus.STOPPED).toStreamCheckpoint()
                },
                conversationId = conversationId,
                runId = runId,
                at = at,
            )
        }
        if (applied) return true
        // Idempotent retry: a previous attempt may have committed but its caller was cancelled
        // before observing the result.
        return runId == null || chatDao.getRun(runId)?.status?.isTerminal != false
    }

    suspend fun deleteMessagesByIds(ids: List<String>) =
        withSemanticTransaction(ids) { chatDao.deleteMessagesByIds(ids) }

    suspend fun beginRecompactContextCompact(
        replacementRun: RunEntity,
        messageId: String,
        modelName: String,
        expectedSelections: Map<String?, String>,
    ): MessageEntity = chatDao.beginRecompactContextCompact(
        replacementRun = replacementRun,
        messageId = messageId,
        modelName = modelName,
        expectedSelectedBranchesJson = Json.encodeToString(
            expectedSelections.mapKeys { it.key ?: "null" },
        ),
    )


    suspend fun removeContextCompact(messageId: String): Boolean {
        var removed = false
        var scheduled = false
        withMaintenanceTransaction {
            val message = chatDao.getMessage(messageId)
            removed = chatDao.removeContextCompact(messageId)
            if (removed && message != null) {
                scheduled = enqueueMessageAttachmentDebt(listOf(message.toAttachmentReference())) or
                    enqueueDebt(
                        MaintenanceDebtEntity.KIND_EMBEDDING_ORPHANS,
                        listOf(messageId),
                    ) or enqueueDebt(
                        MaintenanceDebtEntity.KIND_RUN_BRANCHES,
                        listOf(message.conversationId),
                    )
            }
        }
        if (scheduled) scheduleMaintenance()
        return removed
    }

    suspend fun getMessagesByIds(ids: List<String>): List<MessageEntity> =
        ids.chunked(CONTEXT_MESSAGE_QUERY_PAGE_SIZE).flatMap { page ->
            chatDao.getMessagesByIds(page)
        }

    suspend fun getSearchableMessagesByIds(ids: List<String>): List<MessageEntity> =
        if (ids.isEmpty()) emptyList() else chatDao.getSearchableMessagesByIds(ids)

    suspend fun isMessageSearchable(messageId: String): Boolean =
        chatDao.isMessageSearchable(messageId)

    // ── Branch Selection ──────────────────────────────────────

    suspend fun saveBranchSelections(
        conversationId: String,
        selections: Map<String?, String>,
    ) = branchSelections.saveBranchSelections(conversationId, selections)

    suspend fun restoreBranchSelections(conversationId: String): Map<String?, String> =
        branchSelections.restoreBranchSelections(conversationId)

    suspend fun saveRunBranchSelections(
        conversationId: String,
        selections: Map<String?, String>,
    ) = branchSelections.saveRunBranchSelections(conversationId, selections)

    suspend fun restoreRunBranchSelections(conversationId: String): Map<String?, String> =
        branchSelections.restoreRunBranchSelections(conversationId)

    suspend fun selectRunBranch(
        conversationId: String,
        parentRunId: String?,
        runId: String,
    ) = branchSelections.selectRunBranch(conversationId, parentRunId, runId)

    /** Persists Run and legacy message selection maps in the same row update. */
    suspend fun selectRunBranch(
        conversationId: String,
        parentRunId: String?,
        runId: String,
        messageSelections: Map<String?, String>,
    ) = branchSelections.selectRunBranch(conversationId, parentRunId, runId, messageSelections)

    // ── Embeddings ────────────────────────────────────────────

    suspend fun getOrAdmitSemanticLedgerState(modelId: String): String =
        checkNotNull(database) { "Semantic ledger admission requires the production database" }
            .semanticIndexDao().admitModel(modelId, System.currentTimeMillis()).state
    suspend fun getSemanticLedgers(modelIds: List<String>): List<SemanticIndexLedgerEntity> =
        if (modelIds.isEmpty()) emptyList() else checkNotNull(database) {
            "Semantic ledger reads require the production database"
        }.semanticIndexDao().getLedgers(modelIds)
    suspend fun deleteSemanticModel(modelId: String) {
        checkNotNull(database) { "Semantic model deletion requires the production database" }
            .deleteSemanticModel(modelId)
    }
    suspend fun invalidateSemanticModel(
        modelId: String,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        checkNotNull(database) { "Semantic model invalidation requires the production database" }
            .invalidateSemanticModel(modelId, updatedAt)
    }

    suspend fun commitSemanticEmbedding(
        entity: EmbeddingEntity,
        expectedFingerprint: String,
        updatedAt: Long = System.currentTimeMillis(),
    ): Boolean = checkNotNull(database) {
        "Semantic embedding commit requires the production database"
    }.commitSemanticEmbedding(entity, expectedFingerprint, updatedAt)

    suspend fun findExistingMessageIds(ids: List<String>): List<String> =
        chatDao.findExistingMessageIds(ids)

    suspend fun getEmbeddingSearchPage(
        modelId: String,
        afterId: Long,
        minimumTextLength: Int,
        limit: Int,
    ): List<EmbeddingSearchRow> = chatDao.getEmbeddingSearchPage(
        modelId = modelId,
        afterId = afterId,
        minimumTextLength = minimumTextLength,
        limit = limit,
    )

    suspend fun getEmbeddingCountByModel(modelId: String): Int =
        chatDao.getEmbeddingCountByModel(modelId)

    suspend fun getEmbeddingCountsByModels(modelIds: List<String>): List<EmbeddingModelCount> =
        chatDao.getEmbeddingCountsByModels(modelIds)

    suspend fun getIndexableMessageCount(): Int =
        chatDao.getIndexableMessageCount()

    suspend fun getUnembeddedMessagesPage(
        modelId: String,
        afterId: String?,
        limit: Int,
    ): List<IndexableMessage> =
        chatDao.getUnembeddedMessagesPage(modelId, afterId, limit)

    // ── Search ────────────────────────────────────────────────

    suspend fun searchMessages(query: String, limit: Int = 10): List<MessageEntity> =
        searchConversationMessages(chatDao, query, limit)

    suspend fun getAllConversationsList(): List<ChatEntity> =
        chatDao.getAllConversationsList()

    suspend fun getSearchableConversation(id: String): ChatEntity? =
        chatDao.getSearchableConversation(id)

    suspend fun getSearchableConversationCount(): Int =
        chatDao.getSearchableConversationCount()

    suspend fun getSearchableConversationsPage(
        offset: Int,
        limit: Int,
        descending: Boolean,
    ): List<ChatEntity> = if (descending) {
        chatDao.getSearchableConversationsPageDescending(offset, limit)
    } else {
        chatDao.getSearchableConversationsPageAscending(offset, limit)
    }

    /** Persists the composer draft (text + serialized attachments) for a conversation. */
    suspend fun updateDraft(
        conversationId: String,
        draftText: String,
        draftAttachments: String?,
        reclaimRemovedAttachments: Boolean = true,
    ) {
        var scheduled = false
        withMaintenanceTransaction {
            val previousRaw = chatDao.getConversation(conversationId)?.draftAttachments
            val previous = previousRaw.decodeSelectedAttachments()
            val replacement = draftAttachments.decodeSelectedAttachments()
            chatDao.updateDraft(
                conversationId,
                draftText,
                draftAttachments,
                System.currentTimeMillis(),
            )
            if (reclaimRemovedAttachments) {
                scheduled = when {
                    previousRaw == null -> false
                    previous == null || (draftAttachments != null && replacement == null) ->
                        enqueueAttachmentReconcile()
                    else -> enqueueAttachmentDebt(
                        previous.removedReclaimablePaths(replacement),
                    )
                }
            }
        }
        if (scheduled) scheduleMaintenance()
    }

    /** Enqueues exact paths whose non-draft owner has already been settled by the caller. */
    suspend fun deleteUnreferencedDraftAttachmentFiles(
        attachments: List<SelectedAttachment>,
    ) {
        var scheduled = false
        withMaintenanceTransaction {
            scheduled = enqueueAttachmentDebt(attachments.reclaimablePaths())
        }
        if (scheduled) scheduleMaintenance()
        AttachmentFiles.deleteEmptySandboxParents(
            attachments.filter { it.storage.reclaimWhenAbandoned },
        )
    }

    private suspend fun <T> withSemanticTransaction(
        messageIds: Collection<String>? = null,
        updatedAt: Long = System.currentTimeMillis(),
        conversationId: String? = null,
        clearMessageIds: Collection<String> = emptyList(),
        clearAllEmbeddings: Boolean = false,
        block: suspend () -> T,
    ): T {
        val room = database ?: return block()
        val snapshot = semanticModelSnapshotProvider()
        return when {
            messageIds != null -> room.withSemanticSourceMutation(snapshot, messageIds, updatedAt, block)
            conversationId != null ->
                room.withSemanticEligibilityMutation(snapshot, conversationId, updatedAt, block)
            else -> room.withSemanticGraphMutation(
                snapshot, clearMessageIds, clearAllEmbeddings, updatedAt, block,
            )
        }
    }

    private suspend fun <T> withMaintenanceTransaction(block: suspend () -> T): T =
        database?.withTransaction { block() } ?: block()

    private suspend fun enqueueAttachmentDebt(paths: Collection<String>): Boolean =
        enqueueDebt(MaintenanceDebtEntity.KIND_ATTACHMENT_ORPHANS, paths)

    private suspend fun enqueueAttachmentReconcile(): Boolean = enqueueAttachmentDebt(
        listOf(MaintenanceDebtEntity.RECONCILE_IDENTITY),
    )

    private suspend fun enqueueReconcileDebt(): Boolean {
        var scheduled = false
        for (kind in MaintenanceDebtEntity.RECONCILE_KINDS) {
            scheduled = enqueueDebt(
                kind,
                listOf(MaintenanceDebtEntity.RECONCILE_IDENTITY),
            ) or scheduled
        }
        return scheduled
    }

    private suspend fun enqueueMessageAttachmentDebt(
        references: List<MessageAttachmentReference>,
    ): Boolean {
        if (references.any { reference ->
                val metadata = reference.attachmentMeta
                (metadata == null && reference.images.isNotEmpty()) ||
                    (metadata != null && metadata.decodeAttachmentMeta() == null)
            }
        ) {
            return enqueueAttachmentReconcile()
        }
        return enqueueAttachmentDebt(references.messageReclaimablePaths())
    }

    private suspend fun enqueueDebt(kind: String, identities: Collection<String>): Boolean {
        val dao = maintenanceDebtDao ?: return false
        val exact = identities.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (exact.isEmpty()) return false
        val at = System.currentTimeMillis()
        exact.forEach { identity -> dao.enqueue(kind, identity, at) }
        return true
    }

    private companion object {
        const val ATTACHMENT_REFERENCE_PAGE_SIZE = 128
        const val CONTEXT_MESSAGE_QUERY_PAGE_SIZE = 64
    }
}
