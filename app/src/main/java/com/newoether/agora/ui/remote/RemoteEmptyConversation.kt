package com.newoether.agora.ui.remote

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.remote.RemoteState
import com.newoether.agora.remote.remoteDeviceName

/** Product and configured device identity share chat's existing top/composer range. */
@Composable
internal fun RemoteEmptyConversation(
    state: RemoteState,
    active: Boolean,
    topBarHeight: Dp,
    bottomBarHeight: Dp,
    onRetry: () -> Unit,
) {
    val currentHint = state.remoteEmptyConversationHint() ?: return
    var settledHint by remember(state.deviceId, state.selectedAdapter.id, state.selectedAdapter.nativeScopeId, state.session?.id) {
        mutableIntStateOf(currentHint)
    }
    val failedHints = setOf(R.string.remote_source_unavailable, R.string.remote_empty_retry_hint,
        R.string.remote_empty_offline_hint)
    // The cover owns retry motion; its underlying failed body stays stable until completion.
    val hint = if (state.loading && settledHint in failedHints) settledHint else currentHint
    if (!state.loading) settledHint = currentHint
    val normalWelcome = hint == R.string.remote_new_chat_hint || hint == R.string.remote_empty_conversation_hint ||
        hint == R.string.remote_empty_connecting_hint
    val headline = when {
        normalWelcome -> remoteSourceName(state.selectedAdapter)
        // Name the source directly, e.g. "No active Claude Code session".
        hint == R.string.remote_source_unavailable ->
            stringResource(R.string.remote_source_no_session, remoteSourceName(state.selectedAdapter))
        else -> stringResource(hint)
    }
    val deviceName = remoteDeviceName(state.devices.firstOrNull { it.id == state.deviceId }?.name.orEmpty())
        .ifEmpty { stringResource(R.string.remote_device) }
    val retryVisible = hint in failedHints
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(top = topBarHeight, bottom = bottomBarHeight),
    ) {
        val availableHeight = maxHeight
        val scrollState = rememberScrollState()
        // The whole block keeps its natural size and is centered in the visible range; when it is
        // taller than that range it scrolls. The mark is never shrunk to fit.
        Column(
            modifier = Modifier.fillMaxWidth()
                .testTag("remote-empty-conversation")
                .verticalScroll(scrollState, overscrollEffect = null)
                .heightIn(min = availableHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(remoteSourceIcon(state.selectedAdapter)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(80.dp).testTag("remote-welcome-mark"),
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = headline,
                        modifier = Modifier.semantics { heading() }.testTag("remote-welcome-headline"),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, lineHeight = 35.sp),
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = deviceName,
                        modifier = Modifier.testTag("remote-welcome-device"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (retryVisible) {
                        Spacer(Modifier.height(16.dp))
                        TextButton(enabled = active && !state.loading, onClick = onRetry) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
        }
    }
}
