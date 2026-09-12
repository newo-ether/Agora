package com.newoether.agora.data.local

import androidx.room.*
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ConversationCommand
import com.newoether.agora.model.ConversationRuntimeReducer
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunRecoveryPolicy
import com.newoether.agora.model.RunRecoverySnapshot
import com.newoether.agora.model.RunState
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private fun decodeSelectionMap(raw: String?): MutableMap<String?, String> =
    raw?.let {
        runCatching {
            Json.decodeFromString<Map<String, String>>(it)
                .mapKeysTo(mutableMapOf()) { entry ->
                    if (entry.key == "null") null else entry.key
                }
        }.getOrDefault(mutableMapOf())
    } ?: mutableMapOf()

private fun encodeSelectionMap(selections: Map<String?, String>): String =
    Json.encodeToString(selections.mapKeys { it.key ?: "null" })

data class EmbeddingModelCount(
    val modelId: String,
    val count: Int,
)

@Dao
interface ChatDao :
    ChatCoreDao,
    ChatEmbeddingDao,
    ChatSearchDao,
    ChatExportImportDao,
    ChatDraftDao,
    ChatModelReferenceDao,
    ChatAutomationDao,
    ChatContextCompactDao,
    ChatProviderContextDao,
    NewChatPersistDao,
    ChatHeartbeatSmsDao {
    // ── Transactional methods with business logic (kept in ChatDao) ──────────

    /**
     * Atomically removes one structural message subtree and only those Runs that become wholly
     * empty. [rootRunIdsToDelete] must be CASCADE-safe roots planned from the same locked
     * snapshot; a partially retained Run continues to own its shared boundary USER.
     * Attachment files are intentionally deleted only after this transaction commits.
     */
    @Transaction
    suspend fun deleteMessageSubtree(
        conversationId: String,
        rootMessageId: String,
        staleMessageIds: List<String>,
        rootRunIdsToDelete: List<String>,
        selectedBranchesJson: String,
        selectedRunBranchesJson: String,
        at: Long,
    ): Boolean {
        val root = getMessage(rootMessageId) ?: return false
        require(root.conversationId == conversationId) {
            "Message $rootMessageId does not belong to conversation $conversationId"
        }
        require(rootMessageId in staleMessageIds)
        if (staleMessageIds.isNotEmpty()) {
            deleteEmbeddingsByMessageIds(staleMessageIds)
        }
        check(
            updateSelectionsForRunDeletion(
                conversationId,
                selectedBranchesJson,
                selectedRunBranchesJson,
                at,
            ) == 1
        ) { "Conversation $conversationId disappeared during branch deletion" }
        for (runId in rootRunIdsToDelete) {
            val run = getRun(runId) ?: continue
            require(run.conversationId == conversationId) {
                "Run $runId does not belong to conversation $conversationId"
            }
            check(deleteRun(runId) == 1) { "Run $runId disappeared during deletion" }
        }
        deleteMessagesByIds(staleMessageIds)
        return true
    }

    @Transaction
    suspend fun createRunWithMessages(
        run: RunEntity,
        messages: List<MessageEntity>,
        messageSelectionUpdates: Map<String?, String>,
        conversationModelId: String,
        at: Long,
        touchConversationOnAdmission: Boolean,
    ): RunGraphCommit {
        require(run.status == RunStatus.ACTIVE)
        require(run.activeSlot == 1)
        require(messages.isNotEmpty())
        require(conversationModelId.isNotBlank())
        require(messages.all { it.runId == run.id })
        require(messages.map { it.runSequence } == messages.indices.map { it.toLong() })
        val conversation = checkNotNull(getConversation(run.conversationId)) {
            "Conversation ${run.conversationId} does not exist"
        }
        check(getLiveRun(run.conversationId) == null) {
            "Conversation ${run.conversationId} already has a live Run"
        }
        val insertedMessageIds = messages.mapTo(mutableSetOf()) { it.id }
        require(messageSelectionUpdates.values.all { it in insertedMessageIds }) {
            "A new Run may only select messages committed in the same transaction"
        }
        insertRun(run)
        messages.forEach { insertMessage(it) }

        val messageSelections = decodeSelectionMap(conversation.selectedBranchesJson).apply {
            putAll(messageSelectionUpdates)
        }
        val runSelections = decodeSelectionMap(conversation.selectedRunBranchesJson).apply {
            put(run.parentRunId, run.id)
        }
        check(
            updateConversationForRunAdmission(
                conversationId = run.conversationId,
                selectedBranchesJson = encodeSelectionMap(messageSelections),
                selectedRunBranchesJson = encodeSelectionMap(runSelections),
                modelId = conversationModelId,
                at = at,
                touchConversationOnAdmission = touchConversationOnAdmission,
            ) == 1
        ) { "Conversation ${run.conversationId} disappeared during Run creation" }
        return RunGraphCommit(messages, messageSelections, runSelections)
    }

    /**
     * First Send in a new chat is one durable acceptance boundary. A failed Run/message insert
     * must not leave an empty conversation row behind.
     */
    @Transaction
    suspend fun createConversationRunWithMessages(
        conversation: ChatEntity,
        run: RunEntity,
        messages: List<MessageEntity>,
        messageSelectionUpdates: Map<String?, String>,
        conversationModelId: String,
        conversationSettingsJson: String?,
        expectedNewChatPersist: NewChatPersistEntity?,
        at: Long,
    ): RunGraphCommit {
        require(conversation.id == run.conversationId)
        check(getConversation(conversation.id) == null) {
            "Conversation ${conversation.id} already exists"
        }
        require(conversationModelId.isNotBlank())
        expectedNewChatPersist?.let { expected ->
            deleteNewChatPersistIfMatches(
                modelId = expected.modelId,
                systemPromptId = expected.systemPromptId,
                conversationSettingsJson = expected.conversationSettingsJson,
                draftText = expected.draftText,
                draftAttachments = expected.draftAttachments,
            )
        }
        upsertConversation(
            conversation.copy(
                modelId = conversationModelId,
                lastUpdated = at,
            )
        )
        upsertConversationSettingsTransfer(
            ConversationSettingsTransferEntity(
                conversationId = conversation.id,
                settingsJson = conversationSettingsJson,
            )
        )
        return createRunWithMessages(
            run,
            messages,
            messageSelectionUpdates,
            conversationModelId,
            at,
            touchConversationOnAdmission = true,
        )
    }

    @Transaction
    suspend fun importRunGraph(runs: List<RunEntity>, messages: List<MessageEntity>) {
        require(runs.all { it.status.isTerminal }) {
            "Imported Runs must be terminal"
        }
        val incomingRunIds = runs.mapTo(mutableSetOf()) { it.id }
        require(messages.all { it.runId in incomingRunIds || getRun(it.runId) != null }) {
            "Every imported message must reference an imported or existing Run"
        }
        for (run in runs) {
            if (getRun(run.id) == null) insertRun(run)
        }
        messages.forEach { upsertMessage(it) }
    }

    @Transaction
    suspend fun replaceImportedConversationGraph(
        conversations: List<ChatEntity>,
        runs: List<RunEntity>,
        messages: List<MessageEntity>,
    ) {
        val conversationIds = conversations.mapTo(mutableSetOf(), ChatEntity::id)
        val runIds = runs.mapTo(mutableSetOf(), RunEntity::id)
        require(conversationIds.size == conversations.size) { "Imported conversation IDs must be unique" }
        require(runIds.size == runs.size) { "Imported Run IDs must be unique" }
        require(messages.mapTo(mutableSetOf(), MessageEntity::id).size == messages.size) {
            "Imported message IDs must be unique"
        }
        val runsById = runs.associateBy(RunEntity::id)
        val messagesById = messages.associateBy(MessageEntity::id)
        require(runs.all {
            it.status.isTerminal &&
                it.activeSlot == null &&
                it.conversationId in conversationIds
        }) {
            "Every imported Run must be terminal and belong to an imported conversation"
        }
        require(runs.all { run ->
            run.parentRunId == null ||
                runsById[run.parentRunId]?.conversationId == run.conversationId
        }) {
            "Every imported parent Run must belong to the same replacement conversation"
        }
        require(messages.all { message ->
            message.conversationId in conversationIds &&
                runsById[message.runId]?.conversationId == message.conversationId &&
                (message.parentId == null ||
                    messagesById[message.parentId]?.conversationId == message.conversationId)
        }) {
            "Every imported message and parent must belong to its replacement conversation"
        }
        deleteAllConversations()
        conversations.forEach { upsertConversation(it) }
        runs.forEach { insertRun(it) }
        messages.forEach { upsertMessage(it) }
    }

    /**
     * Creates a fork as one database commit. A cancelled or rejected import must never leave
     * an empty conversation behind.
     */
    @Transaction
    suspend fun createForkGraph(
        conversation: ChatEntity,
        runs: List<RunEntity>,
        messages: List<MessageEntity>,
        sourceToForkMessageIds: Map<String, String>,
    ) {
        require(getConversation(conversation.id) == null) {
            "Fork conversation ${conversation.id} already exists"
        }
        require(runs.isNotEmpty())
        require(messages.isNotEmpty())
        require(runs.all { it.conversationId == conversation.id })
        require(messages.all { it.conversationId == conversation.id })
        val runIds = runs.mapTo(mutableSetOf()) { it.id }
        val messageIds = messages.mapTo(mutableSetOf()) { it.id }
        require(runs.all { it.parentRunId == null || it.parentRunId in runIds }) {
            "Every forked Run must reference another forked Run"
        }
        require(messages.all { it.parentId == null || it.parentId in messageIds }) {
            "Every forked message must reference another forked message"
        }
        require(messages.all { it.runId in runIds }) {
            "Every forked message must reference a forked Run"
        }
        require(sourceToForkMessageIds.size == messages.size)
        require(sourceToForkMessageIds.values.toSet() == messageIds) {
            "Every forked message must have exactly one source message"
        }
        require(sourceToForkMessageIds.keys.intersect(messageIds).isEmpty()) {
            "Forked messages must not reuse source message identities"
        }
        val clonedEmbeddings = ForkEmbeddingClonePolicy.cloneAll(
            sourceEmbeddings = getEmbeddingsByMessageIds(sourceToForkMessageIds.keys.toList()),
            sourceToForkMessageIds = sourceToForkMessageIds,
        )
        upsertConversation(conversation)
        importRunGraph(runs, messages)
        if (clonedEmbeddings.isNotEmpty()) {
            val insertedIds = insertEmbeddings(clonedEmbeddings)
            check(insertedIds.size == clonedEmbeddings.size && insertedIds.all { it > 0L }) {
                "Every forked embedding must be inserted as a new database row"
            }
        }
    }

    /** A provider tool round is protocol-atomic: assistant tool_calls and every result commit
     * together, or none of them do. */
    @Transaction
    suspend fun appendToolRoundToRun(
        messages: List<MessageEntity>,
        expectedPass: Int,
    ): ToolRoundCommit {
        require(expectedPass >= 0)
        val runId = ToolRoundCommitPolicy.requireValidShape(messages)
        ToolRoundCommitPolicy.resolveExactReplay(
            proposed = messages,
            // Avoid SQLite's bound-parameter ceiling for a malformed/extreme provider batch.
            existing = messages.mapNotNull { message -> getMessage(message.id) },
        )?.let { existing ->
            return ToolRoundCommit(existing, inserted = false)
        }
        val run = getRun(runId) ?: error("Run $runId does not exist")
        check(ToolRoundCommitPolicy.canInsert(run, runId, expectedPass)) {
            "Cannot append tool round to non-current Run $runId Pass $expectedPass"
        }
        val firstSequence = nextRunSequence(runId)
        val assigned = messages.mapIndexed { index, message ->
            message.copy(runSequence = firstSequence + index)
        }
        assigned.forEach { insertMessage(it) }
        touchRun(runId, maxOf(run.lastCheckpointAt, assigned.maxOf { it.timestamp }))
        return ToolRoundCommit(assigned, inserted = true)
    }

    @Query(
        """
        UPDATE runs
        SET status = 'STOPPING', stopRequestedAt = :at, lastCheckpointAt = :at
        WHERE id = :runId AND status = 'ACTIVE' AND activeSlot = 1
        """
    )
    suspend fun markRunStopping(runId: String, at: Long): Int

    @Query(
        """
        UPDATE runs
        SET status = :status, activeSlot = NULL, lastCheckpointAt = :at, endedAt = :at,
            endReason = :reason
        WHERE id = :runId AND activeSlot = 1
        """
    )
    suspend fun terminalizeLiveRun(
        runId: String,
        status: RunStatus,
        reason: RunEndReason,
        at: Long,
    ): Int

    @Query(
        """
        UPDATE messages
        SET status = 'STOPPED'
        WHERE runId = :runId
          AND participant = 'MODEL'
          AND status IN ('SENDING', 'THINKING', 'TOOL_CALLING', 'TRANSCRIBING')
        """
    )
    suspend fun stopInFlightModelMessages(runId: String): Int

    /**
     * Normal/error provider completion is one durable boundary: the model row and its Run become
     * terminal together. The update counts are returned as a boolean for diagnostics, but a
     * missing row never leaves an otherwise-live Run stranded.
     */
    @Transaction
    suspend fun finishGeneration(
        checkpoint: MessageStreamCheckpoint,
        conversationId: String,
        runId: String,
        status: RunStatus,
        reason: RunEndReason,
        at: Long,
        markConversationUnread: Boolean,
    ): Boolean {
        require(status.isTerminal)
        val messageUpdated = updateMessageCheckpoint(checkpoint) == 1
        val runUpdated = terminalizeLiveRun(runId, status, reason, at) == 1
        val completed = messageUpdated && runUpdated
        if (completed && markConversationUnread) {
            setConversationUnreadGeneration(conversationId, true)
        }
        return completed
    }

    /**
     * The only user-Stop terminal writer. Checkpoints and Run terminalization commit together, so
     * tree operations never observe a STOPPED message inside a still-live Run (or the inverse).
     */
    @Transaction
    suspend fun finishStoppedGeneration(
        checkpoints: List<MessageStreamCheckpoint>,
        runId: String?,
        at: Long,
    ): Boolean {
        checkpoints.forEach { updateMessageCheckpoint(it) }
        if (runId != null) stopInFlightModelMessages(runId)
        return runId == null || terminalizeLiveRun(
            runId,
            RunStatus.STOPPED,
            RunEndReason.USER_STOPPED,
            at,
        ) == 1
    }

    /**
     * Recovers only [conversationId]. The caller must own or have proven the absence of this
     * conversation's process execution lease, so a live Run here is necessarily process-orphaned.
     */
    @Transaction
    suspend fun recoverConversationRuntime(
        conversationId: String,
        at: Long,
    ): Int {
        val conversation = getConversation(conversationId) ?: return 0
        var changedRows = 0
        val liveRun = getLiveRun(conversationId)
        if (liveRun != null) {
            val snapshot = RunRecoverySnapshot(
                conversationId = conversationId,
                runId = liveRun.id,
                pass = liveRun.currentPass,
                status = liveRun.status,
            )
            val requested = ConversationRuntimeReducer.reduce(
                RunState.Idle(conversationId),
                ConversationCommand.Recover(snapshot),
            )
            val recoveryEffect = requested.effects
                .filterIsInstance<RunEffect.RecoverDurableRun>()
                .single()
            check(recoveryEffect.priorStatus == liveRun.status)
            getMessagesForRuns(listOf(liveRun.id)).forEach { message ->
                val recoveredStatus = RunRecoveryPolicy.recoverMessageStatus(
                    message.participant,
                    message.status,
                )
                val recoveredToolJson = message.toolCallJson?.let { raw ->
                    RunRecoveryPolicy.stopIncompleteToolsJson(raw) ?: raw
                }
                if (recoveredStatus != message.status || recoveredToolJson != message.toolCallJson) {
                    changedRows += updateMessageCheckpoint(
                        MessageStreamCheckpoint(
                            id = message.id,
                            text = message.text,
                            images = message.images,
                            thoughts = message.thoughts,
                            thoughtTitle = message.thoughtTitle,
                            tokenCount = message.tokenCount,
                            inputTokenCount = message.inputTokenCount,
                            cachedInputTokenCount = message.cachedInputTokenCount,
                            cacheWriteInputTokenCount = message.cacheWriteInputTokenCount,
                            uncachedInputTokenCount = message.uncachedInputTokenCount,
                            outputTokenCount = message.outputTokenCount,
                            reasoningTokenCount = message.reasoningTokenCount,
                            status = recoveredStatus,
                            thoughtTimeMs = message.thoughtTimeMs,
                            toolCallJson = recoveredToolJson,
                        )
                    )
                }
            }
            val durableSuccess = terminalizeLiveRun(
                runId = recoveryEffect.identity.runId,
                status = RunStatus.STOPPED,
                reason = RunEndReason.PROCESS_RECOVERED,
                at = at,
            ) == 1
            val completed = ConversationRuntimeReducer.reduce(
                requested.newState,
                ConversationCommand.RecoveryCompleted(
                    recoveryEffect.identity,
                    durableSuccess,
                ),
            )
            check(durableSuccess && completed.accepted && completed.newState is RunState.Idle) {
                "Run recovery transaction lost ownership for ${liveRun.id}"
            }
            changedRows += 1
        }

        conversation.selectedRunBranchesJson?.let { raw ->
            val decoded = runCatching {
                Json.decodeFromString<Map<String, String>>(raw)
                    .mapKeys { if (it.key == "null") null else it.key }
            }.getOrNull()
            if (decoded != null) {
                val repaired = RunBranchSelectionIntegrity.retainValidEdges(
                    selections = decoded,
                    runs = getRunsForConversationSnapshot(conversationId),
                )
                if (repaired != decoded) {
                    changedRows += compareAndSetRunBranchSelections(
                        conversationId = conversationId,
                        expected = raw,
                        replacement = encodeSelectionMap(repaired),
                    )
                }
            }
        }
        changedRows += stopStuckMessagesForConversation(conversationId)
        return changedRows
    }

    @Update(entity = MessageEntity::class)
    suspend fun updateMessageCheckpoint(checkpoint: MessageStreamCheckpoint): Int

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("UPDATE runs SET parentRunId = :newParentRunId WHERE id = :runId")
    suspend fun updateRunParent(runId: String, newParentRunId: String?): Int

    @Query("DELETE FROM embeddings WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId)")
    suspend fun deleteEmbeddingsByConversation(conversationId: String)

    @Query("DELETE FROM embeddings WHERE messageId LIKE 'compact_%' OR NOT EXISTS (SELECT 1 FROM messages WHERE messages.id = embeddings.messageId)")
    suspend fun deleteOrphanedEmbeddings()
}