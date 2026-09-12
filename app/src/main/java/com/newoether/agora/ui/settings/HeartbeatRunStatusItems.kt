package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.AgoraApplication
import com.newoether.agora.data.local.HeartbeatLogEntity
import com.newoether.agora.R
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Heartbeat "Run now" + "Recent runs" settings items. Extracted from
 * SettingsAutomationPage so that file stays within the 999-line source policy.
 * Reaches the scheduler/manager through the application container — the same
 * lookup HeartbeatScheduler itself uses.
 */
@Composable
fun HeartbeatRunStatusItems() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val container = remember {
        (context.applicationContext as? AgoraApplication)?.requireContainer()
    }
    var recentLogs by remember { mutableStateOf(emptyList<HeartbeatLogEntity>()) }
    LaunchedEffect(Unit) {
        container?.heartbeatManager?.recentLogs?.collect { recentLogs = it }
    }

    // SettingsGroup wraps each `items` entry in a single Surface (a Box internally), so
    // multiple SettingsItem siblings emitted here must be wrapped in a Column themselves —
    // otherwise they stack on top of each other instead of one below the other.
    Column(modifier = Modifier.fillMaxWidth()) {

    SettingsItem(
        headlineContent = { Text(stringResource(R.string.heartbeat_run_now)) },
        supportingContent = { Text(stringResource(R.string.heartbeat_run_now_desc)) },
        leadingContent = {
            Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            IconButton(
                onClick = {
                    val scheduler = container?.heartbeatScheduler
                    scope.launch { scheduler?.runHeartbeatNow() }
                },
            ) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.heartbeat_run_now))
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val scheduler = container?.heartbeatScheduler
                scope.launch { scheduler?.runHeartbeatNow() }
            },
    )

    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

    SettingsItem(
        headlineContent = { Text(stringResource(R.string.heartbeat_recent_runs)) },
        supportingContent = {
            Column {
                if (recentLogs.isEmpty()) {
                    Text(stringResource(R.string.heartbeat_no_runs))
                } else {
                    for (log in recentLogs) {
                        Text(
                            buildString {
                                append(formatHeartbeatLogTime(log.timestampEpochMs))
                                append(" · ")
                                append(if (log.success) stringResource(R.string.heartbeat_log_ok) else stringResource(R.string.heartbeat_log_failed))
                                log.error?.let { append(" · ").append(it) }
                            },
                        )
                    }
                }
            }
        },
        leadingContent = {
            Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier.fillMaxWidth(),
    )
    }
}

private fun formatHeartbeatLogTime(epochMs: Long): String {
    return Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}