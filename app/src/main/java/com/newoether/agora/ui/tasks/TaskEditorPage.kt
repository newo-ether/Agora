package com.newoether.agora.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.automation.CronExpression
import com.newoether.agora.automation.ScheduleType
import com.newoether.agora.automation.TaskSchedule
import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.data.modelDisplayName
import java.util.Calendar
import com.newoether.agora.ui.chat.ChatDeleteConfirmDialog
import com.newoether.agora.ui.chat.ChatDeleteDialogPhase
import com.newoether.agora.ui.components.SystemPromptPickerDialog
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.settings.AnimatedActionFab
import com.newoether.agora.ui.settings.CollapsingSettingsLazyScaffold
import com.newoether.agora.ui.settings.SettingsGroup
import com.newoether.agora.ui.settings.SettingsItem
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale

/**
 * The schedule editor mode is explicit UI state. In particular, CUSTOM must not be inferred from
 * whether the current text parses: a partially typed cron is expected to be invalid for a moment,
 * but that must not make the editor jump back to Daily.
 */
internal enum class ScheduleEditorMode {
    ONCE,
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
    CUSTOM,
}

internal fun initialScheduleEditorMode(cronExpr: String, runAt: Long?): ScheduleEditorMode {
    val parsed = TaskSchedule.parse(cronExpr, runAt)
    return parsed?.type?.toEditorMode()
        ?: if (cronExpr.isNotBlank()) ScheduleEditorMode.CUSTOM else ScheduleEditorMode.DAILY
}

internal fun isScheduleDraftValid(mode: ScheduleEditorMode, cronExpr: String): Boolean =
    if (mode == ScheduleEditorMode.CUSTOM) {
        cronExpr.isNotBlank() && CronExpression.isValid(cronExpr)
    } else {
        cronExpr.isBlank() || CronExpression.isValid(cronExpr)
    }

internal fun taskExecutionHistoryForPresentation(
    previewPhase: TaskHistoryPreviewPhase,
    retained: List<com.newoether.agora.automation.TaskManager.ExecutionSummary>?,
    live: List<com.newoether.agora.automation.TaskManager.ExecutionSummary>?,
): List<com.newoether.agora.automation.TaskManager.ExecutionSummary>? =
    if (previewPhase == TaskHistoryPreviewPhase.RETURNING && retained != null) {
        retained
    } else {
        live ?: retained
    }

private fun ScheduleType.toEditorMode(): ScheduleEditorMode = when (this) {
    ScheduleType.ONCE -> ScheduleEditorMode.ONCE
    ScheduleType.DAILY -> ScheduleEditorMode.DAILY
    ScheduleType.WEEKLY -> ScheduleEditorMode.WEEKLY
    ScheduleType.MONTHLY -> ScheduleEditorMode.MONTHLY
    ScheduleType.YEARLY -> ScheduleEditorMode.YEARLY
}

private fun ScheduleEditorMode.toScheduleType(): ScheduleType? = when (this) {
    ScheduleEditorMode.ONCE -> ScheduleType.ONCE
    ScheduleEditorMode.DAILY -> ScheduleType.DAILY
    ScheduleEditorMode.WEEKLY -> ScheduleType.WEEKLY
    ScheduleEditorMode.MONTHLY -> ScheduleType.MONTHLY
    ScheduleEditorMode.YEARLY -> ScheduleType.YEARLY
    ScheduleEditorMode.CUSTOM -> null
}

/**
 * Preserve a literal time when leaving a custom expression. The rest of a custom cron may be too
 * rich for the structured editor, but its `minute hour` prefix is still useful and lossless.
 */
private fun scheduleSeedFromCron(cronExpr: String): TaskSchedule {
    val fields = cronExpr.trim().split(Regex("\\s+"))
    val minute = fields.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..59 }
    val hour = fields.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..23 }
    val default = TaskSchedule.default()
    return default.copy(
        hour = hour ?: default.hour,
        minute = minute ?: default.minute,
    )
}

// ── Detail ──────────────────────────────────────────────────────────────────

/**
 * Task editor, structured as three Settings-style groups — Details / Schedule / Execution log —
 * so a task reads top-to-bottom as "what it says, when it fires, what it did". Everything a run
 * depends on lives above the log; nothing is hidden behind a dialog except the model list.
 */
@Composable
internal fun TaskDetailPage(
    viewModel: ChatViewModel,
    task: TaskEntity,
    editorSession: TaskEditorSessionViewModel,
    backHandlingEnabled: Boolean,
    onBack: () -> Unit,
    onOpenConversation: (conversationId: String) -> Unit,
) {
    val running by viewModel.runningTaskIds.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val modelProviderNames by viewModel.settings.modelProviderNames.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val systemPrompts by viewModel.settings.systemPrompts.collectAsState()
    val activeSystemPromptId by viewModel.settings.activeSystemPromptId.collectAsState()

    val name = editorSession.name
    val prompt = editorSession.prompt
    val modelId = editorSession.modelId
    val cronExpr = editorSession.cronExpr
    val runAt = editorSession.runAt
    val scheduleEditorMode = editorSession.scheduleEditorMode
    val enabled = editorSession.enabled
    val isNew = editorSession.isNew
    var showModelPicker by remember { mutableStateOf(false) }
    var showSystemPromptPicker by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var executionToDelete by remember(task.id) { mutableStateOf<com.newoether.agora.automation.TaskManager.ExecutionSummary?>(null) }
    val executionDeleteId = executionToDelete?.conversation?.id
    var executionDeletePhase by remember(executionDeleteId) {
        mutableStateOf(ChatDeleteDialogPhase.CONFIRM)
    }
    LaunchedEffect(executionDeleteId, executionDeletePhase) {
        if (executionDeleteId == null || executionDeletePhase != ChatDeleteDialogPhase.PENDING) {
            return@LaunchedEffect
        }
        withFrameNanos { }
        val accepted = viewModel.deleteConversation(executionDeleteId) { deleted ->
            if (executionToDelete?.conversation?.id == executionDeleteId) {
                if (deleted) executionToDelete = null
                else executionDeletePhase = ChatDeleteDialogPhase.FAILED
            }
        }
        if (!accepted) executionDeletePhase = ChatDeleteDialogPhase.FAILED
    }
    val savedListIndex = remember(task.id) { editorSession.detailListIndex }
    val savedListOffset = remember(task.id) { editorSession.detailListOffset }
    val previewPhase = editorSession.historyPreview.phase
    val retainedExecutionSnapshot = editorSession.executionHistoryFor(task.id)
    val executionHistoryFlow = remember(task.id, viewModel) {
        viewModel.executionSummariesForTask(task.id)
    }
    val liveExecutionSnapshot by executionHistoryFlow.collectAsState(initial = null)
    val executionSnapshot = taskExecutionHistoryForPresentation(
        previewPhase = previewPhase,
        retained = retainedExecutionSnapshot,
        live = liveExecutionSnapshot,
    )
    val executions = executionSnapshot.orEmpty()
    val executionsLoaded = executionSnapshot != null
    // History arrives after the first frame, so a saved position the list cannot honour yet gets
    // clamped and then corrected once the rows exist, which is the visible upward jump. A fresh
    // entry therefore starts at the top; only a retained snapshot (returning from a preview, where
    // the full list composes immediately) restores the saved position.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (retainedExecutionSnapshot != null) savedListIndex else 0,
        initialFirstVisibleItemScrollOffset =
            if (retainedExecutionSnapshot != null) savedListOffset else 0,
    )
    val focusManager = LocalFocusManager.current

    val isRunning = task.id in running

    val cronValid = isScheduleDraftValid(scheduleEditorMode, cronExpr)
    val isComplete = name.isNotBlank() && prompt.isNotBlank() && cronValid

    fun current() = checkNotNull(editorSession.current(task))

    BackHandler(enabled = backHandlingEnabled) { onBack() }

    LaunchedEffect(task.id, previewPhase, liveExecutionSnapshot) {
        val latest = liveExecutionSnapshot ?: return@LaunchedEffect
        if (previewPhase != TaskHistoryPreviewPhase.RETURNING) {
            editorSession.retainExecutionHistory(task.id, latest)
        }
    }

    LaunchedEffect(task.id, listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (index, offset) -> editorSession.updateScroll(index, offset) }
    }

    // Only a user drag dismisses the keyboard. The list also scrolls on its own to bring a newly
    // focused field into view, and treating that scroll as a dismissal dropped focus (and the
    // cursor) the moment the prompt field was tapped.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) focusManager.clearFocus()
        }
    }

    CollapsingSettingsLazyScaffold(
        title = name.ifBlank { stringResource(if (isNew) R.string.task_new else R.string.task_edit) },
        onBack = onBack,
        modifier = Modifier.clearFocusOnTap(),
        listState = listState,
        actions = {
            IconButton(
                enabled = isComplete,
                onClick = {
                    viewModel.saveTask(current())
                    onBack()
                },
            ) {
                Icon(Icons.Default.Save, contentDescription = stringResource(R.string.task_save))
            }
        },
        floatingActionButton = {
            AnimatedActionFab(
                label = stringResource(if (isRunning) R.string.task_running else R.string.task_run_now),
                icon = Icons.Default.PlayArrow,
                onClick = {
                    viewModel.runTaskNow(
                        current(),
                        preservePersistedEnabled = false,
                    )
                },
                enabled = isComplete && !isRunning,
                loading = isRunning,
            )
        },
    ) {
        item {
            SettingsGroup(
                title = stringResource(R.string.task_section_details),
                items = listOf(
                    {
                        TaskLabeledField(
                            label = stringResource(R.string.task_name),
                            icon = Icons.Default.Label,
                            value = name,
                            onValueChange = editorSession::updateName,
                            placeholder = stringResource(R.string.task_name_hint),
                            singleLine = true,
                        )
                    },
                    {
                        TaskPromptRow(prompt) { showPromptDialog = true }
                    },
                    {
                        SettingsItem(
                            modifier = Modifier.clickable { showModelPicker = true },
                            headlineContent = { Text(stringResource(R.string.task_model)) },
                            supportingContent = {
                                Text(
                                    modelId?.let { modelDisplayName(it, modelAliases, customProviders, modelProviderNames[it] != false) }
                                        ?: stringResource(R.string.task_model_default)
                                )
                            },
                            leadingContent = {
                                Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.primary)
                            },
                        )
                    },
                    {
                        TaskSystemPromptRow(editorSession.systemPromptId, systemPrompts, activeSystemPromptId) {
                            showSystemPromptPicker = true
                        }
                    },
                ),
            )
            Spacer(Modifier.height(24.dp))
        }

        item {
            ScheduleGroup(
                cronExpr = cronExpr,
                runAt = runAt,
                onScheduleChange = editorSession::updateSchedule,
                editorMode = scheduleEditorMode,
                onEditorModeChange = editorSession::updateScheduleEditorMode,
                enabled = enabled,
                onEnabledChange = editorSession::updateEnabled,
            )
            Spacer(Modifier.height(24.dp))
        }

        item {
            Text(
                stringResource(R.string.task_execution_log),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        if (!executionsLoaded) {
            // The history section owns the wait, so the rest of the page stays put while it loads.
            item(key = "task_detail_history_loading") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                        )
                    }
                }
            }
        } else if (executions.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    SettingsItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.task_no_executions),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(R.string.task_no_executions_desc),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        },
                        modifier = Modifier.heightIn(min = 64.dp),
                    )
                }
            }
        } else if (executions.isNotEmpty()) {
            itemsIndexed(executions, key = { _, e -> e.conversation.id }) { index, execution ->
                ExecutionRow(
                    execution = execution,
                    customProviders = customProviders,
                    shape = stackedShape(index, executions.size),
                    onClick = {
                        editorSession.updateScroll(
                            listState.firstVisibleItemIndex,
                            listState.firstVisibleItemScrollOffset,
                        )
                        editorSession.retainExecutionHistory(task.id, executions)
                        onOpenConversation(execution.conversation.id)
                    },
                    menuEnabled = !isRunning,
                    onDelete = { executionToDelete = execution },
                )
                if (index < executions.lastIndex) Spacer(Modifier.height(STACK_GAP))
            }
        }
        item(key = "task_detail_fab_spacing") {
            Spacer(Modifier.height(80.dp))
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            enabledModels = enabledModels.toList(),
            modelAliases = modelAliases,
            customProviders = customProviders,
            selected = modelId,
            onSelect = { selectedModelId ->
                editorSession.updateModelId(selectedModelId)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }
    if (showPromptDialog) {
        TaskPromptDialog(
            initial = prompt,
            onSave = {
                editorSession.updatePrompt(it)
                showPromptDialog = false
            },
            onDismiss = { showPromptDialog = false },
        )
    }

    if (showSystemPromptPicker) {
        SystemPromptPickerDialog(
            settings = viewModel.settings,
            initialSelectedId = editorSession.systemPromptId,
            selectionKey = editorSession.systemPromptId,
            onSave = {
                editorSession.updateSystemPromptId(it)
                showSystemPromptPicker = false
            },
            onDismiss = { showSystemPromptPicker = false },
        )
    }
    executionToDelete?.let {
        ChatDeleteConfirmDialog(
            phase = executionDeletePhase,
            onConfirm = {
                if (executionDeletePhase != ChatDeleteDialogPhase.PENDING) {
                    executionDeletePhase = ChatDeleteDialogPhase.PENDING
                }
            },
            onDismiss = {
                if (executionDeletePhase != ChatDeleteDialogPhase.PENDING) executionToDelete = null
            },
        )
    }
}

internal fun formatDateTime(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT
    ).format(java.util.Date(millis))

private fun formatTimeOfDay(hour: Int, minute: Int): String =
    String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

/** One-line recurrence summary for a task card ("Daily", "Weekly", a raw cron, …). */
@Composable
internal fun taskRepeatSummary(task: TaskEntity): String {
    val schedule = TaskSchedule.parse(task.cronExpr, task.runAt)
    return if (schedule != null) repeatLabel(schedule.type)
    else task.cronExpr.ifBlank { stringResource(R.string.task_schedule_not_set) }
}

@Composable
private fun repeatLabel(type: ScheduleType): String = stringResource(
    when (type) {
        ScheduleType.ONCE -> R.string.task_repeat_once
        ScheduleType.DAILY -> R.string.task_repeat_daily
        ScheduleType.WEEKLY -> R.string.task_repeat_weekly
        ScheduleType.MONTHLY -> R.string.task_repeat_monthly
        ScheduleType.YEARLY -> R.string.task_repeat_yearly
    }
)

@Composable
private fun repeatLabel(mode: ScheduleEditorMode): String =
    if (mode == ScheduleEditorMode.CUSTOM) {
        stringResource(R.string.task_schedule_custom)
    } else {
        repeatLabel(checkNotNull(mode.toScheduleType()))
    }

/** Short weekday names in the user's locale, indexed 0=Sunday..6=Saturday to match cron. */
@Composable
internal fun weekdayNames(): List<String> {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        val cal = Calendar.getInstance()
        val fmt = java.text.SimpleDateFormat("EEE", locale)
        (0..6).map { dow ->
            cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY + dow)
            fmt.format(cal.time)
        }
    }
}

/**
 * Schedule group: WHAT recurrence (Repeat), WHICH date within it (On), and WHAT time (At) — plus
 * whether the whole thing is armed (the switch). "Manual only" is not a repeat option; it is the
 * switch being off, so no two controls express the same state.
 *
 * The On row's editor depends on the repeat type, because "which date" means something different
 * for each: daily has no On row at all, weekly picks weekdays, monthly picks a day number, yearly
 * and once pick a calendar date. Once additionally stores an absolute epoch instead of a cron —
 * a 5-field cron has no year, so "once on March 3rd" would silently repeat every year.
 *
 * A cron this model cannot express (a legacy hourly preset, a hand-written step expression) is
 * left untouched and shown as a custom expression until the user picks a repeat type.
 */
@Composable
private fun ScheduleGroup(
    cronExpr: String,
    runAt: Long?,
    onScheduleChange: (cron: String, runAt: Long?) -> Unit,
    editorMode: ScheduleEditorMode,
    onEditorModeChange: (ScheduleEditorMode) -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val parsedSchedule = remember(cronExpr, runAt) { TaskSchedule.parse(cronExpr, runAt) }
    val isCustomCron = editorMode == ScheduleEditorMode.CUSTOM
    val schedule = parsedSchedule ?: remember(cronExpr) { scheduleSeedFromCron(cronExpr) }

    var showRepeatMenu by remember { mutableStateOf(false) }
    var showWeekdayDialog by remember { mutableStateOf(false) }
    var showDayOfMonthDialog by remember { mutableStateOf(false) }
    var showMonthDayDialog by remember { mutableStateOf(false) }
    var showDateDialog by remember { mutableStateOf(false) }
    var showTimeDialog by remember { mutableStateOf(false) }

    fun apply(next: TaskSchedule) = onScheduleChange(next.toCron(), next.toRunAt())
    fun selectMode(nextMode: ScheduleEditorMode) {
        onEditorModeChange(nextMode)
        if (nextMode == ScheduleEditorMode.CUSTOM) {
            // ONCE has no cron to preserve. Seed Custom with the same time-of-day as a daily cron.
            val seedCron = cronExpr.ifBlank {
                schedule.copy(type = ScheduleType.DAILY, onceAtMillis = 0L).toCron()
            }
            onScheduleChange(seedCron, null)
        } else {
            apply(schedule.switchedTo(checkNotNull(nextMode.toScheduleType())))
        }
    }

    val armable = cronExpr.isNotBlank() || (runAt != null && runAt > 0L)
    val scheduleDraftValid = isScheduleDraftValid(editorMode, cronExpr)
    val oncePast = schedule.type == ScheduleType.ONCE &&
        (runAt ?: 0L) in 1 until System.currentTimeMillis()
    val canToggleSchedule = armable && scheduleDraftValid && (!oncePast || enabled)

    SettingsGroup(
        title = stringResource(R.string.task_schedule),
        items = buildList {
            // ── Repeat ──
            add {
                Box {
                    SettingsItem(
                        modifier = Modifier.clickable { showRepeatMenu = true },
                        headlineContent = { Text(stringResource(R.string.task_repeat)) },
                        supportingContent = {
                            Text(repeatLabel(editorMode))
                        },
                        leadingContent = {
                            Icon(Icons.Default.Repeat, null, tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                    DropdownMenu(
                        expanded = showRepeatMenu,
                        onDismissRequest = { showRepeatMenu = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        ScheduleEditorMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(repeatLabel(mode)) },
                                leadingIcon = {
                                    if (editorMode == mode) {
                                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onClick = {
                                    showRepeatMenu = false
                                    selectMode(mode)
                                },
                            )
                        }
                    }
                }
            }

            // ── On (absent for DAILY, which has no date to choose) ──
            if (!isCustomCron && schedule.type != ScheduleType.DAILY) {
                add {
                    val names = weekdayNames()
                    val onValue = when (schedule.type) {
                        ScheduleType.WEEKLY ->
                            if (schedule.daysOfWeek.isEmpty()) stringResource(R.string.task_schedule_not_set)
                            else schedule.daysOfWeek.sorted().joinToString(", ") { names[it] }
                        ScheduleType.MONTHLY -> stringResource(R.string.task_day_ordinal, schedule.dayOfMonth)
                        ScheduleType.YEARLY, ScheduleType.ONCE -> schedule.formatOnDate()
                        ScheduleType.DAILY -> ""
                    }
                    SettingsItem(
                        modifier = Modifier.clickable {
                            when (schedule.type) {
                                ScheduleType.WEEKLY -> showWeekdayDialog = true
                                ScheduleType.MONTHLY -> showDayOfMonthDialog = true
                                ScheduleType.YEARLY -> showMonthDayDialog = true
                                ScheduleType.ONCE -> showDateDialog = true
                                ScheduleType.DAILY -> Unit
                            }
                        },
                        headlineContent = {
                            Text(
                                when (schedule.type) {
                                    ScheduleType.WEEKLY -> stringResource(R.string.task_days_of_week)
                                    ScheduleType.MONTHLY -> stringResource(R.string.task_day_of_month)
                                    else -> stringResource(R.string.task_on)
                                }
                            )
                        },
                        supportingContent = { Text(onValue) },
                        leadingContent = {
                            Icon(Icons.Default.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            }

            // ── At ──
            if (!isCustomCron) {
                add {
                    SettingsItem(
                        modifier = Modifier.clickable { showTimeDialog = true },
                        headlineContent = { Text(stringResource(R.string.task_at)) },
                        supportingContent = { Text(formatTimeOfDay(schedule.hour, schedule.minute)) },
                        leadingContent = {
                            Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            }

            // ── Custom cron passthrough ──
            if (isCustomCron) {
                add {
                    TaskLabeledField(
                        label = stringResource(R.string.task_schedule_custom),
                        icon = Icons.Default.Code,
                        value = cronExpr,
                        onValueChange = { onScheduleChange(it, null) },
                        placeholder = stringResource(R.string.task_cron_hint),
                        singleLine = true,
                        isError = cronExpr.isBlank() || !CronExpression.isValid(cronExpr),
                        supporting = if (cronExpr.isBlank() || !CronExpression.isValid(cronExpr)) {
                            stringResource(R.string.task_cron_invalid)
                        } else {
                            null
                        },
                        supportingIsError = true,
                    )
                }
            }

            // ── Armed switch ──
            add {
                val nextRun = remember(cronExpr, runAt, enabled) {
                    when {
                        !enabled -> null
                        runAt != null && runAt > System.currentTimeMillis() -> runAt
                        cronExpr.isNotBlank() ->
                            CronExpression.parse(cronExpr)?.next(System.currentTimeMillis())
                        else -> null
                    }
                }
                SettingsItem(
                    modifier = Modifier.clickable(enabled = canToggleSchedule) {
                        onEnabledChange(!enabled)
                    },
                    headlineContent = {
                        Text(
                            stringResource(R.string.task_enabled),
                            color = if (armable) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    },
                    supportingContent = {
                        Text(
                            when {
                                !armable -> stringResource(R.string.task_enabled_needs_schedule)
                                oncePast -> stringResource(R.string.task_once_past)
                                nextRun != null -> stringResource(R.string.task_next_run, formatDateTime(nextRun))
                                else -> stringResource(R.string.task_enabled_desc)
                            },
                            color = if (oncePast) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.Timer, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Switch(
                            checked = enabled && armable,
                            enabled = canToggleSchedule,
                            onCheckedChange = onEnabledChange,
                        )
                    },
                )
            }
        },
    )

    if (showWeekdayDialog) {
        WeekdayDialog(
            selected = schedule.daysOfWeek,
            onConfirm = { days -> apply(schedule.copy(daysOfWeek = days)); showWeekdayDialog = false },
            onDismiss = { showWeekdayDialog = false },
        )
    }
    if (showDayOfMonthDialog) {
        DayOfMonthDialog(
            selected = schedule.dayOfMonth,
            onSelect = { day -> apply(schedule.copy(dayOfMonth = day)); showDayOfMonthDialog = false },
            onDismiss = { showDayOfMonthDialog = false },
        )
    }
    if (showMonthDayDialog) {
        TaskMonthDayPickerDialog(
            schedule = schedule,
            onConfirm = {
                apply(it)
                showMonthDayDialog = false
            },
            onDismiss = { showMonthDayDialog = false },
        )
    }
    if (showDateDialog) {
        TaskDatePickerDialog(
            schedule = schedule,
            onConfirm = {
                apply(it)
                showDateDialog = false
            },
            onDismiss = { showDateDialog = false },
        )
    }
    if (showTimeDialog) {
        TaskTimePickerDialog(
            schedule = schedule,
            use24HourFormat = android.text.format.DateFormat.is24HourFormat(context),
            onConfirm = {
                apply(it)
                showTimeDialog = false
            },
            onDismiss = { showTimeDialog = false },
        )
    }
}
