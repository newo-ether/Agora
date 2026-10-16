package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.automation.LoopManager
import com.newoether.agora.automation.TaskExecutionEngine
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.tool.AskUserToolProvider
import com.newoether.agora.tool.AutomationToolProvider
import com.newoether.agora.tool.McpToolProvider
import com.newoether.agora.util.SnackbarEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Process-scoped chat runtime shared by every client of this process (the phone UI and, later,
 * the WebUI). It lives for the whole process on the app scope, so chat work it owns keeps running
 * when no Activity or ViewModel exists.
 *
 * It owns the foreground generation core, RAG indexing, the message commands (send,
 * regenerate, edit, delete, compact, stop) and the conversation commands (rename, delete, fork,
 * share). Commands name their conversation and origin client
 * explicitly. It binds the generation registry's callbacks and the Loop foreground bridge, so a
 * queued send continues and a Loop cycle is delegated without any Activity. Per-client state (open conversation, scroll, composer drafts, new-chat workspace)
 * stays with each client.
 */
class ChatRuntime(
    application: Application,
    appContext: Context,
    conversations: ConversationRepository,
    settings: SettingsRepository,
    memoryManager: MemoryManager,
    skillManager: SkillManager,
    sandboxFactory: SandboxManagerFactory?,
    automationToolProvider: AutomationToolProvider,
    mcpToolProvider: McpToolProvider,
    askUser: AskUserController,
    manualIndexer: com.newoether.agora.data.ManualIndexer,
    val userDocumentManager: com.newoether.agora.data.UserDocumentManager,
    shellConfirmation: ShellConfirmationController,
    registry: ConversationStateRegistry,
    providerRegistry: ProviderRegistry,
    localProvider: LlmProvider,
    executionCoordinator: ConversationExecutionCoordinator,
    loopManager: LoopManager,
    taskExecutionEngine: TaskExecutionEngine,
    scope: CoroutineScope,
) {
    // replay=0: events raised while no client is collecting are dropped rather than replayed
    // stale to the next client. The 1-slot buffer keeps tryEmit lossless for slow collectors.
    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Clients currently attached to this runtime (see [ChatClient]). */
    internal val clients = ChatClients()

    /** Runtime-wide messages every connected client may show (for example RAG cache prompts). */
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    /** Embedding subsystem: model CRUD + RAG cache + single-message indexing + key resolution. */
    val ragManager = RagManager(
        conversations = conversations,
        settings = settings,
        appContext = appContext,
        scope = scope,
    ) { _snackbarEvents.emit(it) }

    internal val generationManager: GenerationManager by lazy {
        GenerationManager(
            app = application,
            conversations = conversations,
            memoryManager = memoryManager,
            skillManager = skillManager,
            context = appContext,
            sandboxFactory = sandboxFactory,
            additionalToolProviders = listOf(
                automationToolProvider,
                mcpToolProvider,
                AskUserToolProvider(askUser),
                com.newoether.agora.tool.IntrospectToolProvider(manualIndexer),
                com.newoether.agora.tool.SearchDocumentsToolProvider(userDocumentManager),
            ),
            customProviders = { settings.customProviders.value },
        ).also { gm ->
            // Gate lives in RagManager.indexMessageForRag (autoCacheEnabled + active model).
            gm.onMessagePersisted = { messageId, text -> ragManager.indexMessageForRag(messageId, text) }
            gm.onConfirmShellCommand = shellConfirmation::confirm
        }
    }

    /** Stateless request assembly shared by every command; context previews read it too. */
    internal val requestBuilder = GenerationRequestBuilder(
        settings = settings,
        convRepo = conversations,
        memoryManager = memoryManager,
        skillManager = skillManager,
        providerRegistry = providerRegistry,
        ragManager = ragManager,
        appContext = appContext,
    )

    internal val messageGeneration: MessageGenerationController by lazy {
        MessageGenerationController(
            scope = scope,
            application = application,
            appContext = appContext,
            convRepo = conversations,
            settings = settings,
            registry = registry,
            generationManagerProvider = { generationManager },
            requestBuilder = requestBuilder,
            payloadBuilder = MessagePayloadBuilder(),
            providerRegistry = providerRegistry,
            localProvider = localProvider,
            executionCoordinator = executionCoordinator,
            clients = clients,
            onSnackbar = { message -> _snackbarEvents.tryEmit(SnackbarEvent(message)) },
            onUserMessagePersisted = ragManager::indexMessageForRag,
            pauseConversationTasks = { conversationId -> loopManager.stopLoop(conversationId) },
        )
    }

    internal val generationStop: GenerationStopAdapter by lazy {
        GenerationStopAdapter(
            registry = registry,
            clients = clients,
            finalizer = GenerationFinalizer(conversations, ragManager::indexMessageForRag),
            failureText = { appContext.getString(R.string.failed_to_generate) },
        )
    }

    internal val conversationLifecycle = ConversationLifecycleController(
        conversations = conversations,
        scope = scope,
        clients = clients,
        stopLoop = { conversationId -> loopManager.stopLoop(conversationId) },
        tryWithConversationLock = { conversationId, block ->
            executionCoordinator.tryWithConversationLock(conversationId) { block() }
        },
        removeRuntime = registry::remove,
        stopGeneration = { conversationId, origin -> generationStop.stop(conversationId, origin) },
        deletedElsewhereText = { appContext.getString(R.string.conversation_deleted_elsewhere) },
    )

    internal val conversationForkShare = ConversationForkShareController(
        service = ConversationForkShareService(
            conversations,
            settings,
            File(application.filesDir, "fork-attachments"),
        ),
        scope = scope,
        forkFailureText = { reason -> appContext.getString(R.string.conversation_fork_failed, reason) },
        shareFailureText = { reason -> appContext.getString(R.string.conversation_share_failed, reason) },
    )

    // Loop cycles for a conversation some client shows use the regular Send path; the bridge
    // waits for that exact durable turn and returns a typed result to the automation lease owner.
    private val foregroundAutomationBridge = ForegroundAutomationBridgeController(
        isConversationOpen = clients::isConversationOpen,
        send = { conversationId, userText, modelId, requestKind ->
            messageGeneration.sendMessageFromAutomationAwaitingCompletion(
                conversationId,
                userText,
                modelId,
                requestKind,
            )
        },
        loadMessage = conversations::getMessage,
        attach = taskExecutionEngine::attachForegroundSendBridge,
        detach = taskExecutionEngine::detachForegroundSendBridge,
    )

    init {
        // The runtime is the registry's single, process-lifetime callback owner.
        registry.attachUiCallbacks(this) { state ->
            state.onActive = { conversationId ->
                // Published synchronously with the slot claim so Stop and edit closure are immediate.
                clients.generationActivityChanged(conversationId, active = true)
            }
            state.onIdle = { conversationId ->
                clients.generationActivityChanged(conversationId, active = false)
            }
            state.onStreamCommit = clients::commitTerminalStreamingMessage
            state.onQueueDrainRequested = { settledState ->
                settledState.scope.launch {
                    messageGeneration.drainQueuedAfterGeneration(settledState)
                }
            }
        }
        foregroundAutomationBridge.start()
    }
}
