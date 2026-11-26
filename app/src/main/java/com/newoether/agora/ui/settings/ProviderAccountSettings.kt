package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.auth.AccountDetail
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
    // Balance and plan are read each time the page opens or the account signs in.
    var detailsRefreshing by remember(account) { mutableStateOf(false) }
    LaunchedEffect(account, state.signedIn) {
        if (!state.signedIn) return@LaunchedEffect
        detailsRefreshing = true
        try {
            account.refreshDetails()
        } finally {
            detailsRefreshing = false
        }
    }
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
    // The group stays put once signed in. A value that has not arrived yet renders as the same
    // solid capsule with the sweeping gradient the Storage page uses, instead of the group
    // appearing only after the first refresh returns.
    val kinds = account.detailKinds
    if (state.signedIn && kinds.isNotEmpty()) {
        SettingsGroup(
            title = stringResource(R.string.provider_account_info),
            items = kinds.map<AccountDetail.Kind, @Composable () -> Unit> { kind ->
                {
                    val value = state.details.firstOrNull { it.kind == kind }?.value
                    SettingsItem(
                        headlineContent = {
                            StatsLoadingValue(
                                loading = detailsRefreshing && value == null,
                                placeholderWidth = 88.dp,
                                placeholderHeight = 20.dp,
                                text = value ?: "—",
                                alignment = Alignment.CenterStart,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                            )
                        },
                        supportingContent = { Text(stringResource(kind.label())) },
                        leadingContent = {
                            Icon(kind.icon(), null, tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            },
        )
    }
}

private fun AccountDetail.Kind.label(): Int = when (this) {
    AccountDetail.Kind.PLAN -> R.string.provider_account_plan
    AccountDetail.Kind.BALANCE -> R.string.provider_account_balance
    AccountDetail.Kind.BONUS_BALANCE -> R.string.provider_account_bonus_balance
}

private fun AccountDetail.Kind.icon() = when (this) {
    AccountDetail.Kind.PLAN -> Icons.Rounded.WorkspacePremium
    AccountDetail.Kind.BALANCE -> Icons.Rounded.AccountBalanceWallet
    AccountDetail.Kind.BONUS_BALANCE -> Icons.Rounded.CardGiftcard
}
