package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newoether.agora.R
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.data.AutoBackupManager
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.DataExporter
import com.newoether.agora.data.DataImporter
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.forDisplay
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay


import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.util.SnackbarEvent
import com.newoether.agora.util.UpdateChecker
import com.newoether.agora.util.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel(
    application: Application,
    // [chatDao] and [settingsManager] are retained ONLY to pass to ImportExportManager,
    // which threads them into DataExporter/DataImporter (bulk data-layer utilities that
    // genuinely need raw DAO/DataStore). All other managers use repositories uniformly.
    private val database: com.newoether.agora.data.local.ChatDatabase,
    private val chatDao: com.newoether.agora.data.local.ChatDao,
    private val settingsManager: com.newoether.agora.data.SettingsManager,
    val memoryManager: MemoryManager,
    val skillManager: SkillManager,
    private val appContext: Context,
    private val sandboxFactory: SandboxManagerFactory? = null,
    // All injected via AppContainer/ChatViewModelFactory — the single construction site.
    autoBackupManager: AutoBackupManager,
    conversationRepository: ConversationRepository,
    settingsRepository: SettingsRepository,
    conversationSettingsTransfers: ConversationSettingsTransferCoordinator,
    private val startProcessServices: () -> Unit,
    // Process-scoped generation singletons, shared with background task execution.
    private val localProvider: LocalProvider,
    private val providerRegistry: ProviderRegistry,
    // App-scoped automation orchestrator (task CRUD + run-now).
    private val taskManager: com.newoether.agora.automation.TaskManager,
    private val loopManager: com.newoether.agora.automation.LoopManager,
    private val automationToolProvider: com.newoether.agora.tool.AutomationToolProvider,
    private val conversationExecutionCoordinator: com.newoether.agora.automation.ConversationExecutionCoordinator,
    private val automationExecutionGate: com.newoether.agora.automation.AutomationExecutionGate,
    private val generationRegistry: ConversationStateRegistry,
    internal val shellConfirmation: ShellConfirmationController,
    private val mcpRegistry: com.newoether.agora.mcp.McpRegistry,
    private val mcpToolProvider: com.newoether.agora.tool.McpToolProvider,
    private val taskExecutionEngine: com.newoether.agora.automation.TaskExecutionEngine,
) : AndroidViewModel(application) {

    val settings: SettingsRepository = settingsRepository

    /**
     * Conversation/message persistence behind the repository layer. CRUD, cascade-delete,
     * branch-selection and stuck-message logic live in [ConversationRepository]; managers
     * receive the repository (not raw DAO) for a uniform boundary.
     */
    private val convRepo: ConversationRepository = conversationRepository
    private val conversationWorkspaces = ConversationWorkspaceStore(
        conversations = conversationRepository,
        settings = settingsRepository,
        transfers = conversationSettingsTransfers,
        scope = viewModelScope,
    )
    internal val messagePayloadHydration =
        ConversationMessagePayloadHydration(convRepo, appContext)
    private val composerDrafts = ComposerDraftController(
        persistence = conversationWorkspaces,
        conversations = conversationRepository,
    )
    internal val conversationComposer = ConversationComposerController(
        scope = viewModelScope,
        drafts = composerDrafts,
        processor = AttachmentImportProcessor(application),
        sandboxHomeDir = {
            sandboxFactory?.takeIf { it.isAvailable() }?.let {
                File(application.filesDir, "sandbox-home")
            }
        },
    )
    val dataControl = DataControlController(
        conversations = conversationRepository,
        memory = memoryManager,
        skills = skillManager,
        settings = settingsRepository,
        backupManager = autoBackupManager,
        backupSchedule = AndroidAutoBackupSchedulePort(application),
        scope = viewModelScope,
    )
    private val conversationForkShare =
        ConversationForkShareService(
            conversationRepository,
            settingsRepository,
            File(application.filesDir, "fork-attachments"),
        )
    private val conversationForkShareController by lazy {
        ConversationForkShareController(
            currentConversationId = currentConversationId,
            service = conversationForkShare,
            scope = viewModelScope,
            onConversationForked = selectionController::selectConversation,
            onShareReady = _conversationShareText::emit,
            forkFailureText = { reason ->
                appContext.getString(R.string.conversation_fork_failed, reason)
            },
            shareFailureText = { reason ->
                appContext.getString(R.string.conversation_share_failed, reason)
            },
            onFailure = { message -> _snackbarMessage.emit(SnackbarEvent(message)) },
        )
    }
    private val conversationLifecycleController by lazy {
        ConversationLifecycleController(
            currentConversationId = currentConversationId,
            conversations = convRepo,
            scope = viewModelScope,
            stopLoop = { conversationId -> loopManager.stopLoop(conversationId) },
            tryWithConversationLock = { conversationId, block ->
                conversationExecutionCoordinator.tryWithConversationLock(conversationId) { block() }
            },
            removeRuntime = generationRegistry::remove,
            stopVisibleGeneration = generationStopAdapter::stopVisibleConversation,
            settleDeletedSelectedConversation =
                selectionController::settleDeletedSelectedConversation,
            beginSelectedDeleteTransition = { conversationId ->
                selectionController.beginTreeMutation(
                    conversationId = conversationId,
                    scrollToTarget = false,
                )
            },
            abortSelectedDeleteTransition = selectionController::failTreeMutation,
            isDeleteLocked = { conversationId ->
                conversationComposerSubmission.isFrozen(conversationId)
            },
        )
    }

    /** Embedding subsystem: model CRUD + RAG cache + single-message indexing + key resolution. */
    val ragManager = RagManager(
        conversations = convRepo,
        settings = settings,
        appContext = appContext,
        scope = viewModelScope,
    ) { _snackbarMessage.emit(it) }

    /**
     * Data export/import orchestration (native backup + Claude + GPT formats).
     * [chatDao] and [settingsManager] are passed through to [DataExporter]/[DataImporter]
     * which need raw DAO/DataStore for bulk cross-table operations.
     */
    val importExport = ImportExportManager(
        app = getApplication(),
        conversations = convRepo,
        database = database,
        chatDao = chatDao,
        settingsManager = settingsManager,
        memoryManager = memoryManager,
        skillManager = skillManager,
        conversationSettingsTransfers = conversationSettingsTransfers,
        scope = viewModelScope,
        emitSnackbar = { _snackbarMessage.emit(it) },
        onDataChanged = dataControl::refreshCounts,
        automationExecutionGate = automationExecutionGate,
        quiesceAutomation = {
            taskManager.cancelAllExecutionsForImport()
            loopManager.cancelAllExecutionsForImport()
        },
        resumeAutomationScheduling = taskManager::refreshSchedulingAfterImport,
    )

    /** Local (on-device) chat-model configuration CRUD. */
    val modelManager = ModelManager(settings, viewModelScope)
    internal val customModelConfiguration = CustomModelConfigurationController(
        providers = providerRegistry,
        conversations = convRepo,
        settings = settings,
        scope = viewModelScope,
        onModelReferenceReplaced = { oldModelId, newModelId ->
            selectionController.replaceActiveModelReference(oldModelId, newModelId)
        },
    )

    // [providerRegistry] and [localProvider] are now constructor-injected, process-scoped
    // singletons (see AppContainer) so background task execution shares the same instances.

    /**
     * Startup jobs deferred until all StateFlow/property backing fields are
     * initialized — avoids the constructor this-escape where a Dispatchers.IO
     * coroutine accesses a field whose JVM backing field is still null.
     */
    private val proxySettingsSynchronizer = ProxySettingsSynchronizer(
        settings = settings,
        scope = viewModelScope,
        apply = com.newoether.agora.api.HttpClient::setProxy,
    )
    private val localModelCatalogSynchronizer = LocalModelCatalogSynchronizer(
        settings = settings,
        scope = viewModelScope,
    )
    private val startupMaintenance by lazy {
        StartupMaintenanceCoordinator(
            settings = settings,
            scope = viewModelScope,
            currentVersion = ::getCurrentVersion,
            checkUpdate = UpdateChecker::check,
            onUpdateFound = { _updateDialogData.value = it },
            startAutoBackup = dataControl::startAutoBackup,
            startSemanticIndex = ragManager::startPostList,
        )
    }

    private fun startInitJobs() {
        viewModelScope.launch {
            conversations.filterNotNull().first()
            startProcessServices()
            proxySettingsSynchronizer.start()
            startupMaintenance.start()
            localModelCatalogSynchronizer.start()
        }
    }

    // Per-conversation generation lifecycle (IO scope, job, slot, race-free stop/persist tokens)
    // lives in [ConversationGenerationState], one per conversation via [generationRegistry].

    private val generationManager by lazy {
        GenerationManager(
            app = application,
            conversations = convRepo,
            memoryManager = memoryManager,
            skillManager = skillManager,
            context = appContext,
            sandboxFactory = sandboxFactory,
            additionalToolProviders = listOf(automationToolProvider, mcpToolProvider),
            customProviders = { settings.customProviders.value },
        ).also { gm ->
            // Gate lives in RagManager.indexMessageForRag (autoCacheEnabled + active model).
            gm.onMessagePersisted = { messageId, text -> ragManager.indexMessageForRag(messageId, text) }
            gm.onConfirmShellCommand = { server, summary -> shellConfirmation.confirm(server, summary) }
        }
    }
    private val semanticSearchService by lazy {
        SemanticSearchService(
            settings = settings,
            activeEmbeddingConfig = { ragManager.activeEmbeddingModel.value },
            resolveEmbeddingApiKey = ragManager::resolveEmbeddingApiKey,
            search = generationManager::semanticSearch,
        )
    }

    val sandboxManager: SandboxManager? by lazy {
        sandboxFactory?.create()
    }
    val isSandboxFlavor: Boolean = sandboxFactory?.isAvailable() == true
    val mcpServerSnapshots: StateFlow<Map<String, com.newoether.agora.mcp.McpServerSnapshot>>
        get() = mcpRegistry.snapshots

    fun refreshMcpServer(serverId: String) = mcpRegistry.refresh(serverId)

    fun refreshMcpServersOnPageEntry() = mcpRegistry.refreshOnPageEntry()

    override fun onCleared() {
        super.onCleared()
        // The engine and the registry are process-scoped while this ViewModel is not, so every
        // reference either of them holds must be released here or the whole graph leaks.
        foregroundAutomationBridge.close()
        generationRegistry.detachUiCallbacks(generationCallbackOwner)
        dataControl.destroy()
    }

    /** Nullable on purpose: the provider settings page recomposes one frame after a custom
     *  provider is deleted and must render gracefully instead of crashing. */
    fun getProviderInstanceOrNull(name: String): LlmProvider? = providerRegistry.getInstanceOrNull(name)

    internal val scrollRequests = ScrollRequestCoordinator()
    private val selectionController: ConversationSelectionController by lazy {
        ConversationSelectionController(
            scope = viewModelScope,
            conversations = convRepo,
            registry = generationRegistry,
            defaultModel = settings.selectedModel,
            validModels = settings.validChatModels(viewModelScope),
            scrollRequests = scrollRequests,
            renderStore = { renderStore },
            clearConversationGraph = { conversationUi.clearConversationGraph() },
            workspaces = conversationWorkspaces,
            abortRegeneration = { regenerationTransitions.abortCurrent() },
            onTreeMutationCommitted = { conversationId ->
                contextProjector.invalidate(conversationId)
            },
        )
    }

    /** Callback invoked when any send path (manual/queue/loop) accepts a message.
     *  ChatApp wires this to trigger a single haptics.confirm() for all three paths. */
    @Volatile var onSendAccepted: ((conversationId: String, messageId: String) -> Unit)? = null
    fun triggerScrollToMessage(messageId: String? = null) {
        scrollRequests.requestMessage(currentConversationId.value, messageId)
    }

    fun triggerScrollToAbsoluteBottomAfter(conversationId: String, messageId: String) {
        scrollRequests.requestAbsoluteBottomAfter(conversationId, messageId)
    }

    fun triggerScrollToAttachedBottomAfter(conversationId: String, messageId: String) {
        scrollRequests.requestAbsoluteBottomAfter(
            conversationId = conversationId,
            messageId = messageId,
            attachedOnly = true,
        )
    }

    val currentActiveModel: StateFlow<String> get() = selectionController.currentActiveModel

    fun getProviderForModel(modelId: String): String = providerRegistry.providerForModel(modelId)

    // ── Tasks (automation) ────────────────────────────────────
    /** Saved automation tasks; CRUD + run-now delegate to the app-scoped [taskManager]. */
    val tasks: StateFlow<List<com.newoether.agora.data.local.TaskEntity>> get() = taskManager.tasks
    val runningTaskIds: StateFlow<Set<String>> get() = taskManager.runningTaskIds

    fun executionSummariesForTask(taskId: String) = taskManager.executionSummariesForTask(taskId)
    suspend fun getTask(taskId: String) = taskManager.getTask(taskId)

    fun saveTask(task: com.newoether.agora.data.local.TaskEntity) {
        viewModelScope.launch { taskManager.saveTask(task) }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch { taskManager.deleteTask(taskId) }
    }

    fun runTaskNow(task: com.newoether.agora.data.local.TaskEntity, preservePersistedEnabled: Boolean = true) = taskManager.runNow(task, preservePersistedEnabled)

    // ── Auto Backup ───────────────────────────────────────────

    val conversations: StateFlow<List<ChatConversation>?> = convRepo.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val currentConversationId: StateFlow<String?> get() = selectionController.currentConversationId
    val selectedConversationGenerationSnapshot: StateFlow<ConversationGenerationSnapshot>
        get() = selectionController.selectedConversationGenerationSnapshot
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentConversation: StateFlow<ChatConversation?> = currentConversationId
        .flatMapLatest { id -> if (id == null) flowOf(null) else convRepo.observeConversation(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val unreadGenerationAcknowledger = UnreadGenerationAcknowledger(
        currentConversation = currentConversation,
        appForeground = AppForegroundTracker.foreground,
        chatPresented = AppForegroundTracker.chatPresented,
        conversations = convRepo,
        scope = viewModelScope,
        onConversationRead = { conversationId ->
            AgoraForegroundService.cancelTerminalNotification(appContext, conversationId)
        },
    )
    val currentLoop: StateFlow<com.newoether.agora.data.local.LoopEntity?> =
        loopManager.observeCurrentLoop(currentConversationId, viewModelScope)
    val runningLoopConversationIds: StateFlow<Set<String>> get() = loopManager.runningConversationIds

    fun stopCurrentLoop() {
        val id = currentConversationId.value ?: return
        viewModelScope.launch { loopManager.stopLoop(id) }
    }

    private val conversationUi = ConversationUiStateAssembler(
        conversations = convRepo,
        registry = generationRegistry,
        executionCoordinator = conversationExecutionCoordinator,
        currentConversationId = currentConversationId,
        scope = viewModelScope,
        onConversationLoadFailed = selectionController::failConversationLoad,
    )
    private val renderStore: ConversationRenderStore get() = conversationUi.renderStore
    val allMessages: StateFlow<List<ChatMessage>> = conversationUi.allMessages
    val loadedMessagesConversationId: StateFlow<String?> =
        conversationUi.loadedMessagesConversationId

    private val providerModelSync = ProviderModelSyncController(
        providers = providerRegistry,
        settings = settings,
        scope = viewModelScope,
    )
    private val providerModelSyncUi by lazy {
        ProviderModelSyncUiAdapter(
            controller = providerModelSync,
            text = appContext.providerModelSyncUiText(),
            publishMessage = { message -> _snackbarMessage.emit(SnackbarEvent(message)) },
        )
    }
    val isSyncingModels: StateFlow<Boolean> get() = providerModelSyncUi.isSyncing

    // replay=0: with replay=1 an Activity recreation (rotation) re-collected the flow and
    // re-showed the last snackbar. The 1-slot buffer keeps tryEmit lossless for slow collectors;
    // events emitted during the brief recreation gap are dropped rather than replayed stale.
    private val _snackbarMessage = MutableSharedFlow<SnackbarEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val snackbarMessage = _snackbarMessage
        .map { it.forDisplay(settings.customProviders.value) }
    fun displayText(text: String): String =
        replaceCustomProviderIdsForDisplay(text, settings.customProviders.value)

    fun emitSnackbar(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        viewModelScope.launch { _snackbarMessage.emit(SnackbarEvent(message, actionLabel, onAction)) }
    }
    private val _conversationShareText = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val conversationShareText = _conversationShareText
        .map { replaceCustomProviderIdsForDisplay(it, settings.customProviders.value) }

    private val _firstMessageCommitted = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val firstMessageCommitted = _firstMessageCommitted.asSharedFlow()

    private val _updateDialogData = MutableStateFlow<UpdateInfo?>(null)
    val updateDialogData: StateFlow<UpdateInfo?> = _updateDialogData.asStateFlow()
    fun dismissUpdateDialog() { _updateDialogData.value = null }
    fun showUpdateDialog(info: UpdateInfo) { _updateDialogData.value = info }

    /** PDF / text-file preview state shared by the existing UI consumers. */
    val mediaPreview = MediaPreviewState()

    val messages: StateFlow<List<ChatMessage>> = conversationUi.messages
    val isLoading: StateFlow<Boolean> = conversationUi.isLoading
    val generatingInConversationId: StateFlow<String?> =
        conversationUi.generatingInConversationId
    val generationSnapshot: StateFlow<ConversationGenerationSnapshot> =
        conversationUi.generationSnapshot

    /** Per-conversation generation state registry. Each conversation owns an independent
     *  ConversationGenerationState; the global loading/render mirrors
     *  below are now a MIRROR of whichever conversation is currently open (see init collectors). */
    private val generationCallbackOwner = Any()
    private val foregroundAutomationBridge by lazy {
        ForegroundAutomationBridgeController(
            currentConversationId = currentConversationId,
            send = generationController::sendMessageFromAutomationAwaitingCompletion,
            loadMessage = convRepo::getMessage,
            attach = taskExecutionEngine::attachForegroundSendBridge,
            detach = taskExecutionEngine::detachForegroundSendBridge,
        )
    }
    private val generationCallbacksAttached = Unit.also {
        generationRegistry.attachUiCallbacks(generationCallbackOwner) { state ->
            state.onActive = { conversationId ->
                // Publish synchronously with the slot claim so Stop and edit closure are immediate.
                conversationUi.markActive(conversationId)
            }
            state.onIdle = { conversationId ->
                conversationUi.markIdle(conversationId)
            }
            state.onStreamCommit = { conversationId, message ->
                conversationUi.commitTerminalStreamingMessage(conversationId, message)
            }
            state.onQueueDrainRequested = { settledState ->
                settledState.scope.launch {
                    generationController.drainQueuedAfterGeneration(settledState)
                }
            }
        }
    }

    /** Every conversation currently mutating its message tree through foreground generation or
     * headless Task/Loop execution. Drawer rows use this per-id set instead of the open
     * conversation's open UI loading mirror. */
    val generatingConversationIds: StateFlow<Set<String>> = combine(
        generationRegistry.activeConversationIds,
        conversationExecutionCoordinator.activeAutomationConversationIds,
    ) { foreground, automation ->
        foreground + automation
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val generationStopAdapter by lazy {
        GenerationStopAdapter(
            currentConversationId = currentConversationId,
            registry = generationRegistry,
            renderStore = renderStore,
            finalizer = GenerationFinalizer(convRepo, ragManager::indexMessageForRag),
            failureText = {
                getApplication<Application>().getString(R.string.failed_to_generate)
            },
            onFailure = { message -> emitSnackbar(message) },
        )
    }

    val isSwitching: StateFlow<Boolean> get() = selectionController.isSwitching

    internal val regenerationTransitions = BranchReplacementTransitionCoordinator()
    val isNewChatMode: StateFlow<Boolean> get() = selectionController.isNewChatMode
    val newChatEntryId: StateFlow<Long> get() = selectionController.newChatEntryId
    val isTransitioningToNewChat: StateFlow<Boolean>
        get() = selectionController.isTransitioningToNewChat

    val pendingSystemPromptId: StateFlow<String?> =
        conversationWorkspaces.newChatSystemPromptId

    fun setPendingSystemPrompt(promptId: String?) {
        conversationWorkspaces.setSystemPrompt(NEW_CHAT_WORKSPACE_ID, promptId)
    }

    val pendingConversationSettings: StateFlow<ConversationSettings?> =
        conversationWorkspaces.newChatConversationSettings
    internal val compactUi = ConversationCompactUiCoordinator(
        currentConversationId = currentConversationId,
        registry = generationRegistry,
        scope = viewModelScope,
        configuredModel = { settings.contextCompactModel.value },
        currentModel = { currentActiveModel.value },
        configuredPrompt = { settings.contextCompactPrompt.value },
        configuredRetainCount = { settings.contextCompactRetainCount.value },
        configuredPreserveSystemPrompt = { settings.contextCompactPreserveSystemPrompt.value },
        compactManual = { request -> generationController.compactManual(request) },
        failureMessage = { result -> compactFailureMessage(appContext, result) },
        onFailure = { message -> emitSnackbar(message) },
    )
    fun setConversationSettings(convId: String?, value: ConversationSettings?) =
        conversationWorkspaces.setConversationSettings(convId ?: NEW_CHAT_WORKSPACE_ID, value)
    private val payloadBuilder by lazy(::MessagePayloadBuilder)

    private val requestBuilder = GenerationRequestBuilder(
        settings = settings,
        convRepo = convRepo,
        memoryManager = memoryManager,
        skillManager = skillManager,
        providerRegistry = providerRegistry,
        ragManager = ragManager,
        appContext = appContext,
        pendingConversationSettings = pendingConversationSettings,
        onSnackbar = { msg -> emitSnackbar(msg) },
    )
    private val contextProjector by lazy {
        ConversationContextProjector(
            conversations = convRepo,
            requestBuilder = requestBuilder,
            generationManager = { generationManager },
            generationErrorFormatter = { raw ->
                normalizePersistedGenerationErrorText(appContext, raw)
            },
            newChatSystemPromptId = { pendingSystemPromptId.value },
        )
    }

    internal val conversationContextProjection: StateFlow<ConversationContextProjection>
        get() = contextProjector.projection

    internal fun requestConversationContext(
        conversationId: String?,
        selectedBranchesJson: String?,
        selectedModelId: String,
        tokenBudget: Int,
    ) {
        viewModelScope.launch {
            contextProjector.project(
                conversationId,
                selectedBranchesJson,
                selectedModelId,
                tokenBudget,
            )
        }
    }

    private val generationController by lazy {
        MessageGenerationController(
            viewModelScope = viewModelScope,
            application = getApplication(),
            appContext = appContext,
            convRepo = convRepo,
            settings = settings,
            registry = generationRegistry,
            generationManagerProvider = { generationManager },
            requestBuilder = requestBuilder,
            payloadBuilder = payloadBuilder,
            providerRegistry = providerRegistry,
            localProvider = localProvider,
            executionCoordinator = conversationExecutionCoordinator,
            renderStore = renderStore,
            currentConversationId = currentConversationId,
            isNewChatMode = isNewChatMode,
            newChatEntryId = newChatEntryId,
            captureNewChatWorkspace = conversationWorkspaces::captureNewChatSnapshot,
            applyCommittedNewConversationState = conversationWorkspaces::applyCommittedNewConversationState,
            currentActiveModel = currentActiveModel,
            messages = messages,
            onScrollToMessage = { id -> triggerScrollToMessage(id) },
            onScrollToAbsoluteBottomAfter = ::triggerScrollToAbsoluteBottomAfter,
            onScrollToAttachedBottomAfter = ::triggerScrollToAttachedBottomAfter,
            onSendAcceptedEvent = { convId, msgId ->
                // Feedback belongs to the conversation on screen. A send from the new-chat page
                // qualifies because that page becomes this very conversation, but its id is only
                // published after acceptance, so it is matched via isNewChatMode rather than by id.
                // Background automation on another conversation stays silent: from the user's point
                // of view nothing happened on screen.
                val currentId = currentConversationId.value
                val targetsOpenConversation = currentId == convId ||
                    (currentId == null && isNewChatMode.value)
                if (targetsOpenConversation) onSendAccepted?.invoke(convId, msgId)
            },
            onSnackbar = { msg -> emitSnackbar(msg) },
            onSnackbarSuspend = { msg -> _snackbarMessage.emit(SnackbarEvent(msg)) },
            onConversationCreatedBySend = { conversationId ->
                scrollRequests.suppressNextOpenScroll = true
                _firstMessageCommitted.tryEmit(conversationId)
            },
            onConversationAcceptedBySend = { conversationId, modelId, entryId ->
                withContext(Dispatchers.Main.immediate) {
                    selectionController.publishAcceptedConversationIfOriginStillOpen(
                        conversationId,
                        modelId,
                        entryId,
                    )
                }
            },
            onUserMessagePersisted = ragManager::indexMessageForRag,
            onTreeMutationStart = { conversationId, scrollToTarget ->
                selectionController.beginTreeMutation(conversationId, scrollToTarget)
            },
            onTreeMutationSettling = selectionController::markTreeMutationReady,
            onTreeMutationFailed = selectionController::failTreeMutation,
            regenerationTransitions = regenerationTransitions,
            pauseConversationTasks = { conversationId -> loopManager.stopLoop(conversationId) },
        )
    }
    internal val conversationComposerSubmission by lazy {
        ConversationComposerSubmissionController(
            scope = viewModelScope,
            composers = conversationComposer,
            drafts = composerDrafts,
            captureTarget = generationController::captureForegroundSendTarget,
            prepare = generationController::prepareForegroundSend,
            send = { admission, text, attachments, onAccepted ->
                generationController.sendMessage(admission, text, attachments, onAccepted)
            },
            onAcceptedClearFailed = { _, retry ->
                emitSnackbar(
                    message = appContext.getString(R.string.composer_clear_failed),
                    actionLabel = appContext.getString(R.string.retry),
                    onAction = retry,
                )
            },
        )
    }

    fun updateConversationSetting(
        convId: String?,
        update: (ConversationSettings) -> ConversationSettings,
    ) = conversationWorkspaces.updateConversationSettings(convId ?: NEW_CHAT_WORKSPACE_ID, update)

    val switchingScrollRequest: StateFlow<SwitchingScrollRequest?> =
        selectionController.switchingScrollRequest

    fun completeSwitchingScroll(requestId: Long): Boolean =
        selectionController.completeSwitchingScroll(requestId)

    fun failSwitchingScroll(requestId: Long, reason: String) =
        selectionController.failSwitchingScroll(requestId, reason)

    init {
        startInitJobs()
        unreadGenerationAcknowledger.start()
        conversationUi.start()

        // Loop cycles for the open conversation use the regular Send path; the bridge waits for
        // that exact durable turn and returns a typed result to the automation lease owner.
        foregroundAutomationBridge.start()
    }

    fun getCurrentVersion(): String {
        return try { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
    }
    suspend fun checkForUpdates(): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            UpdateChecker.check(getCurrentVersion())
        }
    }
    suspend fun semanticSearch(query: String, limit: Int = 20) =
        semanticSearchService.search(query, limit)
    suspend fun searchMessages(query: String, limit: Int = 20) = convRepo.searchMessages(query, limit)
    internal val sshHostKeyVerifier = SshHostKeyVerifier()
    internal val remoteEmbeddingConnectionTester by lazy {
        RemoteEmbeddingConnectionTester(
            resolveApiKey = ragManager::resolveEmbeddingApiKey,
            resolveBaseUrl = ragManager::resolveEmbeddingBaseUrl,
        )
    }

    fun createNewChat() = selectionController.createNewChat()

    internal fun restoreNewChatDestination(onFailure: (() -> Unit)? = null) =
        selectionController.restoreNewChatDestination(onFailure)

    fun selectConversation(
        id: String,
        hapticOnCompletion: Boolean = true,
    ) = selectionController.selectConversation(id, hapticOnCompletion)

    internal fun restoreConversationDestination(id: String, onFailure: (() -> Unit)? = null) =
        selectionController.restoreConversationDestination(id, onFailure)

    fun forkConversationFrom(messageId: String? = null) =
        conversationForkShareController.fork(messageId)

    fun shareGeneration(assistantMessageId: String) =
        conversationForkShareController.shareGeneration(assistantMessageId)

    fun shareMessages(messageIds: Set<String>) =
        conversationForkShareController.shareMessages(messageIds)

    fun renameConversation(id: String, newTitle: String) {
        conversationLifecycleController.rename(id, newTitle)
    }

    fun generateTitle(conversationId: String) = generationController.generateTitle(conversationId)

    fun setConversationSystemPrompt(id: String, promptId: String?) =
        conversationWorkspaces.setSystemPrompt(id, promptId)

    fun setActiveModel(model: String) = selectionController.setActiveModel(model)

    fun deleteConversation(
        id: String,
        expectedMessageIds: Set<String>? = null,
        onResult: (Boolean) -> Unit = {},
    ): Boolean = conversationLifecycleController.delete(id, expectedMessageIds, onResult)

    fun isConversationDeleteLocked(id: String): Boolean =
        conversationComposerSubmission.isFrozen(id)

    /**
     * Deletes a message and all its descendants (BFS cascade).
     * Hidden tool_/result_ children are included in the cascade.
     * Attachments, embeddings, and branch selections are cleaned up.
     * Returns the count of deleted messages (for the confirmation dialog).
     */
    fun deleteMessage(
        messageId: String,
        onResult: ((Boolean) -> Unit)? = null,
    ): Int {
        if (isSwitching.value) {
            onResult?.invoke(false)
            return 0
        }
        return generationController.deleteMessage(messageId, onResult)
    }

    private val currentRuntimeFacade = CurrentConversationRuntimeFacade(
        currentConversationId = currentConversationId,
        registry = generationRegistry,
        scope = viewModelScope,
    )
    internal val queuedSends: StateFlow<List<QueuedSend>> get() = currentRuntimeFacade.queuedSends
    val isStopping: StateFlow<Boolean> get() = currentRuntimeFacade.isStopping

    fun removeQueuedSend(id: String) = currentRuntimeFacade.removeQueuedSend(id)
    fun sendQueuedNow() = currentRuntimeFacade.requestQueueDrain()

    fun stopGeneration() = generationStopAdapter.stopVisibleConversation()

    fun regenerate(messageId: String): Boolean = generationController.regenerate(messageId)

    fun switchBranch(parentId: String?, currentMessageId: String, direction: Int) =
        selectionController.switchBranch(parentId, currentMessageId, direction)

    suspend fun editMessage(messageId: String, newText: String): Boolean = generationController.editMessage(messageId, newText)

    suspend fun fetchModelsForProvider(name: String): List<String> = providerModelSyncUi.fetchModelsForProvider(name)

    fun computeProviderFingerprint(): String = providerModelSyncUi.computeFingerprint()

    fun fetchAvailableModels() = providerModelSyncUi.fetchAvailableModels()

}
