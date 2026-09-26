package com.newoether.agora.viewmodel

import com.newoether.agora.api.DebugProvider
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal fun validChatModels(
    enabledModels: Set<String>,
    developerOptionsEnabled: Boolean,
    debugModelEnabled: Boolean,
): Set<String> {
    val ordinaryModels = enabledModels - DebugProvider.MODEL_ID
    return if (developerOptionsEnabled && debugModelEnabled) {
        ordinaryModels + DebugProvider.MODEL_ID
    } else {
        ordinaryModels
    }
}

internal fun SettingsRepository.validChatModels(
    scope: CoroutineScope,
): StateFlow<Set<String>?> = flow {
    awaitInitialLoad()
    combine(
        enabledModels,
        developerOptionsEnabled,
        debugModelEnabled,
        ::validChatModels,
    ).collect { emit(it) }
}.stateIn(scope, SharingStarted.Eagerly, null)

private fun resolveValidModel(
    referencedModel: String?,
    defaultModel: String,
    validModels: Set<String>,
): String = referencedModel
    ?.takeIf(validModels::contains)
    ?: defaultModel.takeIf(validModels::contains).orEmpty()

/**
 * Owns the open-conversation projection and its mutually superseding transition Job.
 *
 * This controller can read a conversation runtime's busy projection, but cannot submit runtime
 * commands, write RunState, or execute generation effects. Its Room mutation is limited to the
 * selected Run/message branch transaction.
 */
internal class ConversationSelectionController(
    private val scope: CoroutineScope,
    private val conversations: ConversationRepository,
    private val registry: ConversationStateRegistry,
    defaultModel: StateFlow<String>,
    validModels: StateFlow<Set<String>?>,
    private val scrollRequests: ScrollRequestCoordinator,
    private val renderStore: () -> ConversationRenderStore,
    private val clearConversationGraph: () -> Unit,
    private val workspaces: ConversationWorkspaceStore,
    private val abortRegeneration: () -> Unit,
    private val onTreeMutationCommitted: (String) -> Unit = {},
    private val fadeDelay: suspend () -> Unit = { delay(SWITCH_OVERLAY_FADE_MS) },
) {
    private val switching = SwitchingCoordinator()
    private var switchingJob: Job? = null

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()
    private val _selectedConversationGenerationSnapshot =
        MutableStateFlow(ConversationGenerationSnapshot())
    val selectedConversationGenerationSnapshot: StateFlow<ConversationGenerationSnapshot> =
        _selectedConversationGenerationSnapshot.asStateFlow()
    private var selectedRuntimeCollectorJob: Job? = null
    private var selectedRuntimeBindingGeneration = 0L

    private val _isNewChatMode = MutableStateFlow(true)
    val isNewChatMode: StateFlow<Boolean> = _isNewChatMode.asStateFlow()
    private val _activeModelOverride = MutableStateFlow<String?>(null)
    private val newChatModelId: StateFlow<String?> = workspaces.newChatModelId
    val currentActiveModel: StateFlow<String> = combine(
        _activeModelOverride,
        newChatModelId,
        _isNewChatMode,
        defaultModel,
        validModels,
    ) { active, newChatModel, isNewChat, fallback, valid ->
        if (valid == null) {
            ""
        } else {
            val referencedModel = if (isNewChat) active ?: newChatModel else active
            resolveValidModel(referencedModel, fallback, valid)
        }
    }.stateIn(scope, SharingStarted.Eagerly, "")

    init {
        scope.launch {
            combine(
                _activeModelOverride,
                newChatModelId,
                _isNewChatMode,
                defaultModel,
                validModels,
            ) { active, stored, isNewChat, fallback, valid ->
                if (isNewChat && active == null && valid != null) {
                    reconcileNewChatModel(stored, fallback, valid)
                }
            }.collect { }
        }
        scope.launch {
            combine(
                _activeModelOverride,
                _currentConversationId,
                _isNewChatMode,
                defaultModel,
                validModels,
            ) { active, conversationId, isNewChat, fallback, valid ->
                if (!isNewChat && conversationId != null && valid != null) {
                    reconcileConversationModel(conversationId, active, fallback, valid)
                }
            }.collect { }
        }
    }

    private fun reconcileNewChatModel(
        referencedModel: String?,
        defaultModel: String,
        validModels: Set<String>,
    ) {
        if (referencedModel == null || referencedModel in validModels) return
        if (!_isNewChatMode.value || _activeModelOverride.value != null) return
        if (newChatModelId.value != referencedModel) return
        workspaces.setModel(
            NEW_CHAT_WORKSPACE_ID,
            resolveValidModel(referencedModel, defaultModel, validModels)
                .takeIf(String::isNotBlank),
        )
    }

    private fun reconcileConversationModel(
        conversationId: String,
        referencedModel: String?,
        defaultModel: String,
        validModels: Set<String>,
    ) {
        if (referencedModel == null || referencedModel in validModels) return
        if (_isNewChatMode.value || _currentConversationId.value != conversationId) return
        if (_activeModelOverride.value != referencedModel) return
        val resolvedModel = resolveValidModel(referencedModel, defaultModel, validModels)
            .takeIf(String::isNotBlank)
        _activeModelOverride.value = resolvedModel
        workspaces.setModel(conversationId, resolvedModel)
    }

    private val _newChatEntryId = MutableStateFlow(1L)
    val newChatEntryId: StateFlow<Long> = _newChatEntryId.asStateFlow()

    private val _isTransitioningToNewChat = MutableStateFlow(false)
    val isTransitioningToNewChat: StateFlow<Boolean> =
        _isTransitioningToNewChat.asStateFlow()

    val isSwitching: StateFlow<Boolean> = switching.isSwitching
    val switchingScrollRequest: StateFlow<SwitchingScrollRequest?> = switching.request

    private fun publishSelectedConversation(conversationId: String?) {
        selectedRuntimeBindingGeneration += 1L
        val bindingGeneration = selectedRuntimeBindingGeneration
        selectedRuntimeCollectorJob?.cancel()
        if (conversationId == null) {
            _selectedConversationGenerationSnapshot.value = ConversationGenerationSnapshot()
            _currentConversationId.value = null
            selectedRuntimeCollectorJob = null
            return
        }
        val runtimeSnapshot = registry.getOrCreate(conversationId).generationSnapshot
        _selectedConversationGenerationSnapshot.value = runtimeSnapshot.value
        _currentConversationId.value = conversationId
        selectedRuntimeCollectorJob = scope.launch {
            runtimeSnapshot.collect { snapshot ->
                if (
                    _currentConversationId.value == conversationId &&
                    selectedRuntimeBindingGeneration == bindingGeneration
                ) {
                    _selectedConversationGenerationSnapshot.value = snapshot
                }
            }
        }
    }

    /** Publish a first Send only after its conversation/Run/message graph is durable. */
    fun publishAcceptedConversation(conversationId: String, modelId: String) {
        require(conversationId.isNotBlank())
        require(modelId.isNotBlank())
        _activeModelOverride.value = modelId
        publishSelectedConversation(conversationId)
        _isNewChatMode.value = false
    }

    fun publishAcceptedConversationIfOriginStillOpen(
        conversationId: String,
        modelId: String,
        originNewChatEntryId: Long,
    ): Boolean {
        if (
            !_isNewChatMode.value ||
            _currentConversationId.value != null ||
            _newChatEntryId.value != originNewChatEntryId ||
            switching.isSwitching.value
        ) {
            return false
        }
        publishAcceptedConversation(conversationId, modelId)
        return true
    }

    fun replaceActiveModelReference(oldModelId: String, newModelId: String?) {
        if (_activeModelOverride.value == oldModelId) {
            _activeModelOverride.value = newModelId
        }
    }

    fun setActiveModel(model: String) {
        _activeModelOverride.value = model
        val conversationId = _currentConversationId.value
        if (_isNewChatMode.value || conversationId == null) {
            workspaces.setModel(NEW_CHAT_WORKSPACE_ID, model)
        } else {
            workspaces.setModel(conversationId, model)
        }
    }

    fun createNewChat() = createNewChat(force = false)

    /** Cancels any stale selection and republishes New Chat even when it is already projected. */
    fun restoreNewChatDestination(onFailure: (() -> Unit)? = null) =
        createNewChat(force = true, onFailure = onFailure)

    private fun createNewChat(force: Boolean, onFailure: (() -> Unit)? = null) {
        // Drawer and top-bar actions are the same no-op while already on New Chat.
        if (!force && _isNewChatMode.value) return
        abortRegeneration()
        val previousJob = switchingJob
        val request = switching.beginNewChat(onFailure)
        previousJob?.cancel()
        _newChatEntryId.value += 1L
        _isNewChatMode.value = true
        _isTransitioningToNewChat.value = true
        scrollRequests.clear()
        switchingJob = scope.launch {
            var completed = false
            try {
                fadeDelay()
                if (!switching.isCurrent(request.id)) return@launch
                publishSelectedConversation(null)
                _activeModelOverride.value = null
                clearConversationGraph()
                completed = true
            } finally {
                if (switching.complete(request.id, successful = completed)) {
                    _isTransitioningToNewChat.value = false
                }
            }
        }
    }

    fun settleDeletedSelectedConversation(conversationId: String) {
        require(conversationId.isNotBlank())
        if (_isNewChatMode.value) return
        val pendingRequest = switching.request.value
        if (
            pendingRequest?.kind == SwitchingRequestKind.CONVERSATION &&
            pendingRequest.conversationId != conversationId
        ) {
            return
        }
        if (
            pendingRequest?.kind != SwitchingRequestKind.CONVERSATION &&
            _currentConversationId.value?.let { it != conversationId } == true
        ) {
            return
        }
        createNewChat()
    }

    fun selectConversation(
        conversationId: String,
        hapticOnCompletion: Boolean = true,
    ) = selectConversation(
        conversationId = conversationId,
        hapticOnCompletion = hapticOnCompletion,
        force = false,
    )

    /** Supersedes stale history loads even when the origin is still the published destination. */
    fun restoreConversationDestination(
        conversationId: String,
        onFailure: (() -> Unit)? = null,
    ) =
        selectConversation(
            conversationId = conversationId,
            hapticOnCompletion = false,
            force = true,
            onFailure = onFailure,
        )

    private fun selectConversation(
        conversationId: String,
        hapticOnCompletion: Boolean,
        force: Boolean,
        onFailure: (() -> Unit)? = null,
    ) {
        if (
            !force &&
            _currentConversationId.value == conversationId &&
            !_isNewChatMode.value
        ) {
            return
        }
        abortRegeneration()
        val previousJob = switchingJob
        val request = switching.beginConversation(conversationId, hapticOnCompletion, onFailure)
        previousJob?.cancel()
        _isTransitioningToNewChat.value = false
        scrollRequests.clear()
        switchingJob = scope.launch {
            try {
                fadeDelay()
                if (!switching.isCurrent(request.id)) return@launch
                val conversation = conversations.getConversation(conversationId)
                if (!switching.isCurrent(request.id)) return@launch
                if (conversation == null) {
                    failSwitchingScroll(request.id, "conversation disappeared")
                    return@launch
                }
                _isNewChatMode.value = false
                publishSelectedConversation(conversationId)
                _activeModelOverride.value = conversation.modelId
                switching.markConversationReady(request.id)
            } catch (error: CancellationException) {
                if (switching.isCurrent(request.id)) {
                    failSwitchingScroll(request.id, "conversation switch cancelled")
                }
                throw error
            } catch (error: Exception) {
                DebugLog.e(
                    "ConversationSelection",
                    "Failed to select conversation $conversationId",
                    error,
                )
                failSwitchingScroll(request.id, "conversation load failed")
            }
        }
    }

    fun switchBranch(parentId: String?, currentMessageId: String, direction: Int) {
        if (switching.isSwitching.value) return
        val conversationId = _currentConversationId.value ?: return
        val state = registry.getOrCreate(conversationId)
        if (state.generating.value) return
        val store = renderStore()
        val currentAnchor = store.allMessages.firstOrNull { it.id == currentMessageId } ?: return
        // Edit branches are USER siblings; Regenerate branches are MODEL siblings.
        val siblings = store.allMessages.filter {
            it.parentId == parentId &&
                it.participant == currentAnchor.participant &&
                !it.id.startsWith(Constants.TOOL_MSG_PREFIX) &&
                !it.id.startsWith(Constants.RESULT_MSG_PREFIX)
        }.sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
        if (siblings.size < 2) return
        var currentIndex = siblings.indexOfFirst { it.id == currentMessageId }
        if (currentIndex == -1) {
            val selectedId = store.selectedChildren[parentId]
            currentIndex = siblings.indexOfFirst { it.id == selectedId }
        }
        if (currentIndex == -1) return
        val newIndex = (currentIndex + direction).coerceIn(0, siblings.size - 1)
        if (newIndex == currentIndex) return
        val parentRunId = parentId?.let { id ->
            store.allMessages.firstOrNull { it.id == id }?.runId
        }

        val previousJob = switchingJob
        val request = switching.beginTreeMutation(conversationId)
        previousJob?.cancel()
        switchingJob = scope.launch {
            try {
                fadeDelay()
                if (!switching.isCurrent(request.id)) return@launch
                state.queueMutationMutex.withLock {
                    if (
                        state.generating.value ||
                        _currentConversationId.value != conversationId
                    ) {
                        switching.complete(request.id)
                        return@withLock
                    }
                    val newSelections = store.selectedChildren.toMutableMap()
                    val targetMessage = siblings[newIndex]
                    val targetRunId = targetMessage.runId ?: run {
                        switching.complete(request.id)
                        return@withLock
                    }
                    newSelections[parentId] = targetMessage.id
                    conversations.selectRunBranch(
                        conversationId = conversationId,
                        parentRunId = parentRunId,
                        runId = targetRunId,
                        messageSelections = newSelections,
                    )
                    markTreeMutationReady(request.id, targetMessage.id)
                    store.setSelectedChildren(newSelections)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DebugLog.e("ConversationSelection", "Failed to switch Run branch", error)
                switching.complete(request.id)
            }
        }
    }

    /** Begins the same UI transition for edit/delete services without exposing the coordinator. */
    suspend fun beginTreeMutation(scrollToTarget: Boolean = true): Long? {
        val conversationId = _currentConversationId.value ?: return null
        return beginTreeMutation(conversationId, scrollToTarget)
    }

    /**
     * Starts a mutation overlay only while [conversationId] is still the published destination.
     * This prevents a delayed delete coroutine from covering a newer, rapidly selected chat.
     */
    suspend fun beginTreeMutation(
        conversationId: String,
        scrollToTarget: Boolean = true,
    ): Long? {
        if (_currentConversationId.value != conversationId || _isNewChatMode.value) return null
        val request = switching.beginTreeMutation(conversationId, scrollToTarget)
        try {
            fadeDelay()
            return request.id
        } catch (error: Exception) {
            switching.complete(request.id, successful = false)
            throw error
        }
    }

    fun markTreeMutationReady(requestId: Long?, targetMessageId: String?) {
        val request = switching.request.value
        if (
            requestId == null ||
            request?.id != requestId ||
            request.kind != SwitchingRequestKind.TREE_MUTATION
        ) {
            return
        }
        val conversationId = request.conversationId ?: return
        onTreeMutationCommitted(conversationId)
        switching.markTreeMutationReady(requestId, targetMessageId)
    }

    fun failTreeMutation(requestId: Long?) {
        requestId?.let { switching.complete(it) }
    }

    fun completeSwitchingScroll(requestId: Long): Boolean = switching.complete(requestId)

    fun failSwitchingScroll(requestId: Long, reason: String) {
        if (!switching.isCurrent(requestId)) return
        DebugLog.e("ConversationSelection", "Switching scroll did not settle: $reason")
        switching.complete(requestId, successful = false)
    }

    fun failConversationLoad(conversationId: String) {
        val request = switching.request.value ?: return
        if (
            request.kind == SwitchingRequestKind.CONVERSATION &&
            request.conversationId == conversationId
        ) {
            failSwitchingScroll(request.id, "conversation projection failed")
        }
    }

    private companion object {
        const val SWITCH_OVERLAY_FADE_MS = 200L
    }
}
