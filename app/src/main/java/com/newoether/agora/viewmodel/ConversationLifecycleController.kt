package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.util.DebugLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates durable conversation metadata changes and deletion cleanup for every client.
 *
 * A deletion is issued by one [ChatClient] (its origin). The origin covers its own page while it
 * shows the conversation; clients still showing it return to its surviving parent, or New Chat
 * when no parent remains. Clients other than the origin are told why first.
 */
internal class ConversationLifecycleController(
    private val conversations: ConversationRepository,
    private val scope: CoroutineScope,
    private val clients: ChatClients,
    private val stopLoop: suspend (String) -> Unit,
    private val tryWithConversationLock: suspend (String, suspend () -> Unit) -> Boolean,
    private val removeRuntime: (String) -> Unit,
    private val stopGeneration: suspend (conversationId: String, origin: ChatClient) -> Unit,
    private val deletedElsewhereText: () -> String,
    private val deletedByParentText: () -> String = deletedElsewhereText,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val pendingDeletes = ConcurrentHashMap<String, Any>()

    fun rename(conversationId: String, newTitle: String) {
        scope.launch {
            conversations.updateConversationTitle(conversationId, newTitle)
        }
    }

    fun setPinned(conversationId: String, pinned: Boolean) {
        scope.launch(ioDispatcher) {
            conversations.setConversationPinned(conversationId, pinned)
        }
    }

    fun delete(
        origin: ChatClient,
        conversationId: String,
        expectedMessageIds: Set<String>? = null,
        onResult: (Boolean) -> Unit = {},
    ): Boolean {
        val request = Any()
        if (clients.isSubmissionFrozen(conversationId) || pendingDeletes.putIfAbsent(conversationId, request) != null) {
            runCatching {
                DebugLog.w(
                    "ConversationLifecycle",
                    "Delete rejected for $conversationId: frozen=${clients.isSubmissionFrozen(conversationId)}, pending=${pendingDeletes.containsKey(conversationId)}",
                )
            }
            return false
        }
        val confirmedMessageIds = expectedMessageIds?.toSet()
        val job = scope.launch(ioDispatcher) {
            var deleted = false
            var viewersAtCommit = emptyList<ChatClient>()
            var parentConversationId: String? = null
            val selectedAtDispatch = origin.openConversationId == conversationId
            var transitionRequestId: Long? = null
            try {
                if (selectedAtDispatch) {
                    // This suspends for the overlay fade, so no destructive storage work can begin
                    // until the underlying selected-page loading surface is visible.
                    transitionRequestId = origin.beginTreeMutation(
                        conversationId = conversationId,
                        scrollToTarget = false,
                    )
                }
                if (clients.isSubmissionFrozen(conversationId)) {
                    runCatching {
                        DebugLog.w("ConversationLifecycle", "Delete aborted for $conversationId: submission frozen")
                    }
                    return@launch
                }
                if (!matchesConfirmation(conversationId, confirmedMessageIds)) {
                    runCatching {
                        DebugLog.w("ConversationLifecycle", "Delete aborted for $conversationId: confirmation mismatch")
                    }
                    return@launch
                }
                // Running execution owns the coordinator until its coroutine finishes. Await the
                // canonical Stop barriers outside that lock, including for nonviewed owners.
                stopLoop(conversationId)
                stopGeneration(conversationId, origin)
                val lockAcquired = tryWithConversationLock(conversationId) {
                    if (clients.isSubmissionFrozen(conversationId)) {
                        runCatching {
                            DebugLog.w("ConversationLifecycle", "Delete aborted for $conversationId: submission frozen in lock")
                        }
                        return@tryWithConversationLock
                    }
                    if (!matchesConfirmation(conversationId, confirmedMessageIds)) {
                        runCatching {
                            DebugLog.w("ConversationLifecycle", "Delete aborted for $conversationId: confirmation mismatch in lock")
                        }
                        return@tryWithConversationLock
                    }
                    if (conversations.getLiveRun(conversationId) != null) {
                        runCatching {
                            DebugLog.w("ConversationLifecycle", "Delete aborted for $conversationId: live run present")
                        }
                        return@tryWithConversationLock
                    }
                    parentConversationId = conversations.getConversation(conversationId)?.parentConversationId
                    viewersAtCommit = clients.showing(conversationId)
                    conversations.deleteConversation(conversationId)
                    deleted = true
                }
                if (!lockAcquired) {
                    runCatching {
                        DebugLog.w("ConversationLifecycle", "Delete aborted for $conversationId: conversation lock busy")
                    }
                }
                if (deleted) {
                    removeRuntime(conversationId)
                    if (viewersAtCommit.isNotEmpty()) {
                        withContext(mainDispatcher) {
                            viewersAtCommit.forEach { viewer ->
                                if (viewer !== origin) viewer.showSnackbar(deletedElsewhereText())
                                // The selection owner rejects settlement over a newer selection.
                                viewer.settleDeletedConversation(conversationId, parentConversationId)
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                runCatching {
                    DebugLog.e(
                        "ConversationLifecycle",
                        "Failed to delete conversation $conversationId",
                        error,
                    )
                }
            } finally {
                withContext(NonCancellable + mainDispatcher) {
                    pendingDeletes.remove(conversationId, request)
                    if (selectedAtDispatch && (!deleted || origin !in viewersAtCommit)) {
                        origin.failTreeMutation(transitionRequestId)
                    }
                    onResult(deleted)
                }
            }
        }
        job.invokeOnCompletion { pendingDeletes.remove(conversationId, request) }
        return true
    }

    /**
     * Deletes an idle Subagent on its parent's agent_delete request. No client issued it, so every
     * client still showing the child is told why and settles to the surviving parent. Unlike
     * [delete] this never stops work: a live Run, frozen submission, concurrent delete or changed
     * ownership rejects the request and leaves the child intact.
     */
    suspend fun deleteIdleSubagent(conversationId: String, parentConversationId: String): Boolean {
        val request = Any()
        if (clients.isSubmissionFrozen(conversationId) || pendingDeletes.putIfAbsent(conversationId, request) != null) {
            return false
        }
        try {
            var deleted = false
            var viewersAtCommit = emptyList<ChatClient>()
            withContext(ioDispatcher) {
                tryWithConversationLock(conversationId) {
                    if (clients.isSubmissionFrozen(conversationId)) return@tryWithConversationLock
                    if (conversations.getLiveRun(conversationId) != null) return@tryWithConversationLock
                    val child = conversations.getConversation(conversationId)
                    if (child?.parentConversationId != parentConversationId) return@tryWithConversationLock
                    viewersAtCommit = clients.showing(conversationId)
                    conversations.deleteConversation(conversationId)
                    deleted = true
                }
            }
            if (deleted) {
                removeRuntime(conversationId)
                if (viewersAtCommit.isNotEmpty()) {
                    withContext(NonCancellable + mainDispatcher) {
                        viewersAtCommit.forEach { viewer ->
                            viewer.showSnackbar(deletedByParentText())
                            viewer.settleDeletedConversation(conversationId, parentConversationId)
                        }
                    }
                }
            }
            return deleted
        } finally {
            pendingDeletes.remove(conversationId, request)
        }
    }

    private suspend fun matchesConfirmation(conversationId: String, expectedMessageIds: Set<String>?): Boolean =
        expectedMessageIds == null || conversations.getMessageTopologySnapshot(conversationId)
            .mapTo(linkedSetOf()) { it.id } == expectedMessageIds
}
