package com.newoether.agora.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Notifications configuration section.
 * Only shown when the notification listener capability is available on this build.
 * Extracted from SettingsAutomationPage so that file stays within the 999-line source policy.
 */
@Composable
fun NotificationsSection(viewModel: ChatViewModel, onManageApps: () -> Unit) {
    val context = LocalContext.current
    val enabled by viewModel.settings.notificationsEnabled.collectAsState()
    val pendingQueue by viewModel.settings.notificationsPending.collectAsState()
    val isSupported by viewModel.settings.notificationListenerSupported.collectAsState()

    if (!isSupported) return

    val coroutineScope = rememberCoroutineScope()
    val notificationListenerStatus by viewModel.settings.notificationListenerStatus.collectAsState()

    // Launcher for Notification Access settings — returns when user comes back
    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // User returned from Notification Access settings — force status refresh
        viewModel.settings.triggerNotificationListenerStatusRefresh()
    }

    // Helper to open Notification Access settings via launcher
    fun openNotificationListenerSettingsWithRefresh() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        } else {
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        settingsLauncher.launch(intent)
    }

    SettingsGroup(
        title = stringResource(R.string.automation_notifications),
        items = listOf(
            {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.notifications_read_enabled)) },
                    supportingContent = { Text(stringResource(R.string.notifications_read_enabled_desc)) },
                    leadingContent = {
                        Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Switch(
                            checked = enabled,
                            onCheckedChange = { newEnabled ->
                                viewModel.settings.setNotificationsEnabled(newEnabled)
                                if (newEnabled) {
                                    openNotificationListenerSettingsWithRefresh()
                                }
                            },
                        )
                    },
                    modifier = Modifier.clickable {
                        if (enabled) {
                            viewModel.settings.setNotificationsEnabled(false)
                        } else {
                            viewModel.settings.setNotificationsEnabled(true)
                            openNotificationListenerSettingsWithRefresh()
                        }
                    },
                )
            },
            {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.notifications_listener_status)) },
                    supportingContent = {
                        Text(
                            if (notificationListenerStatus.hasAccess) {
                                stringResource(R.string.notifications_listener_active)
                            } else {
                                stringResource(R.string.notifications_listener_inactive)
                            }
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.Notifications,
                            null,
                            tint = if (notificationListenerStatus.hasAccess) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    },
                    modifier = Modifier.clickable {
                        // Always open settings (Option C) — user can manage app list anytime
                        openNotificationListenerSettingsWithRefresh()
                    },
                )
            },
            {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.notifications_manage_apps)) },
                    supportingContent = { Text(stringResource(R.string.notifications_manage_apps_desc)) },
                    leadingContent = {
                        Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable {
                        onManageApps()
                    },
                )
            },
            {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.notifications_queued_count)) },
                    supportingContent = {
                        val count = try {
                            Json.decodeFromString<List<String>>(pendingQueue).size
                        } catch (e: Exception) {
                            0
                        }
                        Text(
                            stringResource(
                                R.string.notifications_queued_count_desc,
                                count,
                            )
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    viewModel.settings.clearNotificationsPending()
                                }
                            },
                        ) {
                            Text(stringResource(R.string.notifications_clear_queue))
                        }
                    },
                )
            },
        ),
    )
}
