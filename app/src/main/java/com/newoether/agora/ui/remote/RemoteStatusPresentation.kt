package com.newoether.agora.ui.remote

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import com.newoether.agora.R
import com.newoether.agora.mcp.McpConnectionStatus
import com.newoether.agora.remote.RemoteDeviceStatus
import com.newoether.agora.remote.RemoteState
import com.newoether.agora.ui.settings.McpStatusDot

internal fun RemoteDeviceStatus.toMcpConnectionStatus(): McpConnectionStatus = when (this) {
    RemoteDeviceStatus.IDLE -> McpConnectionStatus.IDLE
    RemoteDeviceStatus.CONNECTING -> McpConnectionStatus.CONNECTING
    RemoteDeviceStatus.CONNECTED -> McpConnectionStatus.CONNECTED
    RemoteDeviceStatus.ERROR -> McpConnectionStatus.ERROR
}

/**
 * Remote status uses exactly three colors: Online primary, Connecting dimmed primary and
 * Offline neutral. An unavailable source or unreachable device is Offline, not an error;
 * failure details are reported through the Snackbar, never through a red dot.
 */
@Composable
internal fun RemoteStatusDot(status: McpConnectionStatus) {
    McpStatusDot(if (status == McpConnectionStatus.ERROR) McpConnectionStatus.IDLE else status)
}
@StringRes
internal fun remoteConnectionStatusLabel(status: McpConnectionStatus): Int = when (status) {
    McpConnectionStatus.CONNECTED -> R.string.remote_online
    McpConnectionStatus.CONNECTING -> R.string.remote_connecting
    McpConnectionStatus.IDLE, McpConnectionStatus.ERROR -> R.string.remote_offline
}

/** Display native Desktop availability; gateway failure can only remove readiness. */
internal fun RemoteState.remoteConnectionStatus(): McpConnectionStatus {
    val device = devices.firstOrNull { it.id == deviceId } ?: return McpConnectionStatus.IDLE
    return (if (device.status == RemoteDeviceStatus.CONNECTED) selectedAdapter.status else device.status)
        .toMcpConnectionStatus()
}

/** Empty guidance never replaces an admitted message, including during read failure or recovery. */
@StringRes
internal fun RemoteState.remoteEmptyConversationHint(): Int? {
    if (session == null || messageGroups.isNotEmpty()) return null
    val connection = remoteConnectionStatus()
    val source = selectedAdapter
    // Disconnected means no current native owner. A configured source may still create one.
    // Keep an unavailable source's headline while its gateway check is in flight.
    val unavailableSource = !source.legacy && (source.status == RemoteDeviceStatus.ERROR ||
        source.status == RemoteDeviceStatus.IDLE && !(isDraft && canCreate))
    return when {
        error -> R.string.remote_empty_retry_hint
        unavailableSource -> R.string.remote_source_unavailable
        loading || connection == McpConnectionStatus.CONNECTING -> R.string.remote_empty_connecting_hint
        isDraft && (connection == McpConnectionStatus.CONNECTED || !source.legacy && canCreate &&
            devices.firstOrNull { it.id == deviceId }?.status == RemoteDeviceStatus.CONNECTED) -> R.string.remote_new_chat_hint
        connection != McpConnectionStatus.CONNECTED -> R.string.remote_empty_offline_hint
        !hydrationEnabled -> R.string.remote_empty_connecting_hint
        else -> R.string.remote_empty_conversation_hint
    }
}

internal fun RemoteState.canRetryEmptyConnection(): Boolean = remoteEmptyConversationHint() in setOf(
    R.string.remote_source_unavailable, R.string.remote_empty_retry_hint, R.string.remote_empty_offline_hint,
)
