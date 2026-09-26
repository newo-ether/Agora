package com.newoether.agora.ui.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.newoether.agora.automation.TaskManager
import com.newoether.agora.data.local.TaskEntity

internal data class TaskExecutionHistorySnapshot(
    val taskId: String,
    val executions: List<TaskManager.ExecutionSummary>,
)

internal class TaskEditorSessionViewModel : ViewModel() {
    var activeTaskId by mutableStateOf<String?>(null)
        private set
    var isNew by mutableStateOf(false)
        private set
    private var baseTask by mutableStateOf<TaskEntity?>(null)

    var name by mutableStateOf("")
        private set
    var prompt by mutableStateOf("")
        private set
    var modelId by mutableStateOf<String?>(null)
        private set
    var systemPromptId by mutableStateOf<String?>(null)
        private set
    var cronExpr by mutableStateOf("")
        private set
    var runAt by mutableStateOf<Long?>(null)
        private set
    var scheduleEditorMode by mutableStateOf(ScheduleEditorMode.DAILY)
        private set
    var enabled by mutableStateOf(true)
        private set

    var detailListIndex by mutableIntStateOf(0)
        private set
    var detailListOffset by mutableIntStateOf(0)
        private set
    var historyPreview by mutableStateOf(TaskHistoryPreviewState.Idle)
        private set
    private var executionHistorySnapshot by mutableStateOf<TaskExecutionHistorySnapshot?>(null)

    val activeTaskSnapshot: TaskEntity?
        get() = baseTask

    fun open(task: TaskEntity, isNew: Boolean) {
        if (activeTaskId == task.id) return
        activeTaskId = task.id
        baseTask = task
        this.isNew = isNew
        name = task.name
        prompt = task.prompt
        modelId = task.modelId
        systemPromptId = task.systemPromptId
        cronExpr = task.cronExpr
        runAt = task.runAt
        scheduleEditorMode = initialScheduleEditorMode(task.cronExpr, task.runAt)
        enabled = task.enabled
        detailListIndex = 0
        detailListOffset = 0
        historyPreview = TaskHistoryPreviewState.Idle.copy(generation = historyPreview.generation + 1L)
        executionHistorySnapshot = null
    }

    fun updateName(value: String) {
        name = value
    }

    fun updatePrompt(value: String) {
        prompt = value
    }

    fun updateModelId(value: String?) {
        modelId = value
    }

    fun updateSystemPromptId(value: String?) {
        systemPromptId = value
    }

    fun updateSchedule(cronExpr: String, runAt: Long?) {
        this.cronExpr = cronExpr
        this.runAt = runAt
    }

    fun updateScheduleEditorMode(value: ScheduleEditorMode) {
        scheduleEditorMode = value
    }

    fun updateEnabled(value: Boolean) {
        enabled = value
    }

    fun current(latestTask: TaskEntity? = null): TaskEntity? {
        val activeId = activeTaskId ?: return null
        val source = latestTask?.takeIf { it.id == activeId }
            ?: baseTask?.takeIf { it.id == activeId }
            ?: return null
        return source.copy(
            name = name.trim(),
            prompt = prompt,
            modelId = modelId,
            systemPromptId = systemPromptId,
            cronExpr = cronExpr,
            runAt = runAt,
            enabled = enabled,
        )
    }

    fun updateScroll(index: Int, offset: Int) {
        detailListIndex = index.coerceAtLeast(0)
        detailListOffset = offset.coerceAtLeast(0)
    }

    fun retainExecutionHistory(
        taskId: String,
        executions: List<TaskManager.ExecutionSummary>,
    ) {
        if (activeTaskId != taskId) return
        executionHistorySnapshot = TaskExecutionHistorySnapshot(taskId, executions.toList())
    }

    fun executionHistoryFor(taskId: String): List<TaskManager.ExecutionSummary>? =
        executionHistorySnapshot
            ?.takeIf { it.taskId == taskId && activeTaskId == taskId }
            ?.executions

    fun openHistory(
        previewConversationId: String,
        currentConversationId: String?,
        isNewChatMode: Boolean,
    ) {
        val taskId = activeTaskId ?: return
        historyPreview = historyPreview.open(
            taskId = taskId,
            previewConversationId = previewConversationId,
            currentConversationId = currentConversationId,
            isNewChatMode = isNewChatMode,
        )
    }

    fun observeHistoryDestination(
        currentConversationId: String?,
        isNewChatMode: Boolean,
        isSwitching: Boolean,
    ) {
        historyPreview = historyPreview.observeDestination(
            currentConversationId = currentConversationId,
            isNewChatMode = isNewChatMode,
            isSwitching = isSwitching,
        )
    }

    fun requestHistoryReturn() {
        historyPreview = historyPreview.requestReturn()
    }

    fun beginHistoryReturnRestore(
        onRestore: (TaskHistoryPreviewState, () -> Unit) -> Unit = { _, _ -> },
    ): TaskHistoryPreviewState? {
        val current = historyPreview
        val restoring = current.beginReturnRestore()
        if (restoring == current) return null
        historyPreview = restoring
        onRestore(restoring) { markHistoryReturnRestoreFailed(restoring.generation) }
        return restoring
    }

    fun markHistoryReturnOverlayCovered(expectedGeneration: Long) {
        historyPreview = historyPreview.markReturnOverlayCovered(expectedGeneration)
    }

    fun markHistoryReturnRestoreFailed(expectedGeneration: Long) {
        historyPreview = historyPreview.markReturnRestoreFailed(expectedGeneration)
    }

    fun clear() {
        activeTaskId = null
        detailListIndex = 0
        detailListOffset = 0
        historyPreview = TaskHistoryPreviewState.Idle.copy(generation = historyPreview.generation + 1L)
        executionHistorySnapshot = null
        // Field values and the base snapshot may still be read by an outgoing animation slot, but
        // no inactive session can expose, restore, or commit them. The next open replaces them.
    }
}
