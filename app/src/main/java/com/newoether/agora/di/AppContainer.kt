package com.newoether.agora.di

import android.app.Application
import android.content.Context
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.semanticModelSnapshot
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.data.repository.TaskRepository
import com.newoether.agora.data.AutoBackupManager
import com.newoether.agora.api.LocalModelRuntime
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.automation.AutomationExecutionGate
import com.newoether.agora.automation.AutomationScheduler
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.automation.HeartbeatScheduler
import com.newoether.agora.automation.LoopManager
import com.newoether.agora.automation.TaskExecutionEngine
import com.newoether.agora.automation.TaskManager
import com.newoether.agora.daemon.DaemonController
import com.newoether.agora.data.HeartbeatManager
import com.newoether.agora.data.SmsDraftStore
import com.newoether.agora.data.SmsPoller
import com.newoether.agora.data.SmsReader
import com.newoether.agora.data.SmsStore
import com.newoether.agora.data.NotificationListenerController
import com.newoether.agora.data.NotificationReader
import com.newoether.agora.data.NotificationStore
import com.newoether.agora.data.SmsMessageData
import com.newoether.agora.mcp.McpRegistry
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.service.HeartbeatNotifier
import com.newoether.agora.service.LoopWorker
import com.newoether.agora.service.MaintenanceDebtWorker
import com.newoether.agora.service.TaskWorker
import com.newoether.agora.sms.SmsSender
import com.newoether.agora.tool.AutomationToolProvider
import com.newoether.agora.tool.HeartbeatToolProvider
import com.newoether.agora.tool.McpToolProvider
import com.newoether.agora.tool.NotificationToolProvider
import com.newoether.agora.tool.SmsToolProvider
import com.newoether.agora.viewmodel.ChatViewModelFactory
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.ProviderRegistry
import com.newoether.agora.viewmodel.ShellConfirmationController
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Centralized dependency container (manual DI).
 *
 * Replaces the ad-hoc dependency creation previously spread across MainActivity.
 * The validated database is injected by AgoraApplication's startup gate; all shared
 * dependencies are then created once and reused.
 *
 * This is a stepping stone toward a full DI framework (Hilt/Koin);
 * for a single-module project it provides sufficient decoupling and
 * testability without annotation processing overhead.
 */
class AppContainer(
    private val appContext: Context,
    val database: ChatDatabase,
) {
    private val application = appContext.applicationContext as Application

    init {
        LocalModelRuntime.initialize(application.applicationInfo.nativeLibraryDir)
    }

    /** App-lifetime scope that backs the shared settings StateFlows.
     *  The handler is the last line of defense: children launched directly on this scope
     *  (settings sync, scheduler, task runners) have no other parent to report to, and an
     *  uncaught exception here would otherwise kill the whole process. */
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                com.newoether.agora.util.DebugLog.e("AppContainer", "Uncaught in appScope", e)
            }
    )

    // ── Data Layer ────────────────────────────────────────────

    val settingsManager: SettingsManager by lazy { SettingsManager(appContext) }
    val memoryManager: MemoryManager by lazy { MemoryManager(appContext) }
    val skillManager: SkillManager by lazy { SkillManager(appContext) }
    val chatDao: ChatDao by lazy { database.chatDao() }

    // ── Repositories ──────────────────────────────────────────

    val conversationRepository: ConversationRepository by lazy {
        ConversationRepository(
            chatDao = chatDao,
            database = database,
            semanticModelSnapshotProvider = {
                settingsRepository.awaitInitialLoad()
                semanticModelSnapshot(
                    activeModelId = settingsRepository.activeEmbeddingModelId.value,
                    configuredModelIds = settingsRepository.embeddingModels.value.map { it.id },
                )
            },
        )
    }

    @Volatile
    private var processServicesStarted = false

    /** Starts necessary process work after the narrow conversation list has published. */
    @Synchronized
    fun startProcessServices() {
        if (processServicesStarted) return
        providerRegistry.ensureStarted()
        taskManager.start()
        automationScheduler.start()
        processServicesStarted = true
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (database.maintenanceDebtDao().hasDebt()) MaintenanceDebtWorker.schedule()
            } catch (error: Exception) {
                com.newoether.agora.util.DebugLog.e(
                    "AppContainer",
                    "Failed to schedule maintenance debt",
                    error,
                )
            }
        }
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            conversationSettingsTransfers.replayPending()
        }
        // Keep the daemon in sync with its setting so toggling it in settings starts/stops
        // the heartbeat scheduler immediately instead of only on the next process start.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            settingsRepository.daemonEnabled.collect { enabled ->
                if (enabled) daemonController.start() else daemonController.stop()
            }
        }
    }
    val taskRepository: TaskRepository by lazy {
        TaskRepository(chatDao)
    }
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(settingsManager, appScope).also {
            LocalModelRuntime.bindIdleRetention(it.localModelIdleRetentionMinutes, appScope)
        }
    }
    val conversationSettingsTransfers: ConversationSettingsTransferCoordinator by lazy {
        ConversationSettingsTransferCoordinator(conversationRepository, settingsRepository)
    }

    /** One process-wide confirmation queue shared by Chat, Task, and Loop generation. */
    val shellConfirmationController: ShellConfirmationController by lazy {
        ShellConfirmationController(settingsRepository)
    }

    // ── Generation singletons (process-scoped) ────────────────
    // Shared by both the foreground ChatViewModel and background task execution.
    // [localProvider] must be unique per process; LocalModelRuntime owns the one embedded model
    // lifecycle. [providerRegistry] holds the live provider map the
    // generation pipeline reads and runs the long-lived credential/model sync jobs.

    val localProvider: LocalProvider by lazy { LocalProvider(appContext, settingsRepository) }

    val providerRegistry: ProviderRegistry by lazy {
        ProviderRegistry(settingsRepository, conversationRepository, localProvider, appScope)
    }

    /** Serializes every foreground/background generation touching the same conversation. */
    val conversationExecutionCoordinator: ConversationExecutionCoordinator by lazy {
        ConversationExecutionCoordinator()
    }

    /** Foreground generation slots survive Activity/ViewModel recreation within this process. */
    val conversationStateRegistry: ConversationStateRegistry by lazy {
        ConversationStateRegistry()
    }

    val mcpRegistry: McpRegistry by lazy {
        McpRegistry(appContext, settingsRepository, appScope)
    }

    val mcpToolProvider: McpToolProvider by lazy {
        McpToolProvider(mcpRegistry)
    }

    /** Lets native import quiesce Task/Loop generation without serializing ordinary executions. */
    val automationExecutionGate: AutomationExecutionGate by lazy { AutomationExecutionGate() }

    // ── Sandbox (flavor-specific) ─────────────────────────────

    val sandboxManagerFactory: SandboxManagerFactory? by lazy {
        try {
            // fdroid flavor provides FdroidSandboxManagerFactory
            Class.forName("com.newoether.agora.sandbox.FdroidSandboxManagerFactory")
                .getDeclaredConstructor(
                    android.content.Context::class.java,
                    com.newoether.agora.data.repository.SettingsRepository::class.java,
                )
                .newInstance(appContext, settingsRepository) as SandboxManagerFactory
        } catch (_: ClassNotFoundException) {
            // play flavor provides PlaySandboxManagerFactory
            try {
                Class.forName("com.newoether.agora.sandbox.PlaySandboxManagerFactory")
                    .getDeclaredConstructor()
                    .newInstance() as SandboxManagerFactory
            } catch (_: ClassNotFoundException) {
                null
            } catch (e: Exception) {
                // Class exists but failed to construct — this is a real error, not a flavor miss.
                com.newoether.agora.util.DebugLog.e("AppContainer", "PlaySandboxManagerFactory init failed", e)
                null
            }
        } catch (e: Exception) {
            // FdroidSandboxManagerFactory exists but failed to construct.
            com.newoether.agora.util.DebugLog.e("AppContainer", "FdroidSandboxManagerFactory init failed", e)
            null
        }
    }

    // ── Headless task execution (process-scoped) ──────────────
    // Drives a full generation with no ViewModel/UI, reusing the shared generation
    // singletons above. Background Task/Loop runners call its runOnce(...).

    val taskExecutionEngine: TaskExecutionEngine by lazy {
        TaskExecutionEngine(
            application = application,
            appContext = appContext,
            convRepo = conversationRepository,
            settings = settingsRepository,
            memoryManager = memoryManager,
            skillManager = skillManager,
            providerRegistry = providerRegistry,
            localProvider = localProvider,
            sandboxFactory = sandboxManagerFactory,
            appScope = appScope,
            executionCoordinator = conversationExecutionCoordinator,
            shellConfirmation = shellConfirmationController,
            automationExecutionGate = automationExecutionGate,
            mcpToolProvider = mcpToolProvider,
            generationRegistry = conversationStateRegistry,
            pauseConversationLoop = { conversationId -> loopManager.stopLoop(conversationId) },
            // HeartbeatScheduler runs the heartbeat prompt through this same engine; without
            // these, promote_learning/SMS/notification tools would be invisible during a
            // heartbeat run even though they're wired into the interactive ChatViewModel.
            extraToolProviders = listOf(heartbeatToolProvider, smsToolProvider, notificationToolProvider),
        )
    }

    val taskManager: TaskManager by lazy {
        TaskManager(
            taskRepository = taskRepository,
            conversationRepository = conversationRepository,
            engine = taskExecutionEngine,
            scope = appScope,
            cancelScheduledExecution = { taskId ->
                TaskWorker.cancel(appContext, taskId)
                automationScheduler.cancelTask(taskId)
            },
            cancelConversationLoop = { conversationId ->
                loopManager.stopLoop(conversationId)
            },
            refreshScheduling = { automationScheduler.refresh() },
            conversationExecutionCoordinator = conversationExecutionCoordinator,
            automationExecutionGate = automationExecutionGate,
            titleExecutionConversation = taskExecutionEngine::updateTaskExecutionTitle,
        )
    }

    val loopManager: LoopManager by lazy {
        LoopManager(
            taskRepository = taskRepository,
            conversationRepository = conversationRepository,
            engine = taskExecutionEngine,
            cancelWork = { conversationId ->
                com.newoether.agora.service.LoopWorker.cancel(appContext, conversationId)
            },
            cancelAlarm = { conversationId -> automationScheduler.cancelLoop(conversationId) },
            executionCoordinator = conversationExecutionCoordinator,
            executionGate = automationExecutionGate,
        )
    }

    /** Foreground-only provider: headless automation cannot recursively create automation. */
    val automationToolProvider: AutomationToolProvider by lazy {
        AutomationToolProvider(taskManager, loopManager) {
            settingsManager.automationToolsEnabled.first()
        }
    }

    val automationScheduler: AutomationScheduler by lazy {
        AutomationScheduler(appContext, taskRepository, settingsRepository, appScope)
    }

    // ── Auto Backup ───────────────────────────────────────────

    val autoBackupManager: AutoBackupManager by lazy {
        AutoBackupManager(appContext, database, settingsManager, chatDao, memoryManager, skillManager)
    }

    // ── Heartbeat/SMS/Daemon ────────────────────────────────

    val heartbeatManager: HeartbeatManager by lazy {
        HeartbeatManager(settingsRepository, memoryManager, taskManager, conversationRepository, chatDao)
    }

    val smsStore: SmsStore by lazy {
        SmsStore(chatDao, database)
    }

    val smsDraftStore: SmsDraftStore by lazy {
        SmsDraftStore(chatDao, database)
    }

    val smsPoller: SmsPoller by lazy {
        SmsPoller(smsStore, smsReader)
    }

    /** Flavor-specific SmsReader implementation (fdroid has SmsReaderImpl). */
    val smsReader: SmsReader by lazy {
        try {
            Class.forName("com.newoether.agora.sms.SmsReaderImpl")
                .getDeclaredConstructor(android.content.Context::class.java)
                .newInstance(appContext) as SmsReader
        } catch (_: ClassNotFoundException) {
            // Play flavor or fdroid without implementation - return no-op
            noopSmsReader()
        } catch (e: Exception) {
            com.newoether.agora.util.DebugLog.e("AppContainer", "SmsReaderImpl init failed", e)
            noopSmsReader()
        }
    }

    /** Flavor-specific SmsSender implementation (fdroid has SmsSenderImpl). */
    val smsSender: SmsSender by lazy {
        try {
            Class.forName("com.newoether.agora.sms.SmsSenderImpl")
                .getDeclaredConstructor(android.content.Context::class.java)
                .newInstance(appContext) as SmsSender
        } catch (_: ClassNotFoundException) {
            // Play flavor or fdroid without implementation - return no-op
            noopSmsSender()
        } catch (e: Exception) {
            com.newoether.agora.util.DebugLog.e("AppContainer", "SmsSenderImpl init failed", e)
            noopSmsSender()
        }
    }

    private fun noopSmsReader(): SmsReader = object : SmsReader {
        override fun isSupported() = false
        override fun hasPermission() = false
        override suspend fun readNewMessages(lastSeenId: Long, limit: Int) = emptyList<SmsMessageData>()
        override suspend fun readById(id: Long) = null
        override suspend fun search(query: String, limit: Int) = emptyList<SmsMessageData>()
        override suspend fun currentMaxInboxId() = 0L
    }

    private fun noopSmsSender(): SmsSender = object : SmsSender {
        override fun isSupported() = false
        override fun hasPermission() = false
        override suspend fun sendSms(address: String, body: String) =
            com.newoether.agora.sms.SmsSendResult.Failure("SMS sending not supported on this build")
    }

    val daemonController: DaemonController by lazy {
        DaemonController(appContext, settingsRepository, heartbeatScheduler, AppForegroundTracker)
    }

    val heartbeatScheduler: HeartbeatScheduler by lazy {
        HeartbeatScheduler(
            appContext = appContext,
            heartbeatManager = heartbeatManager,
            settingsRepository = settingsRepository,
            smsStore = smsStore,
            smsPoller = smsPoller,
            notificationStore = notificationStore,
            heartbeatNotifier = heartbeatNotifier,
            taskExecutionEngine = taskExecutionEngine,
            appForegroundTracker = AppForegroundTracker,
            loopManager = loopManager,
        )
    }

    val heartbeatNotifier: HeartbeatNotifier by lazy {
        HeartbeatNotifier(appContext, settingsRepository)
    }

    val heartbeatToolProvider: HeartbeatToolProvider by lazy {
        HeartbeatToolProvider(skillManager, settingsManager)
    }

    val smsToolProvider: SmsToolProvider by lazy {
        SmsToolProvider(smsStore, smsReader, smsSender, smsDraftStore, settingsRepository)
    }

    // ── Notifications ────────────────────────────────────────

    val notificationStore: NotificationStore by lazy {
        NotificationStore(settingsRepository, chatDao)
    }

    /** Flavor-specific NotificationReader implementation (fdroid has NotificationReaderImpl). */
    val notificationReader: NotificationReader by lazy {
        try {
            Class.forName("com.newoether.agora.notifications.NotificationReaderImpl")
                .getDeclaredConstructor(
                    android.content.Context::class.java,
                    com.newoether.agora.data.NotificationStore::class.java,
                )
                .newInstance(appContext, notificationStore) as NotificationReader
        } catch (_: ClassNotFoundException) {
            // Play flavor - return no-op implementation
            object : NotificationReader {
                override fun isSupported() = false
                override suspend fun getNotificationById(key: String) = null
                override suspend fun searchNotifications(query: String, packageName: String?, limit: Int) = emptyList<com.newoether.agora.data.NotificationRecord>()
                override suspend fun getCurrentRecords(limit: Int) = emptyList<com.newoether.agora.data.NotificationRecord>()
            }
        } catch (e: Exception) {
            com.newoether.agora.util.DebugLog.e("AppContainer", "NotificationReaderImpl init failed", e)
            object : NotificationReader {
                override fun isSupported() = false
                override suspend fun getNotificationById(key: String) = null
                override suspend fun searchNotifications(query: String, packageName: String?, limit: Int) = emptyList<com.newoether.agora.data.NotificationRecord>()
                override suspend fun getCurrentRecords(limit: Int) = emptyList<com.newoether.agora.data.NotificationRecord>()
            }
        }
    }

    /** Flavor-specific NotificationListenerController implementation (fdroid has NotificationListenerControllerImpl). */
    val notificationListenerController: NotificationListenerController by lazy {
        try {
            Class.forName("com.newoether.agora.notifications.NotificationListenerControllerImpl")
                .getDeclaredConstructor(android.content.Context::class.java)
                .newInstance(appContext) as NotificationListenerController
        } catch (_: ClassNotFoundException) {
            // Play flavor - return no-op implementation
            object : NotificationListenerController {
                override fun isListenerAccessGranted() = false
                override suspend fun openNotificationListenerSettings() {}
                override suspend fun getListenerStatus() = com.newoether.agora.data.NotificationListenerStatus(hasAccess = false, intentEnabled = false)
            }
        } catch (e: Exception) {
            com.newoether.agora.util.DebugLog.e("AppContainer", "NotificationListenerControllerImpl init failed", e)
            object : NotificationListenerController {
                override fun isListenerAccessGranted() = false
                override suspend fun openNotificationListenerSettings() {}
                override suspend fun getListenerStatus() = com.newoether.agora.data.NotificationListenerStatus(hasAccess = false, intentEnabled = false)
            }
        }
    }

    val notificationToolProvider: NotificationToolProvider by lazy {
        NotificationToolProvider(notificationStore, notificationReader)
    }

    // ── ViewModel Factory ─────────────────────────────────────

    fun chatViewModelFactory(): ChatViewModelFactory =
        ChatViewModelFactory(
            application, database, chatDao, settingsManager, memoryManager, skillManager, appContext, sandboxManagerFactory,
            autoBackupManager, conversationRepository, settingsRepository, conversationSettingsTransfers,
            ::startProcessServices, localProvider, providerRegistry,
            taskManager, loopManager, automationToolProvider, conversationExecutionCoordinator,
            automationExecutionGate, conversationStateRegistry, shellConfirmationController,
            mcpRegistry, mcpToolProvider, taskExecutionEngine,
            heartbeatToolProvider, smsToolProvider, smsDraftStore, smsStore, smsPoller, smsSender,
            notificationToolProvider,
        )
}
