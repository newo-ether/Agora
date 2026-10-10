package com.newoether.agora.viewmodel
import com.newoether.agora.data.repository.updateConversationModel

import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.local.NewChatPersistEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import com.newoether.agora.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val NEW_CHAT_WORKSPACE_ID = "agora:new-chat"

internal data class ConversationWorkspaceDraft(
    val text: String,
    val attachmentsJson: String?,
)

internal data class NewChatWorkspaceSnapshot(
    val persisted: NewChatPersistEntity?,
    val modelId: String?,
    val systemPromptId: String?,
    val conversationSettings: ConversationSettings?,
    /**
     * True for a WebUI session's in-memory New Chat workspace. Its send must not touch the
     * phone's persisted New Chat singleton, so admission carries no persist snapshot for it.
     */
    val sessionLocal: Boolean = false,
    private val pendingPersisted: CompletableDeferred<NewChatPersistEntity?>? = null,
) {
    suspend fun awaitCaptured(): NewChatWorkspaceSnapshot =
        pendingPersisted?.let { fromPersisted(it.await()) } ?: this

    companion object {
        fun fromPersisted(entity: NewChatPersistEntity?) = NewChatWorkspaceSnapshot(
            persisted = entity,
            modelId = entity?.modelId,
            systemPromptId = entity?.systemPromptId,
            conversationSettings = entity?.conversationSettingsJson?.let { raw ->
                runCatching { Json.decodeFromString<ConversationSettings>(raw) }.getOrNull()
            },
        )

        fun pending(
            current: NewChatPersistEntity?,
            completion: CompletableDeferred<NewChatPersistEntity?>,
        ) = fromPersisted(current).copy(pendingPersisted = completion)
    }
}

internal interface ComposerDraftPersistence {
    suspend fun loadDraft(ownerId: String): ConversationWorkspaceDraft

    suspend fun updateDraft(
        ownerId: String,
        text: String,
        attachmentsJson: String?,
    )

    suspend fun clearAcceptedDraft(ownerId: String)

    suspend fun clearAcceptedDraft(
        ownerId: String,
        reclaimAttachments: Boolean,
    ) = clearAcceptedDraft(ownerId)

    suspend fun clearAcceptedDraft(
        ownerId: String,
        reclaimAttachments: Boolean,
        expectedWorkspace: NewChatPersistEntity?,
    ) = clearAcceptedDraft(ownerId, reclaimAttachments)
}

/** Routes mutable conversation workspace state to Room or the New Chat singleton. */
internal class ConversationWorkspaceStore(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val transfers: ConversationSettingsTransferCoordinator,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ComposerDraftPersistence {
    private sealed interface NewChatCommand {
        data class Update(
            val transform: (NewChatPersistEntity) -> NewChatPersistEntity,
            val completion: CompletableDeferred<Unit>?,
        ) : NewChatCommand

        data class Read(
            val completion: CompletableDeferred<NewChatPersistEntity?>,
        ) : NewChatCommand

        data class Clear(
            val reclaimAttachments: Boolean,
            val expectedWorkspace: NewChatPersistEntity?,
            val completion: CompletableDeferred<Unit>,
        ) : NewChatCommand
    }

    private val newChatCommands = Channel<NewChatCommand>(Channel.UNLIMITED)
    private val newChatInitialLoad = CompletableDeferred<Unit>()
    private val newChatStateLock = Any()
    private var pendingNewChatWrites = 0
    private val _newChatPersist = MutableStateFlow<NewChatPersistEntity?>(null)
    private val conversationMutationMutex = Mutex()

    val newChatPersist: StateFlow<NewChatPersistEntity?> = _newChatPersist.asStateFlow()
    val newChatModelId: StateFlow<String?> = newChatPersist
        .map { it?.modelId }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val newChatSystemPromptId: StateFlow<String?> = newChatPersist
        .map { it?.systemPromptId }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val newChatConversationSettings: StateFlow<ConversationSettings?> = newChatPersist
        .map { entity -> decodeConversationSettings(entity?.conversationSettingsJson) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch(ioDispatcher) {
            try {
                val initial = conversations.getNewChatPersist()
                synchronized(newChatStateLock) {
                    _newChatPersist.value = initial
                }
                newChatInitialLoad.complete(Unit)
                for (command in newChatCommands) {
                    processNewChatCommand(command)
                }
            } catch (cancelled: CancellationException) {
                newChatInitialLoad.completeExceptionally(cancelled)
                newChatCommands.close(cancelled)
                throw cancelled
            } catch (error: Exception) {
                newChatInitialLoad.completeExceptionally(error)
                newChatCommands.close(error)
            }
        }
        scope.launch(ioDispatcher) {
            newChatInitialLoad.await()
            conversations.observeNewChatPersist().collect { persisted ->
                synchronized(newChatStateLock) {
                    // First Send deletes the Room singleton inside its graph transaction. Keep the
                    // in-memory workspace alive until the committed conversation has inherited its
                    // state and the selection owner has been published explicitly.
                    if (pendingNewChatWrites == 0 && persisted != null) {
                        _newChatPersist.value = persisted
                    }
                }
            }
        }
    }

    fun setModel(ownerId: String, modelId: String?) {
        if (ownerId == NEW_CHAT_WORKSPACE_ID) {
            enqueueNewChatUpdate { it.copy(modelId = modelId) }
        } else {
            scope.launch(ioDispatcher) {
                conversationMutationMutex.withLock { conversations.updateConversationModel(ownerId, modelId) }
            }
        }
    }

    fun setSystemPrompt(ownerId: String, promptId: String?) {
        if (ownerId == NEW_CHAT_WORKSPACE_ID) {
            enqueueNewChatUpdate { it.copy(systemPromptId = promptId) }
        } else {
            scope.launch(ioDispatcher) {
                conversationMutationMutex.withLock { conversations.updateConversationSystemPrompt(ownerId, promptId) }
            }
        }
    }

    fun setConversationSettings(ownerId: String, value: ConversationSettings?) {
        if (ownerId == NEW_CHAT_WORKSPACE_ID) {
            enqueueNewChatUpdate {
                it.copy(
                    conversationSettingsJson = value?.let { settings ->
                        Json.encodeToString(settings)
                    },
                )
            }
        } else {
            settings.setConversationSettings(ownerId, value)
        }
    }

    fun updateConversationSettings(
        ownerId: String,
        transform: (ConversationSettings) -> ConversationSettings,
    ) {
        if (ownerId == NEW_CHAT_WORKSPACE_ID) {
            enqueueNewChatUpdate { entity ->
                val current = decodeConversationSettings(entity.conversationSettingsJson)
                    ?: ConversationSettings()
                entity.copy(conversationSettingsJson = Json.encodeToString(transform(current)))
            }
        } else {
            settings.updateConversationSettings(ownerId, transform)
        }
    }

    override suspend fun loadDraft(ownerId: String): ConversationWorkspaceDraft =
        if (ownerId == NEW_CHAT_WORKSPACE_ID) {
            val entity = readNewChatPersist()
            ConversationWorkspaceDraft(
                text = entity?.draftText.orEmpty(),
                attachmentsJson = entity?.draftAttachments,
            )
        } else {
            val entity = conversations.getConversation(ownerId)
            ConversationWorkspaceDraft(
                text = entity?.draftText.orEmpty(),
                attachmentsJson = entity?.draftAttachments,
            )
        }

    override suspend fun updateDraft(
        ownerId: String,
        text: String,
        attachmentsJson: String?,
    ) {
        if (ownerId == NEW_CHAT_WORKSPACE_ID) {
            updateNewChatAndAwait {
                it.copy(draftText = text, draftAttachments = attachmentsJson)
            }
        } else {
            withContext(Dispatchers.IO) {
                conversationMutationMutex.withLock {
                    conversations.updateDraft(ownerId, text, attachmentsJson)
                }
            }
        }
    }

    override suspend fun clearAcceptedDraft(ownerId: String) {
        clearAcceptedDraft(ownerId, reclaimAttachments = true)
    }

    override suspend fun clearAcceptedDraft(
        ownerId: String,
        reclaimAttachments: Boolean,
    ) = clearAcceptedDraft(ownerId, reclaimAttachments, expectedWorkspace = null)

    override suspend fun clearAcceptedDraft(
        ownerId: String,
        reclaimAttachments: Boolean,
        expectedWorkspace: NewChatPersistEntity?,
    ) {
        if (ownerId == NEW_CHAT_WORKSPACE_ID) {
            clearNewChatAndAwait(reclaimAttachments, expectedWorkspace)
        } else {
            withContext(Dispatchers.IO) {
                conversationMutationMutex.withLock {
                    conversations.updateDraft(
                        conversationId = ownerId,
                        draftText = "",
                        draftAttachments = null,
                        reclaimRemovedAttachments = reclaimAttachments,
                    )
                }
            }
        }
    }

    /**
     * Inserts an ordered read barrier at Send tap time. The eventual snapshot includes every
     * workspace mutation already queued at that tap and excludes every later New Chat edit.
     */
    fun captureNewChatSnapshot(): NewChatWorkspaceSnapshot {
        val completion = CompletableDeferred<NewChatPersistEntity?>()
        val current = synchronized(newChatStateLock) { _newChatPersist.value }
        val result = newChatCommands.trySend(NewChatCommand.Read(completion))
        if (result.isFailure) {
            completion.completeExceptionally(
                result.exceptionOrNull() ?: IllegalStateException("New Chat workspace is closed"),
            )
        }
        return NewChatWorkspaceSnapshot.pending(current, completion)
    }

    suspend fun awaitNewChatWrites(): NewChatWorkspaceSnapshot =
        NewChatWorkspaceSnapshot.fromPersisted(readNewChatPersist())

    suspend fun applyCommittedNewConversationState(conversationId: String) {
        transfers.complete(conversationId)
    }

    suspend fun clearCommittedNewChatWorkspace() {
        clearNewChatAndAwait(
            reclaimAttachments = false,
            expectedWorkspace = null,
        )
    }


    private fun enqueueNewChatUpdate(
        transform: (NewChatPersistEntity) -> NewChatPersistEntity,
    ) {
        synchronized(newChatStateLock) {
            pendingNewChatWrites += 1
        }
        if (newChatCommands.trySend(NewChatCommand.Update(transform, null)).isFailure) {
            synchronized(newChatStateLock) {
                pendingNewChatWrites -= 1
            }
        }
    }

    private suspend fun updateNewChatAndAwait(
        transform: (NewChatPersistEntity) -> NewChatPersistEntity,
    ) {
        val completion = CompletableDeferred<Unit>()
        synchronized(newChatStateLock) {
            pendingNewChatWrites += 1
        }
        try {
            newChatCommands.send(NewChatCommand.Update(transform, completion))
        } catch (error: Throwable) {
            synchronized(newChatStateLock) {
                pendingNewChatWrites -= 1
            }
            throw error
        }
        completion.await()
    }

    private suspend fun readNewChatPersist(): NewChatPersistEntity? {
        val completion = CompletableDeferred<NewChatPersistEntity?>()
        newChatCommands.send(NewChatCommand.Read(completion))
        return completion.await()
    }

    private suspend fun clearNewChatAndAwait(
        reclaimAttachments: Boolean,
        expectedWorkspace: NewChatPersistEntity?,
    ) {
        val completion = CompletableDeferred<Unit>()
        synchronized(newChatStateLock) {
            pendingNewChatWrites += 1
        }
        try {
            newChatCommands.send(
                NewChatCommand.Clear(
                    reclaimAttachments = reclaimAttachments,
                    expectedWorkspace = expectedWorkspace,
                    completion = completion,
                ),
            )
        } catch (error: Throwable) {
            synchronized(newChatStateLock) {
                pendingNewChatWrites -= 1
            }
            throw error
        }
        completion.await()
    }

    private suspend fun processNewChatCommand(command: NewChatCommand) {
        when (command) {
            is NewChatCommand.Read -> command.completion.complete(
                synchronized(newChatStateLock) { _newChatPersist.value },
            )

            is NewChatCommand.Update -> {
                try {
                    val next = synchronized(newChatStateLock) {
                        command.transform(_newChatPersist.value ?: NewChatPersistEntity())
                            .also { _newChatPersist.value = it }
                    }
                    conversations.upsertNewChatPersist(next)
                    command.completion?.complete(Unit)
                } catch (cancelled: CancellationException) {
                    command.completion?.completeExceptionally(cancelled)
                    throw cancelled
                } catch (error: Exception) {
                    restoreNewChatAfterFailure()
                    command.completion?.completeExceptionally(error)
                } finally {
                    synchronized(newChatStateLock) {
                        pendingNewChatWrites -= 1
                    }
                }
            }

            is NewChatCommand.Clear -> {
                try {
                    val persisted = conversations.getNewChatPersist()
                    val preserveNewerWorkspace = command.expectedWorkspace != null &&
                        persisted != null && persisted != command.expectedWorkspace
                    val next = if (preserveNewerWorkspace) {
                        persisted.copy(draftText = "", draftAttachments = null).also {
                            conversations.upsertNewChatPersist(it)
                        }
                    } else {
                        conversations.deleteNewChatPersist(command.reclaimAttachments)
                        null
                    }
                    synchronized(newChatStateLock) {
                        _newChatPersist.value = next
                    }
                    command.completion.complete(Unit)
                } catch (cancelled: CancellationException) {
                    command.completion.completeExceptionally(cancelled)
                    throw cancelled
                } catch (error: Exception) {
                    restoreNewChatAfterFailure()
                    command.completion.completeExceptionally(error)
                } finally {
                    synchronized(newChatStateLock) {
                        pendingNewChatWrites -= 1
                    }
                }
            }
        }
    }

    private suspend fun restoreNewChatAfterFailure() {
        val persisted = runCatching { conversations.getNewChatPersist() }.getOrNull()
        synchronized(newChatStateLock) {
            _newChatPersist.value = persisted
        }
    }

    private companion object {
        fun decodeConversationSettings(raw: String?): ConversationSettings? = raw?.let {
            runCatching { Json.decodeFromString<ConversationSettings>(it) }.getOrNull()
        }
    }
}
