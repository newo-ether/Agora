package com.newoether.agora.viewmodel

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Executes one idle-only durable message-branch or Compact-boundary deletion. */
internal class ConversationBranchMutationService(
    private val scope: CoroutineScope,
    private val conversations: ConversationRepository,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val toUiMessage: (MessageEntity) -> ChatMessage,
    private val isConversationOpen: (String) -> Boolean,
    private val projectGraph: (
        conversationId: String,
        messages: List<ChatMessage>,
        selectedChildren: Map<String?, String>,
    ) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val resultDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    fun delete(
        conversationId: String,
        messageId: String,
        state: ConversationGenerationState,
        snapshot: List<ChatMessage>,
        /** Client that issued the delete; it covers the tree mutation until its path settles. */
        origin: ChatClient,
        onResult: ((Boolean) -> Unit)? = null,
    ): Int {
        if (state.generating.value) {
            onResult?.invoke(false)
            return 0
        }
        if (snapshot.none { it.id == messageId }) {
            onResult?.invoke(false)
            return 0
        }
        val compactOnly = messageId.startsWith(Constants.COMPACT_MSG_PREFIX)
        val previewIds = if (compactOnly) {
            setOf(messageId)
        } else {
            structuralDescendantIds(snapshot, messageId)
        }

        scope.launch(ioDispatcher) {
            var switchingRequestId: Long? = null
            var committed = false
            try {
                switchingRequestId = origin.beginTreeMutation(conversationId, !compactOnly)
                val queueLocked = timedDeleteStage("queue-lock", messageId) {
                    state.queueMutationMutex.tryLock()
                }
                if (!queueLocked) return@launch
                try {
                    // Recheck after the overlay fade and under the same mutex that accepts Send.
                    if (state.generating.value) return@launch
                    executionCoordinator.tryWithConversationLock(conversationId) lock@ {
                        if (conversations.getLiveRun(conversationId) != null) return@lock
                        if (compactOnly) {
                            timedDeleteStage("delete-transaction", messageId) {
                                check(conversations.removeContextCompact(messageId))
                            }
                            val (remainingChatMessages, selections) =
                                timedDeleteStage("topology-reload", messageId) {
                                    val messages = conversations
                                        .getMessageTopologySnapshot(conversationId)
                                        .map { message -> message.toUiChatMessageStub() }
                                    messages to conversations.restoreBranchSelections(conversationId)
                                }
                            origin.settleTreeMutation(switchingRequestId, null)
                            if (isConversationOpen(conversationId)) {
                                projectGraph(conversationId, remainingChatMessages, selections)
                            }
                            committed = true
                            return@lock
                        }

                        val runs = conversations.getRunsForConversationSnapshot(conversationId)
                        val topology = timedDeleteStage("topology-reload", messageId) {
                            conversations.getMessageTopologySnapshot(conversationId)
                        }
                        val allChatMessages = topology.map { message ->
                            message.toUiChatMessageStub()
                        }
                        val previousSelected =
                            conversations.restoreBranchSelections(conversationId)
                        val previousRunSelections =
                            conversations.restoreRunBranchSelections(conversationId)
                        val plan = BranchDeletionPlanner.planTopology(
                            rootMessageId = messageId,
                            messages = topology,
                            runs = runs,
                            messageSelections = previousSelected,
                            runSelections = previousRunSelections,
                        )
                        val remainingMessages = topology
                            .filter { it.id !in plan.deletedMessageIds }
                            .map { message -> message.toUiChatMessageStub() }
                        timedDeleteStage("delete-transaction", messageId) {
                            check(
                                conversations.deleteMessageSubtree(
                                    conversationId = conversationId,
                                    rootMessageId = messageId,
                                    staleMessageIds = plan.deletedMessageIds.toList(),
                                    rootRunIdsToDelete = plan.rootRunIdsToDelete.toList(),
                                    messageSelections = plan.messageSelections,
                                    runSelections = plan.runSelections,
                                )
                            ) { "Message $messageId disappeared during delete" }
                        }

                        val remainingPath = ConversationUiState.resolvePath(
                            allMessages = remainingMessages,
                            streamingMsg = null,
                            selectedChildren = plan.messageSelections,
                        )
                        val targetAfterDelete = deleteSettlementTargetMessageId(
                            messagesBeforeDelete = allChatMessages,
                            deletedRootMessageId = messageId,
                            remainingPath = remainingPath,
                        )
                        origin.settleTreeMutation(switchingRequestId, targetAfterDelete)
                        if (isConversationOpen(conversationId)) {
                            projectGraph(conversationId, remainingMessages, plan.messageSelections)
                        }
                        committed = true
                    }
                } finally {
                    state.queueMutationMutex.unlock()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DebugLog.e("AgoraVM", "Failed to delete message branch $messageId", error)
            } finally {
                timedDeleteStage("main-callback", messageId) {
                    withContext(NonCancellable + resultDispatcher) {
                        if (!committed) origin.failTreeMutation(switchingRequestId)
                        onResult?.invoke(committed)
                    }
                }
            }
        }

        return previewIds.size
    }

    private inline fun <T> timedDeleteStage(stage: String, messageId: String, block: () -> T): T {
        val startedAt = System.nanoTime()
        try {
            return block()
        } finally {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            runCatching {
                DebugLog.d("AgoraVM", "delete stage=$stage message=$messageId elapsedMs=$elapsedMs")
            }
        }
    }

    private fun structuralDescendantIds(
        messages: List<ChatMessage>,
        rootMessageId: String,
    ): Set<String> {
        val childrenByParent = messages.groupBy { it.parentId }
        val descendants = linkedSetOf(rootMessageId)
        val pending = ArrayDeque<String>().apply { add(rootMessageId) }
        while (pending.isNotEmpty()) {
            for (child in childrenByParent[pending.removeFirst()].orEmpty()) {
                if (descendants.add(child.id)) pending.add(child.id)
            }
        }
        return descendants
    }
}
