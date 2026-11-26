package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.auth.OAuthAccount
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator

/**
 * Account group for Providers that authenticate through a browser sign-in instead of API keys.
 * The actions run on the app scope, so a pending sign-in survives leaving the page; [onCancel]
 * abandons it.
 */
@Composable
internal fun ProviderAccountSettings(
    account: OAuthAccount,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onCancel: () -> Unit,
) {
    val state by account.state.collectAsState()
    SettingsGroup(
        title = stringResource(R.string.provider_account),
        items = listOf {
            SettingsItem(
                headlineContent = {
                    Text(
                        when {
                            state.busy -> stringResource(R.string.provider_account_waiting)
                            state.signedIn -> state.account ?: stringResource(R.string.provider_account_signed_in)
                            else -> stringResource(R.string.provider_account_signed_out)
                        },
                    )
                },
                supportingContent = {
                    val error = state.error
                    if (error != null) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(stringResource(R.string.provider_account_desc))
                    }
                },
                leadingContent = {
                    Icon(Icons.Rounded.AccountCircle, null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingContent = {
                    when {
                        state.busy -> TextButton(onClick = onCancel) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.cancel))
                        }
                        state.signedIn -> TextButton(onClick = onSignOut) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.provider_account_sign_out))
                        }
                        else -> TextButton(onClick = onSignIn) {
                            Icon(Icons.AutoMirrored.Rounded.Login, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.provider_account_sign_in))
                        }
                    }
                },
            )
        },
    )
}
