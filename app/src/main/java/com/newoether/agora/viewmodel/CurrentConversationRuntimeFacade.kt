package com.newoether.agora.viewmodel

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** Read-only current-runtime projection plus queued-guidance removal intent. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class CurrentConversationRuntimeFacade(
    private val currentConversationId: StateFlow<String?>,
    private val registry: ConversationStateRegistry,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val queuedSends: StateFlow<List<QueuedSend>> = currentConversationId
        .flatMapLatest { conversationId ->
            conversationId?.let { registry.getOrCreate(it).queuedSends } ?: flowOf(emptyList())
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val isStopping: StateFlow<Boolean> = currentConversationId
        .flatMapLatest { conversationId ->
            conversationId?.let { registry.getOrCreate(it).stopping } ?: flowOf(false)
        }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Manual queue send. The automatic drain runs when a generation settles; this is the user asking
     * for that same drain while nothing is generating, so a batch left behind by a failed compact or
     * a failed drain can still be delivered without typing a new message.
     */
    fun requestQueueDrain() {
        val conversationId = currentConversationId.value ?: return
        val state = registry.getOrCreate(conversationId)
        if (state.generating.value || state.queuedSends.value.isEmpty()) return
        state.onQueueDrainRequested?.invoke(state)
    }

    fun removeQueuedSend(queuedSendId: String) {
        val conversationId = currentConversationId.value ?: return
        val state = registry.getOrCreate(conversationId)
        scope.launch(ioDispatcher) {
            state.queueMutationMutex.withLock {
                state.removeQueuedSend(queuedSendId)?.deleteOwnedFiles()
            }
        }
    }
}
