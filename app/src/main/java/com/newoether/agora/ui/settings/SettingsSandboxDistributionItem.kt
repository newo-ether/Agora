package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.sandbox.SandboxDistribution
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem

/** Owns the Appearance-style immediate distribution choice and its menu state. */
@Composable
internal fun SettingsSandboxDistributionItem(
    distribution: SandboxDistribution,
    enabled: Boolean,
    error: String?,
    onSelect: (SandboxDistribution) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }
    SettingsItem(
        headlineContent = { Text(stringResource(R.string.sandbox_distribution)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.sandbox_distribution_desc))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        leadingContent = { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Box {
                Text(
                    distribution.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AgoraDropdownMenu(
                    expanded = expanded && enabled,
                    onDismissRequest = { expanded = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 16.dp,
                ) {
                    SandboxDistribution.entries.forEach { choice ->
                        AgoraDropdownMenuItem(
                            text = { Text("${choice.displayName} ${choice.versionLabel}") },
                            leadingIcon = {
                                if (choice == distribution) {
                                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            enabled = enabled,
                            onClick = {
                                expanded = false
                                if (enabled && choice != distribution) onSelect(choice)
                            },
                        )
                    }
                }
            }
        },
        modifier = Modifier.clickable(enabled = enabled) { expanded = true },
    )
}

@Composable
internal fun SandboxDistributionIcon(distribution: SandboxDistribution) {
    if (distribution == SandboxDistribution.ALPINE) {
        Icon(
            painter = painterResource(R.drawable.ic_alpine_linux),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    } else {
        Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary)
    }
}
