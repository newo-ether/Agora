package com.newoether.agora.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.BUILT_IN_REMOTE_PROVIDERS
import com.newoether.agora.ui.components.providerIcon
import com.newoether.agora.util.noOpBringIntoView

/**
 * Full-screen Provider picker. The Custom group opens the custom Provider form; the Built-in
 * group follows the search query, and built-ins already in the list are disabled. Every row has a
 * supporting line so all rows share one height; unlisted built-ins show their retained status.
 */
@Composable
internal fun ProviderAddPage(
    listed: List<String>,
    apiKeys: List<ApiKeyEntry>,
    baseUrls: Map<String, String>,
    onBack: () -> Unit,
    onAddCustom: () -> Unit,
    onPickBuiltIn: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    BackHandler { onBack() }
    val matches = BUILT_IN_REMOTE_PROVIDERS.filter { it.contains(query.trim(), ignoreCase = true) }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.custom_provider_add_title),
        onBack = onBack,
        scrollState = rememberSettingsScrollState(),
    ) {
        SettingsGroupColumn {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).noOpBringIntoView()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.provider_search_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = if (query.isNotEmpty()) {
                            {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.models_clear_search))
                                }
                            }
                        } else {
                            null
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            SettingsGroup(
                title = stringResource(R.string.custom_provider_section),
                items = listOf {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.provider_custom_entry)) },
                        supportingContent = { Text(stringResource(R.string.provider_custom_entry_desc)) },
                        leadingContent = {
                            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        },
                        modifier = Modifier.clickable(onClick = onAddCustom),
                    )
                },
            )
            SettingsGroup(
                title = stringResource(R.string.provider_built_in),
                items = if (matches.isEmpty()) {
                    listOf {
                        SettingsItem(
                            headlineContent = {
                                Text(stringResource(R.string.provider_add_no_match), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            modifier = Modifier.heightIn(min = 64.dp),
                        )
                    }
                } else {
                    matches.map { name ->
                        @Composable {
                            val added = name in listed
                            val contentAlpha = if (added) 0.38f else 1f
                            val summary = if (added) {
                                stringResource(R.string.provider_added)
                            } else {
                                providerStatus(name, isUrlConfigured = false, apiKeys, baseUrls).summary
                            }
                            SettingsItem(
                                headlineContent = {
                                    Text(name, color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha))
                                },
                                supportingContent = {
                                    Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha))
                                },
                                leadingContent = {
                                    val tint = if (added) {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                    val iconRes = providerIcon(name)
                                    if (iconRes != 0) Icon(painterResource(iconRes), null, tint = tint, modifier = Modifier.size(24.dp))
                                    else Icon(Icons.Default.Cloud, null, tint = tint, modifier = Modifier.size(24.dp))
                                },
                                modifier = Modifier.clickable(enabled = !added) { onPickBuiltIn(name) },
                            )
                        }
                    }
                },
            )
        }
    }
}
