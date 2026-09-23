package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.CustomProviderNamePolicy
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.components.CustomEndpointProtocolSelector
import com.newoether.agora.ui.components.displayName
import com.newoether.agora.ui.components.providerIcon
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsProviderPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val apiKeys by viewModel.settings.apiKeys.collectAsState()
    val providerBaseUrls by viewModel.settings.providerBaseUrls.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val localChatModels by viewModel.settings.localChatModels.collectAsState()

    var selectedProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddCustomDialog by remember { mutableStateOf(false) }
    val scrollState = rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) { androidx.compose.foundation.ScrollState(0) }

    BackHandler {
        if (selectedProvider != null) {
            selectedProvider = null
        } else {
            onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GuardedAnimatedContent(
            targetState = selectedProvider,
            forward = selectedProvider != null
        ) { provider ->
            if (provider != null) {
                SettingsSecondaryPane {
                    SettingsProviderDetailPage(
                        providerName = provider,
                        viewModel = viewModel,
                        onBack = { selectedProvider = null },
                    )
                }
            } else {
                val builtInNames = listOf(Constants.PROVIDER_GOOGLE, Constants.PROVIDER_OPENAI, Constants.PROVIDER_ANTHROPIC, Constants.PROVIDER_DEEPSEEK, Constants.PROVIDER_QWEN, Constants.PROVIDER_GROQ, Constants.PROVIDER_OLLAMA, Constants.PROVIDER_OPEN_ROUTER, Constants.PROVIDER_OPENCODE_GO, Constants.PROVIDER_REQUESTY)

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
                                        onClick = { showAddCustomDialog = true },
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

                // Add Custom Provider Dialog
                if (showAddCustomDialog) {
                    var customName by remember { mutableStateOf("") }; var customBaseUrl by remember { mutableStateOf("") }
                    var customProtocol by remember { mutableStateOf(CustomEndpointProtocol.OPENAI) }
                    var nameError by remember { mutableStateOf(false) }; var urlError by remember { mutableStateOf(false) }
                    AlertDialog(
                        modifier = Modifier.clearFocusOnTap(),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        onDismissRequest = { showAddCustomDialog = false },
                        title = {
                            Text(
                                stringResource(R.string.custom_provider_add_title),
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        text = {
                            CustomProviderDialogContent(
                                name = customName,
                                onNameChange = { customName = it; nameError = false },
                                nameError = nameError,
                                protocol = customProtocol,
                                onProtocolChange = { customProtocol = it },
                                baseUrl = customBaseUrl,
                                onBaseUrlChange = { customBaseUrl = it; urlError = false },
                                urlError = urlError,
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val tn = customName.trim()
                                val tu = customBaseUrl.trim()
                                nameError = CustomProviderNamePolicy.hasConflict(
                                    name = tn,
                                    existingNames = customProviders.map { it.name },
                                )
                                urlError = tu.isBlank()
                                if (!nameError && !urlError) {
                                    viewModel.customModelConfiguration.addProvider(tn, tu, customProtocol)
                                    showAddCustomDialog = false
                                }
                            }) {
                                Text(stringResource(R.string.custom_provider_add))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAddCustomDialog = false }) {
                                Text(stringResource(R.string.cancel))
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomProviderDialogContent(
    name: String,
    onNameChange: (String) -> Unit,
    nameError: Boolean,
    protocol: CustomEndpointProtocol,
    onProtocolChange: (CustomEndpointProtocol) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    urlError: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val protocolLabel = stringResource(R.string.custom_provider_protocol_label)
        CustomEndpointProtocolSelector(
            selected = protocol,
            onSelected = onProtocolChange,
            modifier = Modifier.semantics {
                contentDescription = protocolLabel
            },
        )
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.custom_provider_name_label)) },
            isError = nameError,
            supportingText = if (nameError) {
                { Text(stringResource(R.string.custom_provider_name_error)) }
            } else {
                null
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            label = { Text(stringResource(R.string.provider_base_url)) },
            isError = urlError,
            supportingText = if (urlError) {
                { Text(stringResource(R.string.custom_provider_url_error)) }
            } else {
                null
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}
