package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.data.RegistrySkillEntry
import com.newoether.agora.data.SkillMarketplace
import com.newoether.agora.data.curatedSkillMarketplaces
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSkillMarketplacePage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    var selectedMarketplace by remember { mutableStateOf<SkillMarketplace?>(null) }
    var loadedSkills by remember { mutableStateOf<List<RegistrySkillEntry>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var installInFlight by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedMarketplace) {
        val marketplace = selectedMarketplace
        if (marketplace != null) {
            isLoading = true
            errorMessage = null
            loadedSkills = null
            try {
                val result = withContext(Dispatchers.IO) {
                    viewModel.skillManager.browseMarketplace(marketplace)
                }
                result.onSuccess { loadedSkills = it }
                    .onFailure { errorMessage = it.localizedMessage ?: "Failed to load marketplace" }
            } catch (e: Exception) {
                errorMessage = e.localizedMessage ?: "Failed to load marketplace"
            } finally {
                isLoading = false
            }
        }
    }

    CollapsingSettingsScaffold(
        title = if (selectedMarketplace == null) "Marketplaces" else "Available Skills",
        onBack = {
            if (selectedMarketplace != null) {
                selectedMarketplace = null
                loadedSkills = null
            } else {
                onBack()
            }
        },
        scrollState = rememberScrollState(),
    ) {
        SettingsGroupColumn {
            if (selectedMarketplace == null) {
                SettingsGroup(
                    title = "Curated Marketplaces",
                    items = curatedSkillMarketplaces.map { marketplace ->
                        {
                            SettingsItem(
                                headlineContent = {
                                    Text(
                                        marketplace.name,
                                        fontWeight = FontWeight.Medium,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        "${marketplace.owner}/${marketplace.repo}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Store,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedMarketplace = marketplace
                                },
                            )
                        }
                    }
                )
            } else {
                SettingsGroup(
                    title = "Installable Skills",
                    items = buildList {
                        if (isLoading) {
                            add {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 64.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        } else if (errorMessage != null) {
                            add {
                                SettingsItem(
                                    headlineContent = {
                                        Text(
                                            "Error Loading Skills",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            errorMessage!!,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                )
                            }
                        } else if (loadedSkills?.isEmpty() == true) {
                            add {
                                SettingsItem(
                                    headlineContent = {
                                        Text(
                                            "No Skills Found",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                )
                            }
                        } else {
                            loadedSkills?.forEach { skill ->
                                add {
                                    SettingsItem(
                                        headlineContent = {
                                            Text(
                                                skill.id,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        },
                                        supportingContent = {
                                            Text(
                                                skill.description,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        leadingContent = {
                                            Icon(
                                                Icons.Default.CloudDownload,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth().clickable(enabled = installInFlight == null) {
                                            installInFlight = skill.id
                                            scope.launch {
                                                try {
                                                    withContext(Dispatchers.IO) {
                                                        viewModel.skillManager.installFromRegistryEntry(skill)
                                                            .getOrThrow()
                                                    }
                                                    viewModel.emitSnackbar("Installed ${skill.id}")
                                                } catch (e: Exception) {
                                                    viewModel.emitSnackbar("Failed to install ${skill.id}: ${e.localizedMessage}")
                                                } finally {
                                                    installInFlight = null
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}
