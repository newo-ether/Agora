package com.newoether.agora.ui.tasks

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskEditorSourceContractTest {
    @Test
    fun explicitSaveBackAndRunNowRemainDistinctCommands() {
        val editor = source("ui/tasks/TaskEditorPage.kt")
        val detail = editor
            .substringAfter("internal fun TaskDetailPage(")
            .substringBefore("internal fun formatDateTime(")

        assertTrue(detail.contains("BackHandler(enabled = backHandlingEnabled) { onBack() }"))
        assertFalse(detail.contains("BackHandler { onBack() }"))
        assertFalse(detail.contains("fun leave()"))
        assertTrue(detail.contains("viewModel.saveTask(current())\n                    onBack()"))
        assertTrue(detail.contains("viewModel.runTaskNow("))
        assertTrue(detail.contains("preservePersistedEnabled = false"))
        assertTrue(detail.contains("collectAsState(initial = null)"))
        assertTrue(detail.contains("taskExecutionHistoryForPresentation("))
        assertTrue(detail.contains("editorSession.retainExecutionHistory(task.id, executions)"))
        assertFalse(detail.contains("collectAsState(initial = emptyList())"))
        assertFalse(detail.contains("rememberSaveable"))
    }

    @Test
    fun taskOverlayBackHandlersOnlyOwnBackWhileOverlayIsVisible() {
        val activity = source("MainActivity.kt")
        val tasks = source("ui/tasks/TasksScreen.kt")
        val editor = source("ui/tasks/TaskEditorPage.kt")
        val detail = editor
            .substringAfter("internal fun TaskDetailPage(")
            .substringBefore("internal fun formatDateTime(")
        val listCall = tasks
            .substringAfter("TasksListPage(")
            .substringBefore("onNewTask =")
        val detailCall = tasks
            .substringAfter("TaskDetailPage(")
            .substringBefore("onBack = {")

        assertTrue(activity.contains("backHandlingEnabled = showTasks"))
        assertTrue(tasks.contains("backHandlingEnabled: Boolean"))
        assertTrue(listCall.contains("backHandlingEnabled = backHandlingEnabled"))
        assertTrue(detailCall.contains("backHandlingEnabled = backHandlingEnabled"))
        assertTrue(tasks.contains("BackHandler(enabled = backHandlingEnabled) { onBack() }"))
        assertTrue(detail.contains("BackHandler(enabled = backHandlingEnabled) { onBack() }"))
        assertFalse(tasks.contains("BackHandler { onBack() }"))
        assertFalse(detail.contains("BackHandler { onBack() }"))
    }

    @Test
    fun taskEditorUsesTheActivitySessionWithoutPageWideSaveableCapture() {
        val activity = source("MainActivity.kt")
        val tasks = source("ui/tasks/TasksScreen.kt")
        val editor = source("ui/tasks/TaskEditorPage.kt")
        val preview = source("ui/tasks/TaskHistoryPreviewState.kt")
        val session = source("ui/tasks/TaskEditorSessionViewModel.kt")

        assertTrue(activity.contains("val taskEditorSession: TaskEditorSessionViewModel = viewModel()"))
        assertTrue(activity.contains("TaskHistoryDestinationEffect("))
        assertTrue(activity.contains("editorSession = taskEditorSession"))
        assertTrue(activity.contains("previewConversationId = conversationId"))
        assertTrue(activity.contains("restoreConversationDestination"))
        assertTrue(activity.contains("restoreNewChatDestination"))
        val returnHandler = activity
            .substringAfter("taskEditorSession.requestHistoryReturn()")
            .substringBefore("drawerEnabled = !taskHistoryPreview.active")
        assertTrue(
            returnHandler.indexOf("beginHistoryReturnRestore") <
                returnHandler.indexOf("topLevelPresentation.present(TopLevelPresentation.TASKS)"),
        )
        assertTrue(tasks.contains("editorSession: TaskEditorSessionViewModel"))
        assertTrue(preview.contains("destinationObserved"))
        assertTrue(preview.contains("!destinationObserved && previewIsSelected"))
        assertTrue(preview.contains("returnOverlayCovered"))
        assertTrue(preview.contains("returnDestinationObserved"))
        assertTrue(preview.contains("restoreRequested"))
        assertTrue(preview.contains("generation == expectedGeneration"))
        assertTrue(session.contains("TaskExecutionHistorySnapshot"))
        assertTrue(session.contains("activeTaskId == taskId"))
        assertTrue(editor.contains("previewPhase == TaskHistoryPreviewPhase.RETURNING"))
        assertFalse(activity.contains("rememberSaveableStateHolder"))
        assertFalse(tasks.contains("SaveableStateHolder"))
        assertFalse(editor.contains("DiffUtil"))
        assertFalse(preview.contains("Saver<"))
        assertFalse(preview.contains("detailListIndex"))
    }

    @Test
    fun scheduleAndTimeRowsUseDistinctIcons() {
        val editor = source("ui/tasks/TaskEditorPage.kt")
        val atRow = editor
            .substringAfter("// ── At ──")
            .substringBefore("// ── Custom cron passthrough ──")
        val scheduleRow = editor
            .substringAfter("// ── Armed switch ──")
            .substringBefore("if (showWeekdayDialog)")

        assertTrue(atRow.contains("Icons.Default.Schedule"))
        assertFalse(atRow.contains("Icons.Default.Timer"))
        assertTrue(scheduleRow.contains("Icons.Default.Timer"))
        assertFalse(scheduleRow.contains("Icons.Default.Schedule"))
    }

    @Test
    fun thePromptIsEditedInADialogOpenedFromAPreviewRow() {
        val editor = source("ui/tasks/TaskEditorPage.kt")
        val dialog = source("ui/tasks/TaskPromptDialog.kt")

        assertTrue(editor.contains("TaskPromptRow(prompt) { showPromptDialog = true }"))
        assertTrue(editor.contains("TaskPromptDialog("))
        assertFalse(editor.contains("onValueChange = editorSession::updatePrompt"))
        assertTrue(dialog.contains("Modifier.clickable(onClick = onClick)"))
        assertTrue(dialog.contains("minLines = 4,\n                maxLines = 12,"))
        assertTrue(dialog.contains("onClick = { onSave(draft) }"))
    }

    @Test
    fun loadingHistoryShowsAProgressRowAndNeverCorrectsTheScrollAfterwards() {
        val editor = source("ui/tasks/TaskEditorPage.kt")

        assertTrue(editor.contains("if (!executionsLoaded) {"))
        assertTrue(editor.contains("CircularProgressIndicator("))
        assertTrue(
            editor.contains(
                "initialFirstVisibleItemIndex = " +
                    "if (retainedExecutionSnapshot != null) savedListIndex else 0",
            ),
        )
        assertFalse(editor.contains("shouldRestoreTaskDetailScroll"))
        assertFalse(editor.contains("scrollRestored"))
        assertFalse(editor.contains("scrollToItem("))
    }

    @Test
    fun onlyAUserDragDismissesTheKeyboardSoATappedFieldKeepsItsCursor() {
        val detail = source("ui/tasks/TaskEditorPage.kt")
            .substringAfter("internal fun TaskDetailPage(")
            .substringBefore("internal fun formatDateTime(")

        // Focusing a field makes the list scroll itself into view; that must not clear focus.
        assertFalse(detail.contains("LaunchedEffect(listState.isScrollInProgress)"))
        assertTrue(detail.contains("listState.interactionSource.interactions.collect"))
        assertTrue(detail.contains("if (interaction is DragInteraction.Start) focusManager.clearFocus()"))
    }

    @Test
    fun taskCarriesASavedSystemPromptByReferenceNotByCopiedText() {
        val editor = source("ui/tasks/TaskEditorPage.kt")
        val components = source("ui/tasks/TaskEditorSupportingComponents.kt")
        val session = source("ui/tasks/TaskEditorSessionViewModel.kt")
        val manager = source("automation/TaskManager.kt")

        assertTrue(editor.contains("viewModel.settings.systemPrompts.collectAsState()"))
        assertTrue(
            editor.contains("TaskSystemPromptRow(editorSession.systemPromptId, systemPrompts, activeSystemPromptId)"),
        )
        // The task picker is Chat's picker, not a lookalike.
        assertTrue(editor.contains("import com.newoether.agora.ui.components.SystemPromptPickerDialog"))
        assertTrue(editor.contains("settings = viewModel.settings,"))
        assertFalse(components.contains("fun SystemPromptPickerDialog("))
        assertTrue(components.contains("Icon(Icons.Default.Psychology, null"))
        assertTrue(session.contains("fun updateSystemPromptId(value: String?)"))
        assertTrue(session.contains("systemPromptId = systemPromptId,"))
        // The run must resolve the prompt through the conversation, so later prompt edits apply.
        assertTrue(manager.contains("systemPromptId = task.systemPromptId,"))
        assertTrue(
            manager.contains(
                "systemPromptOverride = if (task.systemPromptId != null) null else task.systemPrompt ?: \"\"",
            ),
        )
    }

    private fun source(relativePath: String): String =
        File(mainSourceRoot(), "com/newoether/agora/$relativePath")
            .readText()
            .replace("\r\n", "\n")

    private fun mainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            val candidate = File(directory, "app/src/main/java")
            if (candidate.isDirectory) return candidate
            directory = directory.parentFile ?: error("Unable to locate app/src/main/java")
        }
    }
}
