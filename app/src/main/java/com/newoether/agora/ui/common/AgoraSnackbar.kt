package com.newoether.agora.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import kotlinx.coroutines.launch

/**
 * Delivers a transient message to the nearest snackbar host.
 *
 * The app root supplies the global host. A screen that opens its own window — the full-screen
 * player, a link preview — wraps that window in [DialogSnackbarHost] so the message stays visible
 * above the window instead of being drawn behind it.
 */
val LocalSnackbarMessage = staticCompositionLocalOf<(String) -> Unit> { {} }

/** The one snackbar appearance shared by the app-level host and dialog-level hosts. */
@Composable
fun AgoraSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    Snackbar(
        modifier = modifier.padding(horizontal = 12.dp).padding(vertical = 10.dp)
            .shadow(6.dp, RoundedCornerShape(12.dp), clip = false),
        shape = RoundedCornerShape(12.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        actionContentColor = MaterialTheme.colorScheme.primary,
        dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        dismissAction = @Composable {
            Box(modifier = Modifier.padding(end = 8.dp)) {
                IconButton(
                    onClick = { data.dismiss() },
                    modifier = Modifier.size(28.dp).clip(CircleShape),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cancel),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        action = data.visuals.actionLabel?.let { label ->
            @Composable { TextButton(onClick = { data.performAction() }) { Text(label) } }
        },
        content = { Text(data.visuals.message) },
    )
}

/**
 * Provides [LocalSnackbarMessage] backed by a host drawn inside the current dialog window, so a
 * message raised by a sheet or player that lives in its own window remains readable.
 */
@Composable
fun DialogSnackbarHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = remember(hostState, scope) {
        { message ->
            scope.launch {
                hostState.currentSnackbarData?.dismiss()
                hostState.showSnackbar(message)
            }
        }
    }
    CompositionLocalProvider(LocalSnackbarMessage provides notify) {
        Box(modifier = modifier.fillMaxSize()) {
            content()
            SnackbarHost(
                hostState = hostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
                snackbar = { data -> AgoraSnackbar(data) },
            )
        }
    }
}
