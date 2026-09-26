package com.newoether.agora.ui.tasks

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.data.modelAliasDisplayName
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay
import com.newoether.agora.automation.ScheduleType
import com.newoether.agora.automation.TaskSchedule
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ModelId
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.settings.SettingsItem
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.TimeZone

internal fun daysInYearlyMonth(month: Int): Int = when (month) {
    2 -> 29 // A yearly cron may intentionally target leap day.
    4, 6, 9, 11 -> 30
    else -> 31
}

/** A yearless picker for YEARLY schedules: month plus day are the entire persisted date. */
@Composable
internal fun TaskMonthDayPickerDialog(
    schedule: TaskSchedule,
    onConfirm: (TaskSchedule) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val monthNames = remember(locale) { DateFormatSymbols(locale).months.take(12) }
    var selectedMonth by rememberSaveable { mutableIntStateOf(schedule.month.coerceIn(1, 12)) }
    var selectedDay by rememberSaveable {
        mutableIntStateOf(schedule.dayOfMonth.coerceIn(1, daysInYearlyMonth(selectedMonth)))
    }
    var showMonthMenu by remember { mutableStateOf(false) }
    val allowSelectionAnimation = LocalAgoraMotionPolicy.current.allowSpatialTransitions

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Text(
                stringResource(R.string.task_select_month_day),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    TextButton(onClick = { showMonthMenu = true }) {
                        Text(
                            monthNames[selectedMonth - 1],
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = showMonthMenu,
                        onDismissRequest = { showMonthMenu = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        monthNames.forEachIndexed { index, monthName ->
                            val month = index + 1
                            DropdownMenuItem(
                                text = { Text(monthName) },
                                leadingIcon = {
                                    if (month == selectedMonth) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                                onClick = {
                                    selectedMonth = month
                                    selectedDay = selectedDay.coerceAtMost(daysInYearlyMonth(month))
                                    showMonthMenu = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                (1..daysInYearlyMonth(selectedMonth)).chunked(7).forEach { rowDays ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        repeat(7) { column ->
                            val day = rowDays.getOrNull(column)
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (day != null) {
                                    val selected = day == selectedDay
                                    val containerColor by animateColorAsState(
                                        targetValue = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainer
                                        },
                                        animationSpec = if (allowSelectionAnimation) {
                                            tween(durationMillis = 180)
                                        } else {
                                            snap()
                                        },
                                        label = "monthDayContainerColor",
                                    )
                                    val contentColor by animateColorAsState(
                                        targetValue = if (selected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        animationSpec = if (allowSelectionAnimation) {
                                            tween(durationMillis = 180)
                                        } else {
                                            snap()
                                        },
                                        label = "monthDayContentColor",
                                    )
                                    Surface(
                                        onClick = { selectedDay = day },
                                        modifier = Modifier.size(40.dp),
                                        shape = CircleShape,
                                        color = containerColor,
                                        contentColor = contentColor,
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(day.toString())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        schedule.copy(
                            month = selectedMonth,
                            dayOfMonth = selectedDay,
                            onceAtMillis = 0L,
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Full date picker for ONCE. Material3 owns calendar/input mode and its transition. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskDatePickerDialog(
    schedule: TaskSchedule,
    onConfirm: (TaskSchedule) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialLocalDate = remember(schedule) {
        Calendar.getInstance().apply {
            if (schedule.type == ScheduleType.ONCE && schedule.onceAtMillis > 0L) {
                timeInMillis = schedule.onceAtMillis
            } else {
                set(Calendar.MONTH, schedule.month - 1)
                set(Calendar.DAY_OF_MONTH, schedule.dayOfMonth)
            }
        }
    }
    val initialUtcMillis = remember(initialLocalDate) {
        utcDateMillis(
            initialLocalDate.get(Calendar.YEAR),
            initialLocalDate.get(Calendar.MONTH),
            initialLocalDate.get(Calendar.DAY_OF_MONTH),
        )
    }
    val todayUtcMillis = remember {
        val today = Calendar.getInstance()
        utcDateMillis(
            today.get(Calendar.YEAR),
            today.get(Calendar.MONTH),
            today.get(Calendar.DAY_OF_MONTH),
        )
    }
    val selectableDates = remember(schedule.type, todayUtcMillis) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                schedule.type != ScheduleType.ONCE || utcTimeMillis >= todayUtcMillis
        }
    }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialUtcMillis,
        selectableDates = selectableDates,
    )
    val pickerColors = DatePickerDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    val dateFormatter = remember { DatePickerDefaults.dateFormatter() }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.height(568.dp),
        confirmButton = {
            TextButton(
                onClick = {
                    val selected = pickerState.selectedDateMillis ?: return@TextButton
                    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        timeInMillis = selected
                    }
                    val year = utc.get(Calendar.YEAR)
                    val month = utc.get(Calendar.MONTH) + 1
                    val day = utc.get(Calendar.DAY_OF_MONTH)
                    val next = schedule.copy(dayOfMonth = day, month = month)
                    onConfirm(
                        if (schedule.type == ScheduleType.ONCE) next.withOnceAt(year, month, day)
                        else next
                    )
                },
                enabled = pickerState.selectedDateMillis != null,
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        shape = RoundedCornerShape(28.dp),
        colors = pickerColors,
    ) {
        DatePicker(
            state = pickerState,
            dateFormatter = dateFormatter,
            colors = pickerColors,
            title = {
                ProvideTextStyle(
                    MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                ) {
                    DatePickerDefaults.DatePickerTitle(
                        displayMode = pickerState.displayMode,
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 20.dp),
                        contentColor = pickerColors.titleContentColor,
                    )
                }
            },
            headline = {
                DatePickerDefaults.DatePickerHeadline(
                    selectedDateMillis = pickerState.selectedDateMillis,
                    displayMode = pickerState.displayMode,
                    dateFormatter = dateFormatter,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                    contentColor = pickerColors.headlineContentColor,
                )
            },
            showModeToggle = true,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskTimePickerDialog(
    schedule: TaskSchedule,
    use24HourFormat: Boolean,
    onConfirm: (TaskSchedule) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = schedule.hour,
        initialMinute = schedule.minute,
        is24Hour = use24HourFormat,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Text(stringResource(R.string.task_at), fontWeight = FontWeight.Bold)
        },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                TimePicker(state = pickerState)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(schedule.withTime(pickerState.hour, pickerState.minute))
                },
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun utcDateMillis(year: Int, zeroBasedMonth: Int, day: Int): Long =
    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(year, zeroBasedMonth, day)
    }.timeInMillis

@Composable
internal fun WeekdayDialog(
    selected: Set<Int>,
    onConfirm: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val names = weekdayNames()
    var working by remember { mutableStateOf(selected) }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_days_of_week), fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(7) { dow ->
                    val checked = dow in working
                    SettingsItem(
                        modifier = Modifier.clickable {
                            working = if (checked) working - dow else working + dow
                        },
                        headlineContent = {
                            Text(names[dow], fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal)
                        },
                        leadingContent = {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { working = if (checked) working - dow else working + dow },
                            )
                        },
                    )
                }
            }
        },
        // Multi-select needs an explicit commit — unlike the single-choice pickers, one tap here
        // is not the final answer.
        confirmButton = {
            TextButton(enabled = working.isNotEmpty(), onClick = { onConfirm(working) }) {
                Text(stringResource(R.string.provider_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun DayOfMonthDialog(
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_day_of_month), fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(31) { index ->
                    val day = index + 1
                    ChoiceRow(
                        label = stringResource(R.string.task_day_ordinal, day),
                        sub = null,
                        selected = day == selected,
                        onClick = { onSelect(day) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.provider_close)) } },
    )
}

@Composable
internal fun ExecutionRow(
    execution: com.newoether.agora.automation.TaskManager.ExecutionSummary,
    customProviders: List<CustomProviderConfig>,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    menuEnabled: Boolean,
    onDelete: () -> Unit,
) {
    var menuOpen by remember(execution.conversation.id) { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        val statusText = when (execution.status) {
            MessageStatus.SUCCESS -> stringResource(R.string.task_status_success)
            MessageStatus.ERROR -> stringResource(R.string.task_status_failed)
            MessageStatus.SENDING, MessageStatus.THINKING,
            MessageStatus.TOOL_CALLING, MessageStatus.TRANSCRIBING -> stringResource(R.string.task_running)
            MessageStatus.STOPPED -> stringResource(R.string.task_status_stopped)
            else -> stringResource(R.string.task_status_unknown)
        }
        val formattedTime = remember(execution.timestamp) {
            if (execution.timestamp == 0L) "" else formatDateTime(execution.timestamp)
        }
        val displayTitle = replaceCustomProviderIdsForDisplay(
            execution.conversation.title,
            customProviders,
        )
        val displayPreview = replaceCustomProviderIdsForDisplay(execution.preview, customProviders)
        SettingsItem(
            headlineContent = {
                Text(
                    text = displayTitle.ifBlank {
                        displayPreview.ifBlank { statusText }
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Column {
                    Text(
                        text = listOf(statusText, formattedTime)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = when (execution.status) {
                            MessageStatus.ERROR -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            MessageStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (displayPreview.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = displayPreview,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            leadingContent = {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            trailingContent = {
                Box {
                    IconButton(
                        enabled = menuEnabled,
                        onClick = { menuOpen = true },
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.options),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        shape = RoundedCornerShape(12.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 16.dp,
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            },
        )
    }
}

@Composable
internal fun ModelPickerDialog(
    enabledModels: List<String>,
    modelAliases: Map<String, String>,
    customProviders: List<CustomProviderConfig>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_model), fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    ChoiceRow(
                        label = stringResource(R.string.task_model_default),
                        sub = null,
                        selected = selected == null,
                        onClick = { onSelect(null) },
                    )
                }
                items(enabledModels, key = { it }) { model ->
                    val parsed = ModelId.parse(model)
                    ChoiceRow(
                        label = modelAliasDisplayName(model, modelAliases, customProviders),
                        sub = providerDisplayName(parsed.providerName, customProviders),
                        selected = selected == model,
                        onClick = { onSelect(model) },
                    )
                }
            }
        },
        // Close, not Cancel: a tap applies immediately, so there is nothing to cancel.
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.provider_close)) } },
    )
}

/** The app's standard selection row (Settings model/prompt dialogs): a [SettingsItem] whose
 *  leading slot is the radio, with the selected label in bold. Shared by both Task pickers so
 *  they are indistinguishable from every other picker in the app. */
@Composable
private fun ChoiceRow(label: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    SettingsItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        },
        supportingContent = sub?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        },
        leadingContent = { RadioButton(selected = selected, onClick = onClick) },
    )
}

/** Details-group row naming the saved system prompt a task runs with. */
@Composable
internal fun TaskSystemPromptRow(
    selectedId: String?,
    prompts: List<SystemPromptEntry>,
    onClick: () -> Unit,
) {
    val selected = prompts.firstOrNull { it.id == selectedId }
    SettingsItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(stringResource(R.string.task_system_prompt)) },
        supportingContent = {
            Text(selected?.title ?: stringResource(R.string.task_system_prompt_default))
        },
        leadingContent = {
            Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
        },
    )
}

/** Picker for the saved system prompt a task runs with. The default entry keeps whichever prompt
 *  the app is currently set to, resolved at run time like any other conversation. */
@Composable
internal fun SystemPromptPickerDialog(
    prompts: List<SystemPromptEntry>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_system_prompt), fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    ChoiceRow(
                        label = stringResource(R.string.task_system_prompt_default),
                        sub = null,
                        selected = selected == null,
                        onClick = { onSelect(null) },
                    )
                }
                items(prompts, key = { it.id }) { prompt ->
                    ChoiceRow(
                        label = prompt.title,
                        sub = null,
                        selected = selected == prompt.id,
                        onClick = { onSelect(prompt.id) },
                    )
                }
            }
        },
        // Close, not Cancel: a tap applies immediately, so there is nothing to cancel.
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.provider_close)) } },
    )
}
