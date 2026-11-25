package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.chat.message.ChatMarkdownCodeBlock
import com.newoether.agora.util.rememberContentTextDirection

internal const val CONCH_USER_UNIX_INSTALL =
    "curl -fsSL https://raw.githubusercontent.com/newo-ether/conch/main/scripts/install.sh | bash"
internal const val CONCH_USER_WINDOWS_INSTALL =
    "Set-ExecutionPolicy Bypass -Scope Process -Force; irm https://raw.githubusercontent.com/newo-ether/conch/main/scripts/install.ps1 | iex"
internal const val FILO_WINDOWS_INSTALL =
    "Set-ExecutionPolicy Bypass -Scope Process -Force; irm https://raw.githubusercontent.com/newo-ether/filo/main/install.ps1 | iex"

/** Help belongs to the new device draft, before its connection fields. */
@Composable
internal fun SettingsInstallationHelp(conch: Boolean, enabled: Boolean, onClick: () -> Unit) {
    SettingsGroup(stringResource(R.string.install_guide_help), items = listOf {
        SettingsItem(
            headlineContent = { Text(stringResource(if (conch) R.string.conch_guide_title else R.string.filo_guide_title)) },
            supportingContent = { Text(stringResource(if (conch) R.string.conch_guide_description else R.string.filo_guide_description)) },
            leadingContent = { Icon(Icons.Rounded.HelpOutline, null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.testTag(if (conch) "shell-conch-guide-entry" else "remote-filo-guide-entry")
                .clickable(enabled = enabled, onClick = onClick),
        )
    })
}

@Composable
internal fun SettingsConchGuidePage(onBack: () -> Unit) {
    var platform by remember { mutableIntStateOf(0) }
    InstallationGuide(conch = true, platform = platform, onPlatform = { platform = it }, onBack = onBack)
}

@Composable
internal fun SettingsFiloGuidePage(onBack: () -> Unit) {
    InstallationGuide(conch = false, platform = 0, onPlatform = {}, onBack = onBack)
}

@Composable
private fun InstallationGuide(conch: Boolean, platform: Int, onPlatform: (Int) -> Unit, onBack: () -> Unit) {
    val pageActive = LocalSettingsScrollRetentionActive.current
    var closed by remember { mutableStateOf(false) }
    SideEffect { if (!pageActive) closed = true }
    val latestPageActive by rememberUpdatedState(pageActive)
    fun ownsGuide() = latestPageActive && !closed
    val active = ownsGuide()
    val back = { if (ownsGuide()) { closed = true; onBack() } }
    BackHandler(enabled = active, onBack = back)
    val prefix = if (conch) "conch-guide" else "filo-guide"
    val command = if (!conch) FILO_WINDOWS_INSTALL else if (platform == 0) CONCH_USER_WINDOWS_INSTALL else CONCH_USER_UNIX_INSTALL
    CollapsingSettingsScaffold(
        title = stringResource(if (conch) R.string.conch_guide_title else R.string.filo_guide_title),
        onBack = back,
        modifier = Modifier.testTag(if (conch) "conch-install-guide" else "filo-install-guide"),
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.install_guide_purpose),
                modifier = Modifier.testTag("$prefix-introduction"),
                items = listOf {
                    SettingsItem(
                        headlineContent = { Text(if (conch) "Conch" else "Filo") },
                        supportingContent = {
                            Text(stringResource(if (conch) R.string.conch_guide_description else R.string.filo_guide_description))
                        },
                        leadingContent = { Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                },
            )
            SettingsGroup(
                title = stringResource(R.string.install_guide_install),
                modifier = Modifier.testTag("$prefix-installation"),
                items = buildList {
                    if (conch) add {
                        SettingsIconContent(
                            icon = Icons.Rounded.Devices,
                            modifier = Modifier.testTag("conch-guide-platform"),
                        ) {
                            Text(
                                stringResource(R.string.install_guide_platform),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(12.dp))
                            PillTabSwitcher(
                                tabs = listOf(R.string.install_guide_windows, R.string.install_guide_linux, R.string.install_guide_termux)
                                    .map { stringResource(it) },
                                selectedIndex = platform,
                                onSelect = { if (ownsGuide()) onPlatform(it) },
                                enabled = active,
                            )
                        }
                    }
                    add {
                        InstallationStep(1, stringResource(R.string.install_guide_open_terminal)) {
                            Text(
                                stringResource(when {
                                    !conch -> R.string.filo_guide_windows_terminal
                                    platform == 0 -> R.string.conch_guide_windows_terminal
                                    platform == 1 -> R.string.conch_guide_linux_terminal
                                    else -> R.string.conch_guide_termux_terminal
                                }),
                                modifier = Modifier.testTag("$prefix-terminal-note"),
                            )
                        }
                    }
                    add {
                        InstallationStep(2, stringResource(R.string.install_guide_run_command)) {
                            InstallCommand(command, if (conch && platform != 0) "bash" else "powershell", active, ::ownsGuide, "$prefix-command")
                        }
                    }
                    add {
                        InstallationStep(3, stringResource(R.string.install_guide_connect)) {
                            Text(stringResource(if (conch) R.string.conch_guide_finish else R.string.filo_guide_finish))
                        }
                    }
                },
            )
            SettingsGroup(
                title = stringResource(R.string.install_guide_project_address),
                modifier = Modifier.testTag("$prefix-project"),
                items = listOf {
                    InstallSourceLink(
                        if (conch) "https://github.com/newo-ether/conch" else "https://github.com/newo-ether/filo",
                        active, "$prefix-source",
                    )
                },
            )
        }
    }
}

@Composable
private fun InstallationStep(number: Int, title: String, content: @Composable ColumnScope.() -> Unit) {
    SettingsItem(
        headlineContent = { Text(title) },
        leadingContent = {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text(number.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        supportingContent = { Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
    )
}

/** Same block and header copy target as chat Markdown code; stale entries cannot copy. */
@Composable
private fun InstallCommand(command: String, language: String, active: Boolean, owns: () -> Boolean, tag: String) {
    SelectionContainer {
        ChatMarkdownCodeBlock(
            code = command,
            autoWrap = true,
            language = language,
            copyEnabled = active,
            canCopy = owns,
            copyModifier = Modifier.testTag("$tag-copy"),
            modifier = Modifier.testTag(tag).semantics(mergeDescendants = true) {},
        )
    }
}

@Composable
private fun InstallSourceLink(url: String, active: Boolean, tag: String) {
    val uriHandler = LocalUriHandler.current
    val latestActive by rememberUpdatedState(active)
    SettingsItem(
        headlineContent = { Text(stringResource(R.string.about_github)) },
        supportingContent = { Text(url.removePrefix("https://"), style = LocalTextStyle.current.copy(textDirection = rememberContentTextDirection(url))) },
        leadingContent = { Icon(Icons.Rounded.Code, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.Rounded.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.testTag(tag).clickable(enabled = active, role = Role.Button) {
            if (latestActive) runCatching { uriHandler.openUri(url) }
        },
    )
}
