package com.newoether.agora.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.newoether.agora.R
import com.newoether.agora.data.modelApiDisplayName
import com.newoether.agora.ui.tasks.ModelPickerDialog
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsAutomationPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val toolsEnabled by viewModel.settings.automationToolsEnabled.collectAsState()
    val exactEnabled by viewModel.settings.exactExecutionEnabled.collectAsState()
    val wakeLockEnabled by viewModel.settings.automationWakeLockEnabled.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    val daemonEnabled by viewModel.settings.daemonEnabled.collectAsState()
    val alarmManager = remember {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }
    val powerManager = remember {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    val notificationManager = remember {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    var exactPermissionGranted by remember {
        mutableStateOf(canScheduleExactAlarms(alarmManager))
    }
    var batteryOptimizationIgnored by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    var awaitingExactPermission by rememberSaveable { mutableStateOf(false) }

    // Launcher must be created during composition (rememberLauncherForActivityResult is itself
    // @Composable) and only *launched* from click handlers — it cannot be constructed lazily
    // inside a plain function called from onClick/onCheckedChange.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.settings.setDaemonEnabled(granted)
    }

    DisposableEffect(lifecycleOwner, exactEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOptimizationIgnored = powerManager.isIgnoringBatteryOptimizations(
                    context.packageName,
                )
                val granted = canScheduleExactAlarms(alarmManager)
                exactPermissionGranted = granted
                if (awaitingExactPermission) {
                    viewModel.settings.setExactExecutionEnabled(granted)
                    awaitingExactPermission = false
                } else if (exactEnabled && !granted) {
                    // Special access can be revoked outside the app. Keep persisted intent
                    // honest; the scheduler independently fails safe to inexact alarms.
                    viewModel.settings.setExactExecutionEnabled(false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openBatteryOptimizationSettings() {
        val directRequest = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:${context.packageName}".toUri()
        }
        val canLaunchDirectRequest =
            !batteryOptimizationIgnored &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                context.packageManager.checkPermission(
                    Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    context.packageName,
                ) == PackageManager.PERMISSION_GRANTED &&
                directRequest.resolveActivity(context.packageManager) != null
        val directRequestLaunched = canLaunchDirectRequest && runCatching {
            context.startActivity(directRequest)
        }.isSuccess
        if (!directRequestLaunched) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            }
        }
    }

    fun setExactEnabled(enabled: Boolean) {
        if (!enabled) {
            awaitingExactPermission = false
            viewModel.settings.setExactExecutionEnabled(false)
            return
        }
        if (canScheduleExactAlarms(alarmManager)) {
            exactPermissionGranted = true
            viewModel.settings.setExactExecutionEnabled(true)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            awaitingExactPermission = true
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                )
            }.onFailure {
                awaitingExactPermission = false
                viewModel.settings.setExactExecutionEnabled(false)
            }
        }
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.settings.setDaemonEnabled(true)
        }
    }

    var showNotificationApps by rememberSaveable { mutableStateOf(false) }
    if (showNotificationApps) {
        SettingsNotificationAppsPage(viewModel, onBack = { showNotificationApps = false })
        return
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_automation),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("automation.md") }
    ) {
        SettingsGroupColumn(modifier = Modifier.fillMaxWidth()) {
            SettingsGroup(
                title = stringResource(R.string.settings_group_tools),
                items = listOf({
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.automation_ai_tools)) },
                        supportingContent = { Text(stringResource(R.string.automation_ai_tools_desc)) },
                        leadingContent = {
                            Icon(Icons.Default.Repeat, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            Switch(
                                checked = toolsEnabled,
                                onCheckedChange = viewModel.settings::setAutomationToolsEnabled,
                            )
                        },
                        modifier = Modifier.clickable {
                            viewModel.settings.setAutomationToolsEnabled(!toolsEnabled)
                        },
                    )
                }),
            )

            SettingsGroup(
                title = stringResource(R.string.automation_background_execution),
                items = listOf(
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.automation_daemon_mode)) },
                            supportingContent = { Text(stringResource(R.string.automation_daemon_mode_desc)) },
                            leadingContent = {
                                Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                Switch(
                                    checked = daemonEnabled,
                                    onCheckedChange = { enabled ->
                                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            requestNotificationPermission()
                                        } else {
                                            viewModel.settings.setDaemonEnabled(enabled)
                                        }
                                    },
                                )
                            },
                            modifier = Modifier.clickable {
                                if (daemonEnabled) {
                                    viewModel.settings.setDaemonEnabled(false)
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    requestNotificationPermission()
                                } else {
                                    viewModel.settings.setDaemonEnabled(true)
                                }
                            },
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.automation_exact_execution)) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        when {
                                            exactEnabled && exactPermissionGranted -> R.string.automation_exact_execution_on_desc
                                            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ->
                                                R.string.automation_exact_execution_off_legacy_desc
                                            else -> R.string.automation_exact_execution_off_desc
                                        }
                                    )
                                )
                            },
                            leadingContent = {
                                Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                Switch(
                                    checked = exactEnabled && exactPermissionGranted,
                                    onCheckedChange = ::setExactEnabled,
                                )
                            },
                            modifier = Modifier.clickable {
                                setExactEnabled(!(exactEnabled && exactPermissionGranted))
                            },
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = {
                                Text(stringResource(R.string.automation_wake_lock))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.automation_wake_lock_desc))
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.BatterySaver,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = wakeLockEnabled,
                                    onCheckedChange =
                                        viewModel.settings::setAutomationWakeLockEnabled,
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setAutomationWakeLockEnabled(!wakeLockEnabled)
                            },
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = {
                                Text(stringResource(R.string.automation_battery_optimization))
                            },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        if (batteryOptimizationIgnored) {
                                            R.string.automation_battery_optimization_ignored_desc
                                        } else {
                                            R.string.automation_battery_optimization_active_desc
                                        }
                                    )
                                )
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.BatterySaver,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            modifier = Modifier.clickable {
                                openBatteryOptimizationSettings()
                            },
                        )
                    },
                ),
            )

            // Heartbeat section
            HeartbeatSection(viewModel)

            // SMS section (fdroid only)
            SmsSection(viewModel)

            // Notifications section (fdroid only)
            NotificationsSection(viewModel, onManageApps = { showNotificationApps = true })
        }
        if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

private fun canScheduleExactAlarms(alarmManager: AlarmManager): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

/**
 * Heartbeat configuration section.
 * Shown on all flavors.
 */
@Composable
fun HeartbeatSection(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val enabled by viewModel.settings.heartbeatEnabled.collectAsState()
    val intervalMinutes by viewModel.settings.heartbeatIntervalMinutes.collectAsState()
    val activeHoursStart by viewModel.settings.heartbeatActiveHoursStart.collectAsState()
    val activeHoursEnd by viewModel.settings.heartbeatActiveHoursEnd.collectAsState()
    val customPrompt by viewModel.settings.heartbeatPrompt.collectAsState()
    val heartbeatModel by viewModel.settings.heartbeatModel.collectAsState()
    val heartbeatConversationId by viewModel.settings.heartbeatConversationId.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val conversations by viewModel.conversations.collectAsState()

    var showIntervalDialog by rememberSaveable { mutableStateOf(false) }
    var showActiveHoursDialog by rememberSaveable { mutableStateOf(false) }
    var showModelDialog by rememberSaveable { mutableStateOf(false) }
    var showPromptDialog by rememberSaveable { mutableStateOf(false) }
    var showConversationDialog by rememberSaveable { mutableStateOf(false) }

    val conversationTitle = remember(heartbeatConversationId, conversations) {
        if (heartbeatConversationId.isNullOrBlank()) null
        else conversations?.firstOrNull { it.id == heartbeatConversationId }?.title
    }

    SettingsGroup(
        title = stringResource(R.string.automation_heartbeat),
        items = listOf(
            {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.heartbeat_enabled)) },
                    supportingContent = { Text(stringResource(R.string.heartbeat_enabled_desc)) },
                    leadingContent = {
                        Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Switch(
                            checked = enabled,
                            onCheckedChange = viewModel.settings::setHeartbeatEnabled,
                        )
                    },
                    modifier = Modifier.clickable {
                        viewModel.settings.setHeartbeatEnabled(!enabled)
                    },
                )
            },
            {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.heartbeat_interval)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.heartbeat_interval_desc,
                                intervalMinutes,
                            )
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Text(
                            when (intervalMinutes) {
                                5 -> "5 min"
                                10 -> "10 min"
                                15 -> "15 min"
                                30 -> "30 min"
                                45 -> "45 min"
                                60 -> "1 h"
                                120 -> "2 h"
                                240 -> "4 h"
                                else -> "${intervalMinutes} min"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    modifier = Modifier.clickable { showIntervalDialog = true },
                )
            },
            {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.heartbeat_active_hours)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.heartbeat_active_hours_desc,
                                activeHoursStart,
                                if (activeHoursEnd == 24) 0 else activeHoursEnd,
                            )
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Text(
                            "${activeHoursStart}:00 – ${if (activeHoursEnd == 24) "0:00" else "$activeHoursEnd:00"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    modifier = Modifier.clickable { showActiveHoursDialog = true },
                )
            },
            {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.heartbeat_conversation)) },
                    supportingContent = { Text(stringResource(R.string.heartbeat_conversation_desc)) },
                    leadingContent = {
                        Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Text(
                            conversationTitle ?: stringResource(R.string.heartbeat_conversation_none),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 130.dp),
                        )
                    },
                    modifier = Modifier.clickable { showConversationDialog = true },
                )
            },
            {
                // Model override always applies (Kai-style); "Default" inherits the
                // selected conversation's model.
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.heartbeat_model)) },
                    supportingContent = { Text(stringResource(R.string.heartbeat_model_desc)) },
                    leadingContent = {
                        Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Text(
                            heartbeatModel?.let { modelApiDisplayName(it, customProviders) } ?: "Default",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 130.dp),
                        )
                    },
                    modifier = Modifier.clickable { showModelDialog = true },
                )
            },
            {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.heartbeat_custom_prompt)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.heartbeat_custom_prompt_desc,
                                if (customPrompt.isBlank()) 0 else customPrompt.length,
                            )
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { showPromptDialog = true },
                )
            },
            { HeartbeatRunStatusItems() },
        ),
    )

    if (showIntervalDialog) {
        val presets = listOf(5, 10, 15, 30, 45, 60, 120, 240)
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showIntervalDialog = false },
            title = { Text(stringResource(R.string.heartbeat_interval), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(presets, key = { it }) { minutes ->
                        val label = when (minutes) {
                            60 -> "1 h"
                            120 -> "2 h"
                            240 -> "4 h"
                            else -> "$minutes min"
                        }
                        SettingsItem(
                            headlineContent = { Text(label) },
                            leadingContent = {
                                RadioButton(
                                    selected = intervalMinutes == minutes,
                                    onClick = {
                                        viewModel.settings.saveHeartbeatIntervalMinutes(minutes)
                                        showIntervalDialog = false
                                    },
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.saveHeartbeatIntervalMinutes(minutes)
                                showIntervalDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIntervalDialog = false }) {
                    Text(stringResource(R.string.provider_close))
                }
            },
        )
    }

    if (showActiveHoursDialog) {
        var startHour by remember(activeHoursStart) { mutableStateOf(activeHoursStart.toFloat()) }
        var endHour by remember(activeHoursEnd) { mutableStateOf(if (activeHoursEnd == 24) 24f else activeHoursEnd.toFloat()) }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showActiveHoursDialog = false },
            title = { Text(stringResource(R.string.heartbeat_active_hours), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "${startHour.toInt()}:00 – ${if (endHour.toInt() == 24) "0:00" else "${endHour.toInt()}:00"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Start", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = startHour,
                        onValueChange = { startHour = it },
                        valueRange = 0f..23f,
                        steps = 22,
                    )
                    Text("End", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = endHour,
                        onValueChange = { endHour = it },
                        valueRange = 1f..24f,
                        steps = 22,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.settings.saveHeartbeatActiveHoursStart(startHour.toInt())
                    viewModel.settings.saveHeartbeatActiveHoursEnd(endHour.toInt())
                    showActiveHoursDialog = false
                }) {
                    Text(stringResource(R.string.provider_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showActiveHoursDialog = false }) {
                    Text(stringResource(R.string.provider_cancel))
                }
            },
        )
    }

    if (showModelDialog) {
        ModelPickerDialog(
            enabledModels = enabledModels.toList(),
            modelAliases = modelAliases,
            customProviders = customProviders,
            selected = heartbeatModel,
            onSelect = { selectedModel ->
                viewModel.settings.saveHeartbeatModel(selectedModel)
                showModelDialog = false
            },
            onDismiss = { showModelDialog = false },
        )
    }

    if (showPromptDialog) {
        var draftPrompt by remember(customPrompt) { mutableStateOf(customPrompt) }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showPromptDialog = false },
            title = { Text(stringResource(R.string.heartbeat_custom_prompt), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = draftPrompt,
                        onValueChange = { if (it.length <= 4000) draftPrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                        maxLines = 10,
                        placeholder = { Text("Leave blank to use the default heartbeat prompt") },
                    )
                    Text(
                        "${draftPrompt.length} / 4000",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.settings.saveHeartbeatPrompt(draftPrompt)
                    showPromptDialog = false
                }) {
                    Text(stringResource(R.string.provider_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPromptDialog = false }) {
                    Text(stringResource(R.string.provider_cancel))
                }
            },
        )
    }

    if (showConversationDialog) {
        val titled = remember(conversations) {
            conversations?.filter { it.title.isNotBlank() }?.sortedByDescending { it.id } ?: emptyList()
        }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showConversationDialog = false },
            title = { Text(stringResource(R.string.heartbeat_conversation_pick_title), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item(key = "__none__") {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.heartbeat_conversation_none)) },
                            leadingContent = {
                                RadioButton(
                                    selected = heartbeatConversationId.isNullOrBlank(),
                                    onClick = {
                                        viewModel.settings.saveHeartbeatConversationId(null)
                                        showConversationDialog = false
                                    },
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.saveHeartbeatConversationId(null)
                                showConversationDialog = false
                            },
                        )
                    }
                    items(titled, key = { it.id }) { conv ->
                        SettingsItem(
                            headlineContent = {
                                Text(
                                    conv.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = heartbeatConversationId == conv.id,
                                    onClick = {
                                        viewModel.settings.saveHeartbeatConversationId(conv.id)
                                        showConversationDialog = false
                                    },
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.saveHeartbeatConversationId(conv.id)
                                showConversationDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConversationDialog = false }) {
                    Text(stringResource(R.string.provider_close))
                }
            },
        )
    }
}

/**
 * Human-readable label for the last SMS poll, based on the persisted sync state.
 * Must be called from a Composable context to access string resources.
 */
@Composable
private fun LastSmsPollLabel(state: com.newoether.agora.data.SmsSyncState?): String {
    val s = state ?: return stringResource(R.string.sms_status_never_polled)
    val error = s.lastError
    if (error != null) return stringResource(R.string.sms_status_last_failed, error)
    if (s.lastSyncEpochMs == 0L) return stringResource(R.string.sms_status_never_polled)
    val minutes = (System.currentTimeMillis() - s.lastSyncEpochMs) / 60_000L
    return when {
        minutes < 1 -> stringResource(R.string.sms_status_just_now)
        minutes < 60 -> stringResource(R.string.sms_status_minutes_ago, minutes)
        else -> stringResource(R.string.sms_status_hours_ago, minutes / 60)
    }
}

/**
 * SMS configuration section.
 * Only shown on fdroid flavor where SMS is supported.
 */
@Composable
fun SmsSection(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val readEnabled by viewModel.settings.smsReadEnabled.collectAsState()
    val sendEnabled by viewModel.settings.smsSendEnabled.collectAsState()
    val pollIntervalMinutes by viewModel.settings.smsPollIntervalMinutes.collectAsState()
    val isSupported by viewModel.settings.smsReaderSupported.collectAsState()
    val pendingCount by viewModel.smsUi.smsPendingCount.collectAsState(initial = 0)
    val syncState by viewModel.smsUi.smsSyncState.collectAsState(initial = null)
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var showPollIntervalDialog by rememberSaveable { mutableStateOf(false) }

    if (!isSupported) return

    // Turning a toggle ON must request the corresponding runtime permission — flipping the
    // DataStore flag alone does nothing if READ_SMS/SEND_SMS was never granted, and this
    // screen is the only place the user can trigger that request. Turning OFF needs no
    // permission, so it updates the flag directly.
    val readSmsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.settings.setSmsReadEnabled(granted) }

    val sendSmsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.settings.setSmsSendEnabled(granted) }

    fun onToggleReadEnabled(next: Boolean) {
        if (next) {
            readSmsPermissionLauncher.launch(Manifest.permission.READ_SMS)
        } else {
            viewModel.settings.setSmsReadEnabled(false)
        }
    }

    fun onToggleSendEnabled(next: Boolean) {
        if (next) {
            sendSmsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
        } else {
            viewModel.settings.setSmsSendEnabled(false)
        }
    }

    SettingsGroup(
        title = stringResource(R.string.automation_sms),
        items = listOf(
            // Read SMS subsection
            {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.sms_read_enabled)) },
                    supportingContent = { Text(stringResource(R.string.sms_read_enabled_desc)) },
                    leadingContent = {
                        Icon(Icons.Default.Sms, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Switch(
                            checked = readEnabled,
                            onCheckedChange = ::onToggleReadEnabled,
                        )
                    },
                    modifier = Modifier.clickable {
                        onToggleReadEnabled(!readEnabled)
                    },
                )
            },
            // Send SMS subsection
            {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.sms_send_enabled)) },
                    supportingContent = { Text(stringResource(R.string.sms_send_enabled_desc)) },
                    leadingContent = {
                        Icon(Icons.Default.Sms, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Switch(
                            checked = sendEnabled,
                            onCheckedChange = ::onToggleSendEnabled,
                        )
                    },
                    modifier = Modifier.clickable {
                        onToggleSendEnabled(!sendEnabled)
                    },
                )
            },
            // Poll interval
            {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.sms_poll_interval)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.sms_poll_interval_desc,
                                pollIntervalMinutes,
                            )
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Text(
                            when (pollIntervalMinutes) {
                                0 -> "Never"
                                5 -> "5 min"
                                15 -> "15 min"
                                30 -> "30 min"
                                60 -> "60 min"
                                else -> "${pollIntervalMinutes} min"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    modifier = Modifier.clickable { showPollIntervalDialog = true },
                )
            },
            // Queued count, last poll status + manual refresh
            {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.sms_status)) },
                    supportingContent = {
                        Column {
                            Text(
                                if (pendingCount > 0) {
                                    stringResource(R.string.sms_status_queued, pendingCount)
                                } else {
                                    stringResource(R.string.sms_status_none)
                                },
                            )
                            Text(
                                LastSmsPollLabel(syncState),
                                color = if (syncState?.lastError != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    },
                    leadingContent = {
                        Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        IconButton(
                            onClick = {
                                refreshing = true
                                viewModel.smsUi.refreshSmsNow()
                                scope.launch {
                                    kotlinx.coroutines.delay(1_500)
                                    refreshing = false
                                }
                            },
                        ) {
                            if (refreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.sms_refresh_now))
                            }
                        }
                    },
                )
            },
        ),
    )

    if (showPollIntervalDialog) {
        val presets = listOf(0, 5, 15, 30, 60)
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showPollIntervalDialog = false },
            title = {
                Text(
                    stringResource(R.string.sms_poll_interval),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
            },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(presets, key = { it }) { minutes ->
                        val label = when (minutes) {
                            0 -> "Never"
                            60 -> "60 min"
                            else -> "$minutes min"
                        }
                        SettingsItem(
                            headlineContent = { Text(label) },
                            leadingContent = {
                                RadioButton(
                                    selected = pollIntervalMinutes == minutes,
                                    onClick = {
                                        viewModel.settings.saveSmsPollIntervalMinutes(minutes)
                                        showPollIntervalDialog = false
                                    },
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.saveSmsPollIntervalMinutes(minutes)
                                showPollIntervalDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPollIntervalDialog = false }) {
                    Text(stringResource(R.string.provider_close))
                }
            },
        )
    }
}

/**
 * Model ids (especially custom-provider ones) can be arbitrarily long, unbroken strings
 * (no spaces, just hyphens/colons). Shown at full length inside the description sentence,
 * they force that sentence to wrap character-by-character once the trailing chip claims
 * its own space in the row. Truncate for the sentence; the trailing chip (which is already
 * ellipsized) remains the place to see/tap for the exact value.
 */
private fun truncateModelName(model: String?, maxLength: Int = 28): String? {
    if (model == null) return null
    return if (model.length <= maxLength) model else model.take(maxLength - 1) + "…"
}