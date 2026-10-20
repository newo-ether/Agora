package com.newoether.agora.ui.settings

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.mcp.McpConnectionStatus
import com.newoether.agora.mcp.McpServerSnapshot
import com.newoether.agora.ui.motion.Crossfade
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Text

private data class McpStatusUiState(
    val status: McpConnectionStatus,
    val enabledToolCount: Int,
    val error: String?,
)

@Composable
internal fun McpStatusDot(status: McpConnectionStatus) {
    val description = stringResource(
        when (status) {
            McpConnectionStatus.IDLE -> R.string.mcp_status_idle
            McpConnectionStatus.CONNECTING -> R.string.mcp_status_connecting
            McpConnectionStatus.CONNECTED -> R.string.mcp_status_connected
            McpConnectionStatus.ERROR -> R.string.mcp_status_error
        },
    )
    val color = when (status) {
        McpConnectionStatus.IDLE -> {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        }
        McpConnectionStatus.CONNECTING -> {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        }
        McpConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
        McpConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color = color, shape = CircleShape)
            .semantics { contentDescription = description },
    )
}

@Composable
internal fun McpStatusText(
    snapshot: McpServerSnapshot?,
    includeError: Boolean = false,
) {
    val tools = snapshot?.tools
    val enabledToolCount = remember(tools) {
        tools?.count { it.enabled } ?: 0
    }
    val state = McpStatusUiState(
        status = snapshot?.status ?: McpConnectionStatus.IDLE,
        enabledToolCount = enabledToolCount,
        error = snapshot?.error?.takeIf(String::isNotBlank),
    )
    Crossfade(
        targetState = state,
        animationSpec = tween(durationMillis = 250),
        label = "mcpStatusText",
    ) { current ->
        val color = when (current.status) {
            McpConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
            McpConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = when (current.status) {
                    McpConnectionStatus.IDLE -> stringResource(R.string.mcp_status_idle)
                    McpConnectionStatus.CONNECTING -> stringResource(R.string.mcp_status_connecting)
                    McpConnectionStatus.CONNECTED -> stringResource(R.string.mcp_status_connected)
                    McpConnectionStatus.ERROR -> stringResource(R.string.mcp_status_error)
                },
                color = color,
            )
            when {
                current.status == McpConnectionStatus.CONNECTED -> Text(
                    text = stringResource(
                        R.string.mcp_tools_enabled,
                        current.enabledToolCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                includeError && current.error != null -> Text(
                    text = current.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
internal fun McpStatusIcon(status: McpConnectionStatus) {
    Crossfade(
        targetState = status,
        animationSpec = tween(durationMillis = 250),
        label = "mcpStatusIcon",
    ) { current ->
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (current) {
                McpConnectionStatus.IDLE -> Icon(Icons.Default.CloudOff, null)
                McpConnectionStatus.CONNECTING -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )
                McpConnectionStatus.CONNECTED -> Icon(
                    Icons.Default.CheckCircle,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                McpConnectionStatus.ERROR -> Icon(
                    Icons.Default.Error,
                    null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
