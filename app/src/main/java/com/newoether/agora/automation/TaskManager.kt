package com.newoether.agora.automation

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.TaskRepository
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * App-scoped orchestrator for saved automation [TaskEntity]s.
 *
 * Every mutation and execution for the same task is serialized. Scheduled workers additionally
 * carry the persisted occurrence timestamp, so a queued worker becomes harmless as soon as the
 * user disables, edits, or deletes its Task. Manual jobs and WorkManager jobs share one reservation
 * set; this closes the run-now double-tap window and makes the running indicator exact.
 */
class TaskManager(
    private val taskRepository: TaskRepository,
    private val conversationRepository: ConversationRepository,
    private val engine: TaskExecutionEngine,
    private val scope: CoroutineScope,
    private val cancelScheduledExecution: suspend (String) -> Unit = {},
    private val cancelConversationLoop: suspend (String) -> Unit = {},
    private val refreshScheduling: () -> Unit = {},
    private val conversationExecutionCoordinator: ConversationExecutionCoordinator =
        ConversationExecutionCoordinator(),
    private val automationExecutionGate: AutomationExecutionGate = AutomationExecutionGate(),
    private val titleExecutionConversation: suspend (
        conversationId: String,
        response: String,
    ) -> Unit = { _, _ -> },
) {
    data class ExecutionSummary(
        val conversation: ChatConversation,
        val preview: String,
        val status: MessageStatus?,
        val timestamp: Long,
    )

    sealed interface ExecutionResult {
        data class Success(val conversationId: String, val response: String) : ExecutionResult
        data class Failure(
            val conversationId: String,
            val reason: String,
            val retryable: Boolean,
        ) : ExecutionResult
        /** No input/Run was persisted; a scheduled caller may safely retry this occurrence. */
        data class Deferred(val conversationId: String, val reason: String) : ExecutionResult
        data class Skipped(val reason: String, val advancesSchedule: Boolean = false) : ExecutionResult
    }

    sealed interface DeleteResult {
        data class Deleted(val task: TaskEntity) : DeleteResult
        data object NotFound : DeleteResult
        data class Ambiguous(val matches: List<TaskEntity>) : DeleteResult
    }

    private val mutableTasks = MutableStateFlow<List<TaskEntity>>(emptyList())
    val tasks: StateFlow<List<TaskEntity>> = mutableTasks.asStateFlow()
    @Volatile
    private var started = false

    /** Starts the Task list projection after the conversation list has published. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            taskRepository.getAllTasks().collect { mutableTasks.value = it }
        }
    }

    private val reservationMonitor = Any()
    private val reservedTaskIds = mutableSetOf<String>()
    private val _runningTaskIds = MutableStateFlow<Set<String>>(emptySet())
    val runningTaskIds: StateFlow<Set<String>> = _runningTaskIds.asStateFlow()

    private val taskCoordinator = ConversationExecutionCoordinator()
    private val manualJobs = ConcurrentHashMap<String, Job>()

    /** A scheduled occurrence delayed beyond this window is dropped (not made up). Prevents a
     *  "9am daily" from firing at 3am after the phone was off; a few-minute Doze lag still runs. */
    private companion object {
        const val MAX_OCCURRENCE_LATENCY_MS = 15L * 60_000L
    }

    fun executionsForTask(taskId: String): Flow<List<ChatConversation>> =
        conversationRepository.getExecutionsForTask(taskId)

    /** Observes both tables so a message status transition refreshes the execution log instantly. */
    fun executionSummariesForTask(taskId: String): Flow<List<ExecutionSummary>> = combine(
        conversationRepository.getExecutionsForTask(taskId),
        conversationRepository.observeExecutionMessagesForTask(taskId),
    ) { executions, messages ->
        val latestByConversation = messages
            .asSequence()
            .filter { it.participant == Participant.MODEL || it.participant == Participant.ERROR }
            .filterNot { isSyntheticToolMessageId(it.id) }
            .groupBy { it.conversationId }
            .mapValues { (_, values) -> values.maxByOrNull { it.timestamp } }
        executions.map { conversation ->
            val last = latestByConversation[conversation.id]
            ExecutionSummary(
                conversation = conversation,
                preview = last?.text.orEmpty(),
                status = last?.status,
                timestamp = last?.timestamp ?: 0L,
            )
        }
    }

    suspend fun getTask(id: String): TaskEntity? = taskRepository.getTask(id)

    suspend fun saveTask(task: TaskEntity) {
        if (task.name.isBlank() || task.prompt.isBlank()) {
            return
        }
        validateTask(task)
        // Cancel any in-flight execution only when the schedule actually changed, and do it under
        // the task lock so a running Worker unwinds cleanly before we overwrite its state. Cancelling
        // before the lock (the old behaviour) killed a mid-flight generation the instant the user
        // edited anything, even an unrelated field; and reading `previous` outside the lock could
        // miss a concurrent cron change. The stale-occurrence check in executeById still makes a
        // queued Worker harmless, but cancelling here also frees the running slot promptly.
        withTaskLock(task.id) {
            val previous = taskRepository.getTask(task.id)
            val scheduleChanged = !task.enabled || !task.hasSchedule() ||
                (previous != null && (previous.cronExpr != task.cronExpr || previous.runAt != task.runAt))
            if (scheduleChanged) cancelExecutions(task.id)
            saveTaskLocked(task)
        }
    }

    private suspend fun saveTaskLocked(task: TaskEntity) {
        validateTask(task)
        val now = System.currentTimeMillis()
        val previous = taskRepository.getTask(task.id)
        val nextRunAt = when {
            !task.enabled || !task.hasSchedule() -> 0L
            previous != null && previous.enabled && previous.cronExpr == task.cronExpr &&
                previous.runAt == task.runAt && previous.nextRunAt > now -> previous.nextRunAt
            else -> nextRunFor(task, now)
        }
        taskRepository.upsertTask(
            task.copy(
                name = task.name.trim(),
                prompt = task.prompt.trim(),
                cronExpr = task.cronExpr.trim(),
                modelId = task.modelId?.trim()?.takeIf { it.isNotEmpty() },
                nextRunAt = nextRunAt,
            )
        )
    }

    suspend fun createTask(
        name: String,
        prompt: String,
        cronExpr: String,
        modelId: String?,
        systemPromptId: String? = null,
    ): TaskEntity {
        require(name.isNotBlank()) { "Task name is required" }
        require(prompt.isNotBlank()) { "Task prompt is required" }
        require(cronExpr.isBlank() || CronExpression.isValid(cronExpr)) {
            "Invalid cron expression: $cronExpr"
        }
        val task = TaskEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            prompt = prompt.trim(),
            modelId = modelId?.trim()?.takeIf { it.isNotEmpty() },
            systemPromptId = systemPromptId?.trim()?.takeIf { it.isNotEmpty() },
            cronExpr = cronExpr.trim(),
            nextRunAt = 0L,
        )
        saveTask(task)
        return taskRepository.getTask(task.id) ?: task
    }

    suspend fun listTasksSnapshot(): List<TaskEntity> = taskRepository.getAllTasksSnapshot()

    /** Cancels manual jobs and all queued/running WorkManager occurrences before graph import. */
    suspend fun cancelAllExecutionsForImport() {
        taskRepository.getAllTasksSnapshot().forEach { cancelExecutions(it.id) }
    }

    fun refreshSchedulingAfterImport() = refreshScheduling()

    suspend fun deleteTask(id: String) {
        cancelExecutions(id)
        withTaskLock(id) {
            taskRepository.getTask(id)?.let { deleteTaskAndExecutionsLocked(it) }
        }
    }

    suspend fun deleteTaskByIdOrName(value: String): DeleteResult {
        val all = taskRepository.getAllTasksSnapshot()
        val exactId = all.firstOrNull { it.id == value }
        val target = exactId ?: run {
            val matches = all.filter { it.name == value }
            return when (matches.size) {
                0 -> DeleteResult.NotFound
                1 -> deleteResolvedTask(matches.single())
                else -> DeleteResult.Ambiguous(matches)
            }
        }
        return deleteResolvedTask(target)
    }

    private suspend fun deleteResolvedTask(task: TaskEntity): DeleteResult {
        cancelExecutions(task.id)
        return withTaskLock(task.id) {
            val fresh = taskRepository.getTask(task.id) ?: return@withTaskLock DeleteResult.NotFound
            deleteTaskAndExecutionsLocked(fresh)
            DeleteResult.Deleted(fresh)
        }
    }

    private suspend fun deleteTaskAndExecutionsLocked(task: TaskEntity) {
        conversationRepository.getExecutionsForTask(task.id).first().forEach { execution ->
            conversationExecutionCoordinator.withConversationLock(execution.id) {
                val entity = conversationRepository.getConversation(execution.id)
                    ?: return@withConversationLock
                // A Task owns all of its execution conversations. Stop a nested Loop before the
                // FK cascade removes the loop row and conversation underneath a running worker.
                cancelConversationLoop(execution.id)
                conversationRepository.deleteConversation(execution.id)
            }
        }
        taskRepository.deleteTask(task.id)
    }

    fun nextRunFor(task: TaskEntity, now: Long): Long = when {
        !task.enabled -> 0L
        // One-shot: its instant IS the next run, and only while still in the future. Returning 0
        // once it has passed is what retires the task after it fires (see finishScheduledRun).
        task.runAt != null -> task.runAt.takeIf { it > now } ?: 0L
        task.cronExpr.isNotBlank() -> CronExpression.parse(task.cronExpr)?.next(now) ?: 0L
        else -> 0L
    }

    /** Starts one exclusive manual run. A second tap while queued/running is ignored. */
    fun runNow(task: TaskEntity, preservePersistedEnabled: Boolean = true) {
        if (task.name.isBlank() || task.prompt.isBlank() || !reserve(task.id)) return

        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTaskLock(task.id) {
                    // List snapshots may be stale immediately after a switch change, so list Run
                    // Now keeps the persisted enabled state. The editor explicitly opts out because
                    // its current draft is the configuration the command must save.
                    val enabledForSave = if (preservePersistedEnabled) {
                        taskRepository.getTask(task.id)?.enabled ?: task.enabled
                    } else {
                        task.enabled
                    }
                    saveTaskLocked(task.copy(enabled = enabledForSave))
                    val persisted = taskRepository.getTask(task.id) ?: task
                    executeLocked(
                        task = persisted,
                        requestedConversationId = UUID.randomUUID().toString(),
                        foregroundServiceManagedExternally = false,
                        finishManualSchedule = true,
                    )
                }
            } finally {
                release(task.id)
            }
        }
        manualJobs[task.id] = job
        job.invokeOnCompletion { manualJobs.remove(task.id, job) }
        job.start()
    }

    /** WorkManager entry point. [expectedRunAt] invalidates stale queued occurrences. */
    suspend fun executeById(
        id: String,
        executionId: String,
        expectedRunAt: Long = 0L,
    ): ExecutionResult = withTaskLock(id) {
        if (!reserve(id)) return@withTaskLock ExecutionResult.Skipped("Task is already running")
        try {
            val task = taskRepository.getTask(id)
                ?: return@withTaskLock ExecutionResult.Skipped("Task no longer exists")
            if (task.name.isBlank() || task.prompt.isBlank()) {
                taskRepository.upsertTask(task.copy(enabled = false, nextRunAt = 0L))
                return@withTaskLock ExecutionResult.Skipped(
                    "Task is incomplete and was disabled", advancesSchedule = true,
                )
            }
            if (!task.enabled || !task.hasSchedule()) {
                return@withTaskLock ExecutionResult.Skipped("Task is disabled", advancesSchedule = true)
            }
            if (expectedRunAt > 0L && task.nextRunAt != expectedRunAt) {
                return@withTaskLock ExecutionResult.Skipped(
                    "Scheduled occurrence is stale", advancesSchedule = true,
                )
            }
            // Drop an occurrence that was delayed far past its slot (device was off, Doze, etc.)
            // rather than running a "9am report" at 3am after a reboot. The schedule advances to
            // the next real occurrence via finishScheduledRun. A short delay (under the threshold)
            // still runs, so a few-minute Doze lag doesn't silently drop a legitimate firing.
            if (expectedRunAt > 0L) {
                val lateByMs = System.currentTimeMillis() - expectedRunAt
                if (lateByMs > MAX_OCCURRENCE_LATENCY_MS) {
                    return@withTaskLock ExecutionResult.Skipped(
                        "Scheduled occurrence is too late ($lateByMs ms)", advancesSchedule = true,
                    )
                }
            }
            executeLocked(
                task = task,
                requestedConversationId = executionId,
                foregroundServiceManagedExternally = true,
                finishManualSchedule = false,
            )
        } finally {
            release(id)
        }
    }

    /** Finalizes only the occurrence that actually ran; a later user edit always wins. */
    suspend fun finishScheduledRun(taskId: String, expectedRunAt: Long = 0L) {
        withTaskLock(taskId) {
            val fresh = taskRepository.getTask(taskId) ?: return@withTaskLock
            val now = System.currentTimeMillis()
            val next = when {
                // The expected occurrence ran (or was skipped-as-stale after the alarm fired).
                expectedRunAt <= 0L || fresh.nextRunAt == expectedRunAt -> nextRunFor(fresh, now)
                // A concurrent edit moved nextRunAt forward to a still-future time: respect it.
                fresh.nextRunAt > now -> fresh.nextRunAt
                // The persisted nextRunAt is in the past (e.g. a stale occurrence that was never
                // advanced). Recompute from cron so the scheduler re-arms the next real occurrence
                // instead of leaving the task permanently un-armed (the old H4 silent-death path).
                else -> nextRunFor(fresh, now)
            }
            // A one-shot has no "next": once its instant is behind us the task retires itself, so
            // it can never re-arm (and the editor shows it as un-armed rather than silently idle).
            val retireOneShot = fresh.runAt != null && next <= 0L
            if (retireOneShot) {
                taskRepository.upsertTask(fresh.copy(lastRunAt = now, nextRunAt = 0L, enabled = false))
            } else if (next != fresh.nextRunAt) {
                taskRepository.upsertTask(fresh.copy(lastRunAt = now, nextRunAt = next))
            } else if (fresh.lastRunAt != now) {
                taskRepository.upsertTask(fresh.copy(lastRunAt = now))
            }
        }
    }

    private suspend fun finishManualRunLocked(taskId: String) {
        val fresh = taskRepository.getTask(taskId) ?: return
        val now = System.currentTimeMillis()
        val next = fresh.nextRunAt.takeIf { it > now } ?: nextRunFor(fresh, now)
        taskRepository.upsertTask(fresh.copy(lastRunAt = now, nextRunAt = next))
    }

    private suspend fun executeLocked(
        task: TaskEntity,
        requestedConversationId: String,
        foregroundServiceManagedExternally: Boolean,
        finishManualSchedule: Boolean,
    ): ExecutionResult = automationExecutionGate.withExecution {
        val requestedResult = conversationExecutionCoordinator
            .withAutomationConversationLock(requestedConversationId) {
                conversationRepository.recoverConversationRuntime(requestedConversationId)
                val existing = conversationRepository.getConversation(requestedConversationId)
                if (existing != null && existing.taskId != task.id) {
                    null
                } else {
                    executeConversationLocked(
                        task = task,
                        conversationId = requestedConversationId,
                        existing = existing,
                        foregroundServiceManagedExternally = foregroundServiceManagedExternally,
                        finishManualSchedule = finishManualSchedule,
                    )
                }
            }
        if (requestedResult != null) return@withExecution requestedResult

        val conversationId = UUID.randomUUID().toString()
        conversationExecutionCoordinator.withAutomationConversationLock(conversationId) {
            conversationRepository.recoverConversationRuntime(conversationId)
            executeConversationLocked(
                task = task,
                conversationId = conversationId,
                existing = null,
                foregroundServiceManagedExternally = foregroundServiceManagedExternally,
                finishManualSchedule = finishManualSchedule,
            )
        }
    }

    private suspend fun executeConversationLocked(
        task: TaskEntity,
        conversationId: String,
        existing: ChatEntity?,
        foregroundServiceManagedExternally: Boolean,
        finishManualSchedule: Boolean,
    ): ExecutionResult {
        if (existing != null) {
            val recovery = recoverExistingExecution(existing)
            if (recovery != null) return recovery
            conversationRepository.deleteConversation(conversationId)
        }

        conversationRepository.upsertConversation(
            ChatEntity(
                id = conversationId,
                title = task.name,
                modelId = task.modelId,
                // A saved prompt is resolved per run through the ordinary conversation path, so its
                // placeholders expand with this run's values instead of a frozen copy.
                systemPromptId = task.systemPromptId,
                taskId = task.id,
                origin = "task",
            )
        )

        val result = engine.runOnceWithAutomationGuardsHeld(
            conversationId = conversationId,
            userText = task.prompt,
            modelId = task.modelId,
            // null hands prompt resolution back to the conversation, which carries the task's saved
            // prompt id. Without a saved prompt the literal text (blank = no prompt) still wins.
            systemPromptOverride = if (task.systemPromptId != null) null else task.systemPrompt ?: "",
            foregroundServiceManagedExternally = foregroundServiceManagedExternally,
            requestKind = "task",
        )
        val outcome = when (result) {
            is TaskExecutionEngine.Result.Success -> {
                try {
                    titleExecutionConversation(conversationId, result.text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.e(
                        "TaskManager",
                        "Task execution title update failed for conversation=$conversationId",
                        e,
                    )
                }
                ExecutionResult.Success(conversationId, result.text)
            }
            is TaskExecutionEngine.Result.Busy ->
                ExecutionResult.Deferred(conversationId, result.reason)
            is TaskExecutionEngine.Result.Failure -> {
                DebugLog.e("TaskManager", "Task run failed")
                ExecutionResult.Failure(
                    conversationId = conversationId,
                    reason = result.reason,
                    retryable = isRetryableFailure(result.reason),
                )
            }
        }
        if (finishManualSchedule) finishManualRunLocked(task.id)
        return outcome
    }

    /**
     * Recovers a deterministic WorkManager execution without replaying an uncertain side effect.
     * A clean terminal provider failure may be retried; successful, stopped, tool-bearing, or
     * process-interrupted attempts are terminal for this occurrence.
     */
    private suspend fun recoverExistingExecution(existing: ChatEntity): ExecutionResult? {
        val recoverySnapshot = conversationRepository.withProviderContextSnapshot {
            val topology =
                conversationRepository.getProviderContextTopologySnapshot(existing.id)
                    ?: return@withProviderContextSnapshot null
            if (topology.messages.isEmpty()) return@withProviderContextSnapshot null
            val assistantId = topology.messages
                .asSequence()
                .filter {
                    it.participant == Participant.MODEL || it.participant == Participant.ERROR
                }
                .filterNot { isSyntheticToolMessageId(it.id) }
                .maxByOrNull { it.timestamp }
                ?.id
            topology to assistantId?.let { conversationRepository.getMessage(it) }
        } ?: return null
        val (topology, assistant) = recoverySnapshot
        return when (assistant?.status) {
            MessageStatus.SUCCESS -> ExecutionResult.Success(existing.id, assistant.text)
            MessageStatus.ERROR -> {
                if (topology.messages.any { isSyntheticToolMessageId(it.id) }) {
                    ExecutionResult.Failure(
                        existing.id,
                        assistant.text.ifBlank { "Previous attempt failed after executing a tool" },
                        retryable = false,
                    )
                } else {
                    null
                }
            }
            MessageStatus.STOPPED -> ExecutionResult.Failure(
                existing.id,
                assistant.text.ifBlank { "Previous attempt was stopped" },
                retryable = false,
            )
            MessageStatus.SENDING,
            MessageStatus.THINKING,
            MessageStatus.TOOL_CALLING,
            MessageStatus.TRANSCRIBING,
            null -> {
                assistant?.let {
                    conversationRepository.upsertMessage(
                        it.copy(
                            text = "Execution interrupted before completion",
                            status = MessageStatus.ERROR,
                        )
                    )
                }
                ExecutionResult.Failure(
                    existing.id,
                    "Execution interrupted before completion",
                    retryable = false,
                )
            }
        }
    }

    private fun isSyntheticToolMessageId(messageId: String): Boolean =
        messageId.startsWith(Constants.TOOL_MSG_PREFIX) ||
            messageId.startsWith(Constants.RESULT_MSG_PREFIX)

    private fun isRetryableFailure(reason: String): Boolean =
        !reason.startsWith("No model selected") &&
            !reason.startsWith("Provider not configured") &&
            !reason.startsWith("Conversation not found") &&
            !reason.startsWith("Execution cancelled")

    private fun validateTask(task: TaskEntity) {
        require(task.name.isNotBlank()) { "Task name is required" }
        require(task.prompt.isNotBlank()) { "Task prompt is required" }
        require(task.cronExpr.isBlank() || CronExpression.isValid(task.cronExpr)) {
            "Invalid cron expression: ${task.cronExpr}"
        }
    }

    private suspend fun cancelExecutions(taskId: String) {
        manualJobs[taskId]?.cancel()
        runCatching { cancelScheduledExecution(taskId) }
            .onFailure { DebugLog.w("TaskManager", "Failed to cancel work for $taskId", it) }
    }

    private fun reserve(taskId: String): Boolean = synchronized(reservationMonitor) {
        if (!reservedTaskIds.add(taskId)) return@synchronized false
        _runningTaskIds.value = reservedTaskIds.toSet()
        true
    }

    private fun release(taskId: String) = synchronized(reservationMonitor) {
        reservedTaskIds.remove(taskId)
        _runningTaskIds.value = reservedTaskIds.toSet()
    }

    private suspend fun <T> withTaskLock(taskId: String, block: suspend () -> T): T =
        taskCoordinator.withConversationLock("task:$taskId", block)
}
