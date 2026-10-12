package com.newoether.agora.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Switch
import com.newoether.agora.ui.components.Text

@Composable
fun SettingsAutomationPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val toolsEnabled by viewModel.settings.automationToolsEnabled.collectAsState()
    val exactEnabled by viewModel.settings.exactExecutionEnabled.collectAsState()
    val wakeLockEnabled by viewModel.settings.automationWakeLockEnabled.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    val alarmManager = remember {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }
    val powerManager = remember {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    var exactPermissionGranted by remember {
        mutableStateOf(canScheduleExactAlarms(alarmManager))
    }
    var batteryOptimizationIgnored by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    var awaitingExactPermission by rememberSaveable { mutableStateOf(false) }

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
        }
        if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

private fun canScheduleExactAlarms(alarmManager: AlarmManager): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
