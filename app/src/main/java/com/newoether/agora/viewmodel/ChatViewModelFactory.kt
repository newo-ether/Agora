package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.newoether.agora.data.AutoBackupManager
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.automation.TaskExecutionEngine
import com.newoether.agora.automation.TaskManager
import com.newoether.agora.automation.LoopManager
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.automation.AutomationExecutionGate
import com.newoether.agora.tool.AutomationToolProvider
import com.newoether.agora.tool.HeartbeatToolProvider
import com.newoether.agora.tool.McpToolProvider
import com.newoether.agora.tool.NotificationToolProvider
import com.newoether.agora.tool.SmsToolProvider
import com.newoether.agora.mcp.McpRegistry
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.sandbox.SandboxManagerFactory

class ChatViewModelFactory(
    private val application: Application,
    private val database: ChatDatabase,
    private val chatDao: ChatDao,
    private val settingsManager: SettingsManager,
    private val memoryManager: MemoryManager,
    private val skillManager: SkillManager,
    private val context: Context,
    private val sandboxFactory: SandboxManagerFactory? = null,
    private val autoBackupManager: AutoBackupManager,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val conversationSettingsTransfers: ConversationSettingsTransferCoordinator,
    private val startProcessServices: () -> Unit,
    private val localProvider: LocalProvider,
    private val providerRegistry: ProviderRegistry,
    private val taskManager: TaskManager,
    private val loopManager: LoopManager,
    private val automationToolProvider: AutomationToolProvider,
    private val conversationExecutionCoordinator: ConversationExecutionCoordinator,
    private val automationExecutionGate: AutomationExecutionGate,
    private val conversationStateRegistry: ConversationStateRegistry,
    private val shellConfirmationController: ShellConfirmationController,
    private val mcpRegistry: McpRegistry,
    private val mcpToolProvider: McpToolProvider,
    private val taskExecutionEngine: TaskExecutionEngine,
    private val heartbeatToolProvider: HeartbeatToolProvider,
    private val smsToolProvider: SmsToolProvider,
    private val smsDraftStore: com.newoether.agora.data.SmsDraftStore,
    private val smsStore: com.newoether.agora.data.SmsStore,
    private val smsPoller: com.newoether.agora.data.SmsPoller,
    private val smsSender: com.newoether.agora.sms.SmsSender,
    private val notificationToolProvider: NotificationToolProvider,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(
                application, database, chatDao, settingsManager, memoryManager, skillManager, context, sandboxFactory,
                autoBackupManager, conversationRepository, settingsRepository,
                conversationSettingsTransfers, startProcessServices, localProvider, providerRegistry,
                taskManager, loopManager, automationToolProvider, conversationExecutionCoordinator,
                automationExecutionGate, conversationStateRegistry, shellConfirmationController,
                mcpRegistry, mcpToolProvider, taskExecutionEngine,
                heartbeatToolProvider, smsToolProvider, smsDraftStore, smsStore, smsPoller, smsSender,
                notificationToolProvider,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
