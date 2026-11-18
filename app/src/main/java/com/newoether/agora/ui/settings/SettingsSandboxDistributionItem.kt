package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.sandbox.SandboxDistribution
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.motion.MotionAwareLinearProgressIndicator

@Composable
internal fun SettingsSandboxDistributionItem(
    distribution: SandboxDistribution,
    enabled: Boolean,
    onSelect: (SandboxDistribution) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }
    SettingsItem(
        headlineContent = { Text(stringResource(R.string.sandbox_distribution)) },
        supportingContent = { Text(stringResource(R.string.sandbox_distribution_desc)) },
        leadingContent = { Icon(Icons.Default.ListAlt, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Box {
                SandboxSetupLabel(distribution.displayName, enabled, singleLine = true)
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
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button) { expanded = true },
    )
}

@Composable
internal fun SettingsSandboxInstallItem(
    distribution: SandboxDistribution,
    enabled: Boolean,
    installing: Boolean,
    downloadProgress: Float?,
    onInstall: () -> Unit,
) {
    SettingsItem(
        headlineContent = { Text("${distribution.displayName} ${distribution.versionLabel}") },
        supportingContent = {
            if (installing) {
                Column {
                    val progressModifier = Modifier.fillMaxWidth().height(4.dp)
                    if (downloadProgress == null) {
                        MotionAwareLinearProgressIndicator(modifier = progressModifier)
                    } else {
                        LinearProgressIndicator(progress = { downloadProgress }, modifier = progressModifier)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (downloadProgress == null) stringResource(R.string.sandbox_extracting_rootfs)
                        else stringResource(R.string.sandbox_downloading_rootfs, (downloadProgress * 100).toInt()),
                    )
                }
            } else {
                Text(stringResource(R.string.sandbox_not_installed))
            }
        },
        leadingContent = { SandboxDistributionIcon(distribution) },
        trailingContent = if (installing) null else ({
            SandboxSetupLabel(stringResource(R.string.sandbox_install), enabled)
        }),
        modifier = if (installing) Modifier else Modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            onClick = onInstall,
        ),
    )
}

/** The selected value and install action share the Appearance trailing-label treatment. */
@Composable
private fun SandboxSetupLabel(label: String, enabled: Boolean, singleLine: Boolean = false) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        modifier = Modifier.width(96.dp).padding(end = 4.dp),
        textAlign = TextAlign.End,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
    )
}

@Composable
internal fun SandboxDistributionIcon(distribution: SandboxDistribution) {
    val icon = when (distribution) {
        SandboxDistribution.ALPINE -> R.drawable.ic_alpine_linux
        SandboxDistribution.DEBIAN -> R.drawable.ic_sandbox_debian
        SandboxDistribution.UBUNTU -> R.drawable.ic_sandbox_ubuntu
    }
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
}
