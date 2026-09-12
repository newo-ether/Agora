package com.newoether.agora.automation

import android.content.Context
import com.newoether.agora.data.HeartbeatManager
import com.newoether.agora.data.NotificationStore
import com.newoether.agora.data.SmsPoller
import com.newoether.agora.data.SmsStore
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.getOrCreateHeartbeatConversationId
import com.newoether.agora.data.repository.migrateHeartbeatConversationSetting
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.service.HeartbeatNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dedicated scheduler for heartbeat and SMS polling.
 *
 * Runs in the Daemon's coroutine scope. NOT to be confused with [AutomationScheduler]
 * which uses AlarmManager for Task/Loop scheduling.
 *
 * Loop runs every 60 seconds:
 * 1. Checks if heartbeat is due → runs heartbeat
 * 2. Polls SMS if enabled
 */
class HeartbeatScheduler(
    private val appContext: Context,
    private val heartbeatManager: HeartbeatManager,
    private val settingsRepository: SettingsRepository,
    private val smsStore: SmsStore,
    private val smsPoller: SmsPoller,
    private val notificationStore: NotificationStore,
    private val heartbeatNotifier: HeartbeatNotifier,
    private val taskExecutionEngine: TaskExecutionEngine,
    private val appForegroundTracker: AppForegroundTracker,
    private val loopManager: LoopManager,
) {
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Prevents a manual run from overlapping the scheduled loop's run. */
    @Volatile
    private var heartbeatInFlight = false

    @Volatile
    private var migrated = false

    fun start() {
        if (job != null && job!!.isActive) return
        job = scope.launch {
            while (true) {
                delay(60_000) // 60 second loop
                try {
                    if (!migrated) {
                        awaitInitialLoad()
                        getConversationRepository().migrateHeartbeatConversationSetting(
                            settingsRepository,
                        )
                        migrated = true
                    }
                    runCycle()
                } catch (e: Exception) {
                    com.newoether.agora.util.DebugLog.e("HeartbeatScheduler", "Cycle error", e)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Runs a single heartbeat immediately, bypassing the interval/active-hours gate.
     * Safe to call while the scheduler loop is running; overlapping runs are skipped.
     */
    suspend fun runHeartbeatNow() {
        executeHeartbeat()
    }

    private suspend fun runCycle() {
        // Run heartbeat if due
        if (heartbeatManager.isHeartbeatDue()) {
            awaitInitialLoad()
            executeHeartbeat()
        }

        // Poll SMS if enabled, rate-limited by the configured poll interval
        if (settingsRepository.smsReadEnabled.value) {
            awaitInitialLoad()
            pollSmsIfDue()
        }

        // Notifications are push-driven - no polling needed
        // The pending queue is consumed during heartbeat
    }

    private suspend fun awaitInitialLoad() {
        settingsRepository.awaitInitialLoad()
    }

    private suspend fun pollSmsIfDue() {
        val intervalMinutes = settingsRepository.smsPollIntervalMinutes.value
        if (intervalMinutes <= 0) return // 0 = Never
        // Persisted backoff mirrors Kai: compare against max(lastSync, lastAttempt) so a
        // failing poll (or one skipped for permission) waits out the interval instead of
        // retrying every 60s tick.
        val now = System.currentTimeMillis()
        val state = smsStore.getSyncStateOnce()
        val lastActivityMs = maxOf(state.lastSyncEpochMs, state.lastAttemptEpochMs)
        if (now - lastActivityMs < intervalMinutes * 60_000L) return
        smsPoller.poll()
    }

    private class HeartbeatSnapshot(
        val prompt: String,
        val smsIds: List<Long>,
        val notificationKeys: List<String>,
    )

    // Runs exactly one heartbeat, guarded against overlap (loop vs manual trigger).
    private suspend fun executeHeartbeat() {
        if (heartbeatInFlight) return
        heartbeatInFlight = true
        try {
            runHeartbeat()
        } finally {
            heartbeatInFlight = false
        }
    }

    // This method is called from runCycle
    private suspend fun runHeartbeat() {
        // Resolve the heartbeat conversation: the user-selected one, or the dedicated
        // auto-created conversation when "Auto (dedicated)" is selected. The model setting
        // always applies as an override; null inherits the conversation's model.
        val userSelectedId = settingsRepository.heartbeatConversationId.value
        val heartbeatConversationId: String =
            if (!userSelectedId.isNullOrBlank()) {
                userSelectedId
            } else {
                getConversationRepository().getOrCreateHeartbeatConversationId(
                    modelId = settingsRepository.heartbeatModel.value,
                )
            }
        val modelOverride: String? = settingsRepository.heartbeatModel.value

        val snapshot = buildPrompt(heartbeatConversationId)

        val result = taskExecutionEngine.runOnce(
            conversationId = heartbeatConversationId,
            userText = snapshot.prompt,
            modelId = modelOverride,
            requestKind = "heartbeat",
        )

        val success = result is TaskExecutionEngine.Result.Success

        // Consume exactly the snapshot the AI just saw — and only on success, so a failed
        // run (or messages that arrived during the call) survive to the next heartbeat.
        if (success) {
            if (snapshot.smsIds.isNotEmpty()) {
                smsStore.removePending(snapshot.smsIds)
            }
            if (snapshot.notificationKeys.isNotEmpty()) {
                notificationStore.removePending(snapshot.notificationKeys)
            }
        }
        notificationStore.performRetentionSweep()

        // Log the result
        val resultText = when (result) {
            is TaskExecutionEngine.Result.Success -> result.text
            is TaskExecutionEngine.Result.Failure -> result.reason
            is TaskExecutionEngine.Result.Busy -> ""
        }
        heartbeatManager.setLastHeartbeatEpochMs(System.currentTimeMillis())

        // Record the outcome in Room (kept to the 5 newest rows).
        heartbeatManager.recordHeartbeat(success, if (success) null else resultText.ifBlank { null })

        // Send push notification if backgrounded
        if (!appForegroundTracker.isInForeground && !success) {
            sendHeartbeatNotification(resultText)
        }
    }

    private suspend fun buildPrompt(heartbeatConversationId: String): HeartbeatSnapshot {
        val customPrompt = settingsRepository.heartbeatPrompt.value
        val pendingSms = smsStore.getPendingSnapshot()
        val pendingNotifications = notificationStore.getPendingSnapshot()
        
        val conversationRepository = getConversationRepository()
        val runs = conversationRepository.getRunsForConversationSnapshot(heartbeatConversationId)
        val recentMessages = conversationRepository.getMessagesForRuns(runs.map { it.id })
        val recentResponses = recentMessages
            .filter { it.participant == com.newoether.agora.model.Participant.MODEL }
            .takeLast(3)
            .map { it.text }

        val builder = com.newoether.agora.data.HeartbeatPromptBuilder(
            taskManager = getTaskManager(),
            loopManager = getLoopManager(),
            conversationRepository = conversationRepository,
            memoryManager = getMemoryManager(),
            taskRepository = getTaskRepository(),
        )
        return HeartbeatSnapshot(
            prompt = builder.buildHeartbeatPrompt(
                customPrompt = customPrompt,
                pendingSms = pendingSms,
                pendingNotifications = pendingNotifications,
                recentResponses = recentResponses,
            ),
            smsIds = pendingSms.map { it.id },
            notificationKeys = pendingNotifications.map { it.id },
        )
    }

    private fun getTaskRepository(): com.newoether.agora.data.repository.TaskRepository {
        val application = appContext.applicationContext as com.newoether.agora.AgoraApplication
        return application.requireContainer().taskRepository
    }

    private fun getTaskManager(): TaskManager {
        val application = appContext.applicationContext as com.newoether.agora.AgoraApplication
        return application.requireContainer().taskManager
    }

    private fun getLoopManager(): LoopManager {
        val application = appContext.applicationContext as com.newoether.agora.AgoraApplication
        return application.requireContainer().loopManager
    }

    private fun getConversationRepository(): ConversationRepository {
        val application = appContext.applicationContext as com.newoether.agora.AgoraApplication
        return application.requireContainer().conversationRepository
    }

    private fun getMemoryManager(): com.newoether.agora.data.MemoryManager {
        val application = appContext.applicationContext as com.newoether.agora.AgoraApplication
        return application.requireContainer().memoryManager
    }

    private suspend fun sendHeartbeatNotification(message: String) {
        heartbeatNotifier.sendHeartbeatNotification("Heartbeat Check", message)
    }
}