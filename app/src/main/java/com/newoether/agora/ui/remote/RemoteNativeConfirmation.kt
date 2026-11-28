package com.newoether.agora.ui.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.remote.RemoteSettingsChange

@Composable
internal fun RemoteNativeConfirmation(settings: RemoteSettingsChange?) {
    if (settings != null) {
        val text = when (settings.state) {
            "pending" -> stringResource(R.string.remote_settings_pending_request)
            "confirmed" -> stringResource(R.string.remote_settings_confirmed_request)
            "failed" -> stringResource(R.string.remote_settings_failed_request, settings.actualModel, settings.actualEffort)
            else -> null
        }
        if (text != null) Text(text, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
