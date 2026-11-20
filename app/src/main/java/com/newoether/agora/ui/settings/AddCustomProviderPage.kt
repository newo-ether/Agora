package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.CustomProviderNamePolicy
import com.newoether.agora.ui.components.CustomEndpointProtocolSelector
import com.newoether.agora.ui.components.rememberUnsavedChangesBack
import com.newoether.agora.viewmodel.ChatViewModel

@Composable
internal fun AddCustomProviderPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit = onBack,
) {
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var protocol by rememberSaveable { mutableStateOf(CustomEndpointProtocol.OPENAI) }
    var nameError by rememberSaveable { mutableStateOf(false) }
    var urlError by rememberSaveable { mutableStateOf(false) }
    val guardedBack = rememberUnsavedChangesBack(
        hasUnsavedChanges = name.isNotEmpty() || baseUrl.isNotEmpty() ||
            protocol != CustomEndpointProtocol.OPENAI,
        onBack = onBack,
    )
    BackHandler { guardedBack() }

    fun save() {
        val trimmedName = name.trim()
        val trimmedUrl = baseUrl.trim()
        nameError = CustomProviderNamePolicy.hasConflict(
            name = trimmedName,
            existingNames = viewModel.settings.customProviders.value.map { it.name },
        )
        urlError = trimmedUrl.isBlank()
        if (nameError || urlError) return
        viewModel.customModelConfiguration.addProvider(trimmedName, trimmedUrl, protocol)
        onSaved()
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.provider_custom_entry),
        onBack = guardedBack,
        scrollState = rememberSettingsScrollState(),
        actions = {
            IconButton(onClick = ::save) {
                Icon(
                    Icons.Default.Save,
                    contentDescription = stringResource(R.string.save),
                )
            }
        },
        floatingActionButton = { if (showDocFab) DocumentationFab("provider.md") },
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.embedding_general),
                items = listOf {
                    SettingsIconContent(Icons.Default.Label) {
                        McpLabeledField(
                            label = stringResource(R.string.custom_provider_name_label),
                            value = name,
                            onValueChange = { name = it; nameError = false },
                            isError = nameError,
                            supportingText = if (nameError) {
                                stringResource(R.string.custom_provider_name_error)
                            } else {
                                null
                            },
                        )
                    }
                },
            )
            SettingsGroup(
                title = stringResource(R.string.provider_connection),
                items = listOf(
                    {
                        SettingsIconContent(Icons.Default.Cloud) {
                            val protocolLabel = stringResource(R.string.custom_provider_protocol_label)
                            Text(
                                protocolLabel,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(8.dp))
                            CustomEndpointProtocolSelector(
                                selected = protocol,
                                onSelected = { protocol = it },
                                modifier = Modifier.semantics { contentDescription = protocolLabel },
                            )
                        }
                    },
                    {
                        SettingsIconContent(Icons.Default.Link) {
                            McpLabeledField(
                                label = stringResource(R.string.provider_base_url),
                                value = baseUrl,
                                onValueChange = { baseUrl = it; urlError = false },
                                isError = urlError,
                                supportingText = if (urlError) {
                                    stringResource(R.string.custom_provider_url_error)
                                } else {
                                    null
                                },
                                keyboardType = KeyboardType.Uri,
                            )
                        }
                    },
                ),
            )
        }
        if (showDocFab) Spacer(Modifier.height(80.dp))
    }
}
