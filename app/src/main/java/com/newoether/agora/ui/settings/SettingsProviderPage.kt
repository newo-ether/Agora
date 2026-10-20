package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.components.displayName
import com.newoether.agora.ui.components.providerIcon
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.TextButton
import com.newoether.agora.ui.components.Surface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsProviderPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val apiKeys by viewModel.settings.apiKeys.collectAsState()
    val providerBaseUrls by viewModel.settings.providerBaseUrls.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val localChatModels by viewModel.settings.localChatModels.collectAsState()

    var selectedProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddCustomPage by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberSettingsScrollState()

    BackHandler(enabled = !showAddCustomPage) {
        if (selectedProvider != null) {
            selectedProvider = null
        } else {
            onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GuardedAnimatedContent(
            targetState = selectedProvider to showAddCustomPage,
            forward = selectedProvider != null || showAddCustomPage,
        ) { (provider, addCustomProvider) ->
            if (addCustomProvider) {
                SettingsSecondaryPane {
                    AddCustomProviderPage(
                        viewModel = viewModel,
                        onBack = { showAddCustomPage = false },
                    )
                }
            } else if (provider != null) {
                SettingsSecondaryPane {
                    SettingsProviderDetailPage(
                        providerName = provider,
                        viewModel = viewModel,
                        onBack = { selectedProvider = null },
                    )
                }
            } else {
                val builtInNames = listOf(Constants.PROVIDER_GOOGLE, Constants.PROVIDER_OPENAI, Constants.PROVIDER_ANTHROPIC, Constants.PROVIDER_DEEPSEEK, Constants.PROVIDER_QWEN, Constants.PROVIDER_GROQ, Constants.PROVIDER_OLLAMA, Constants.PROVIDER_OPEN_ROUTER, Constants.PROVIDER_OPENCODE_GO)

                @Composable
                fun isConfigured(name: String): Boolean = when (name) {
                    Constants.PROVIDER_LOCAL -> localChatModels.isNotEmpty()
                    else -> {
                        val isCustom = customProviders.any { it.name == name }
                        if (isCustom || name == Constants.PROVIDER_OLLAMA) !providerBaseUrls[name].isNullOrBlank()
                        else apiKeys.any { it.provider == name }
                    }
                }

                CollapsingSettingsScaffold(
                    title = stringResource(R.string.settings_provider),
                    onBack = onBack,
                    scrollState = scrollState,
                ) {
                        SettingsGroupColumn {
                            SettingsGroup(title = stringResource(R.string.provider_built_in), items = builtInNames.map { name ->
                                @Composable {
                                    val configured = isConfigured(name)
                                    SettingsItem(
                                        headlineContent = { Text(name) },
                                        supportingContent = {
                                            Text(
                                                when {
                                                    name == Constants.PROVIDER_OLLAMA -> providerBaseUrls[name]?.takeIf { it.isNotBlank() } ?: stringResource(R.string.not_configured)
                                                    isConfigured(name) -> stringResource(R.string.provider_keys_summary, apiKeys.count { it.provider == name })
                                                    else -> stringResource(R.string.not_configured)
                                                }
                                            )
                                        },
                                        leadingContent = {
                                            val iconRes = providerIcon(name)
                                            val tint = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            if (iconRes != 0) {
                                                Icon(painterResource(iconRes), null, tint = tint, modifier = Modifier.size(24.dp))
                                            } else {
                                                Icon(Icons.Default.Cloud, null, tint = tint, modifier = Modifier.size(24.dp))
                                            }
                                        },
                                        modifier = Modifier.clickable { selectedProvider = name }
                                    )
                                }
                            })

                            SettingsGroup(title = stringResource(R.string.custom_provider_section), items = buildList {
                                if (customProviders.isEmpty()) {
                                    add {
                                        SettingsItem(
                                            headlineContent = { Text(stringResource(R.string.custom_provider_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                            leadingContent = { Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(24.dp)) },
                                            modifier = Modifier.heightIn(min = 64.dp)
                                        )
                                    }
                                }
                                customProviders.forEach { config ->
                                    add {
                                        val configured = !providerBaseUrls[config.name].isNullOrBlank()
                                        val endpoint = providerBaseUrls[config.name]
                                            ?.takeIf { it.isNotBlank() }
                                            ?: stringResource(R.string.not_configured)
                                        SettingsItem(
                                            headlineContent = { Text(config.name) },
                                            supportingContent = {
                                                Text(endpoint)
                                            },
                                            leadingContent = { Icon(Icons.Default.Cloud, null, tint = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(24.dp)) },
                                            trailingContent = {
                                                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) { Text(config.protocol.displayName(), modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                                            },
                                            modifier = Modifier.clickable { selectedProvider = config.name }
                                        )
                                    }
                                }
                                add {
                                    SettingsAddItem(
                                        label = stringResource(R.string.custom_provider_add_title),
                                        onClick = { showAddCustomPage = true },
                                    )
                                }
                            })

                            val localConfigured = localChatModels.isNotEmpty()
                            SettingsGroup(title = stringResource(R.string.local_models_title), items = listOf {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.local_title)) },
                                    supportingContent = { Text(if (localConfigured) stringResource(R.string.provider_local_models_summary, localChatModels.size) else stringResource(R.string.not_configured)) },
                                    leadingContent = { Icon(Icons.Default.AutoAwesome, null, tint = if (localConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                                    modifier = Modifier.clickable { selectedProvider = Constants.PROVIDER_LOCAL }
                                )
                            })
                        }

                }
            }
        }
    }
}
