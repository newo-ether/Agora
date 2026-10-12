package com.newoether.agora.ui.chat.bottombar

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.common.openAiServiceTierShortLabel
import com.newoether.agora.ui.common.thinkingControlShortLabel
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Switch
import com.newoether.agora.ui.components.Text

/** Ordinary chat's existing rows; visibility and sheet state remain owned by ChatBottomBar. */
@Composable
internal fun ComposerToolsMenuContent(
    activeMenuState: MutableState<String?>,
    showThinkingSheetState: MutableState<Boolean>,
    showOpenAiServiceTierSheetState: MutableState<Boolean>,
    showLowContextMode: Boolean,
    lowContextModeEnabled: Boolean,
    onLowContextModeToggle: (Boolean) -> Unit,
    thinkingEnabled: Boolean,
    thinkingLevel: String,
    thinkingBudgetEnabled: Boolean,
    thinkingBudgetTokens: Int,
    onThinkingToggle: (Boolean) -> Unit,
    thinkingCanDisable: Boolean,
    selectedProvider: String,
    isModelValid: Boolean,
    codeExecutionEnabled: Boolean,
    onCodeExecutionToggle: (Boolean) -> Unit,
    googleSearchEnabled: Boolean,
    onGoogleSearchToggle: (Boolean) -> Unit,
    capabilityControlsEnabled: Boolean,
    openAiServiceTierAvailable: Boolean,
    openAiServiceTierEnabled: Boolean,
    openAiServiceTier: String,
    onOpenAiServiceTierToggle: (Boolean) -> Unit,
    openAiWebSearchAvailable: Boolean,
    openAiWebSearchEnabled: Boolean,
    onOpenAiWebSearchToggle: (Boolean) -> Unit,
    showWebSearch: Boolean,
    webSearchEnabled: Boolean,
    onWebSearchToggle: (Boolean) -> Unit,
    showShell: Boolean,
    shellEnabled: Boolean,
    onShellToggle: (Boolean) -> Unit,
    canCompact: Boolean,
    isCompacting: Boolean,
    onCompactClick: () -> Unit,
    onAdvancedClick: () -> Unit,
) {
    var activeMenu by activeMenuState
    var showThinkingSheet by showThinkingSheetState
    var showOpenAiServiceTierSheet by showOpenAiServiceTierSheetState
    if (showLowContextMode) {
        AgoraDropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.low_context_mode))
                }
            },
            trailingIcon = {
                Switch(
                    checked = lowContextModeEnabled,
                    onCheckedChange = onLowContextModeToggle,
                    modifier = Modifier.scale(0.7f),
                )
            },
            onClick = { onLowContextModeToggle(!lowContextModeEnabled) },
        )
    }
    AgoraDropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.ui.res.painterResource(id = com.newoether.agora.R.drawable.neurology_24), null, modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.thinking))
                    Text(
                        text = thinkingControlShortLabel(
                            thinkingEnabled,
                            thinkingLevel,
                            thinkingBudgetEnabled,
                            thinkingBudgetTokens
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        trailingIcon = {
            Switch(
                checked = thinkingEnabled,
                onCheckedChange = { onThinkingToggle(it) },
                enabled = thinkingCanDisable || !thinkingEnabled,
                modifier = Modifier.scale(0.7f)
            )
        },
        onClick = {
            activeMenu = null
            showThinkingSheet = true
        }
    )
    val isGemini = selectedProvider.equals("google", ignoreCase = true) && isModelValid
    if (isGemini) {
        AgoraDropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, null, modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.code_execution))
                    Spacer(modifier = Modifier.width(10.dp))
                    ProviderBadge("Gemini")
                }
            },
            trailingIcon = {
                Switch(
                    checked = codeExecutionEnabled,
                    onCheckedChange = { onCodeExecutionToggle(it) },
                    enabled = capabilityControlsEnabled,
                    modifier = Modifier.scale(0.7f)
                )
            },
            enabled = capabilityControlsEnabled,
            onClick = { onCodeExecutionToggle(!codeExecutionEnabled) }
        )
        AgoraDropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.provider_google),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(LocalContentColor.current),
                        modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.google_search))
                    Spacer(modifier = Modifier.width(10.dp))
                    ProviderBadge("Gemini")
                }
            },
            trailingIcon = {
                Switch(
                    checked = googleSearchEnabled,
                    onCheckedChange = { onGoogleSearchToggle(it) },
                    enabled = capabilityControlsEnabled,
                    modifier = Modifier.scale(0.7f)
                )
            },
            enabled = capabilityControlsEnabled,
            onClick = { onGoogleSearchToggle(!googleSearchEnabled) }
        )
    }
    if (openAiServiceTierAvailable && isModelValid) {
        AgoraDropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.openai_service_tier_title))
                        Text(
                            text = openAiServiceTierShortLabel(
                                openAiServiceTierEnabled,
                                openAiServiceTier,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            trailingIcon = {
                Switch(
                    checked = openAiServiceTierEnabled,
                    onCheckedChange = onOpenAiServiceTierToggle,
                    enabled = capabilityControlsEnabled,
                    modifier = Modifier.scale(0.7f),
                )
            },
            enabled = capabilityControlsEnabled,
            onClick = {
                activeMenu = null
                showOpenAiServiceTierSheet = true
            },
        )
    }
    if (openAiWebSearchAvailable && isModelValid) {
        NativeSearchMenuItem(
            checked = openAiWebSearchEnabled,
            provider = "OpenAI",
            enabled = capabilityControlsEnabled,
            onCheckedChange = onOpenAiWebSearchToggle,
        )
    }
    if (showWebSearch) {
        AgoraDropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Language, null, modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.web_search))
                }
            },
            trailingIcon = {
                Switch(
                    checked = webSearchEnabled,
                    onCheckedChange = { onWebSearchToggle(it) },
                    enabled = capabilityControlsEnabled,
                    modifier = Modifier.scale(0.7f)
                )
            },
            enabled = capabilityControlsEnabled,
            onClick = { onWebSearchToggle(!webSearchEnabled) }
        )
    }
    if (showShell) {
        AgoraDropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, null, modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.shell_title))
                }
            },
            trailingIcon = {
                Switch(
                    checked = shellEnabled,
                    onCheckedChange = { onShellToggle(it) },
                    enabled = capabilityControlsEnabled,
                    modifier = Modifier.scale(0.7f)
                )
            },
            enabled = capabilityControlsEnabled,
            onClick = { onShellToggle(!shellEnabled) }
        )
    }
    AgoraDropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Compress, null, modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.context_compact))
            }
        },
        enabled = canCompact && !isCompacting,
        onClick = { activeMenu = null; onCompactClick() },
    )
    AgoraDropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tune, null, modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.advanced_settings))
            }
        },
        // Unlike the toggle rows, this opens a dialog — collapse the menu first.
        onClick = { activeMenu = null; onAdvancedClick() }
    )
}
