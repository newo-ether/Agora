package com.newoether.agora.viewmodel

import androidx.lifecycle.viewModelScope
import com.newoether.agora.data.local.TaskEntity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Task automation reached through the chat view model.
 *
 * The Tasks UI needs the same app-scoped TaskManager the generation pipeline drives, so these stay
 * thin accessors instead of a second source of task state. They live beside the view model rather
 * than inside it because task CRUD is not part of a chat turn.
 */
internal val ChatViewModel.tasks: StateFlow<List<TaskEntity>> get() = taskManager.tasks

internal val ChatViewModel.runningTaskIds: StateFlow<Set<String>> get() = taskManager.runningTaskIds

internal fun ChatViewModel.executionSummariesForTask(taskId: String) =
    taskManager.executionSummariesForTask(taskId)

internal suspend fun ChatViewModel.getTask(taskId: String) = taskManager.getTask(taskId)

internal fun ChatViewModel.saveTask(task: TaskEntity) {
    viewModelScope.launch { taskManager.saveTask(task) }
}

internal fun ChatViewModel.deleteTask(taskId: String) {
    viewModelScope.launch { taskManager.deleteTask(taskId) }
}

internal fun ChatViewModel.runTaskNow(task: TaskEntity, preservePersistedEnabled: Boolean = true) =
    taskManager.runNow(task, preservePersistedEnabled)
