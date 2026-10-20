package com.newoether.agora.ui.settings

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.modelAliasDisplayName
import com.newoether.agora.data.modelApiDisplayName
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.components.providerIcon
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.util.Constants
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.motion.AnimatedVisibility

internal fun LazyListScope.modelProviderGroups(
    keyPrefix: String,
    groups: List<ModelProviderGroup>,
    firstHeaderStartsSection: Boolean,
    lastGroupClosesSection: Boolean,
    allowSpatialTransitions: Boolean,
    searchActive: Boolean,
    enabledModels: Set<String>,
    modelAliases: Map<String, String>,
    customProviders: List<CustomProviderConfig>,
    expandedProviders: MutableMap<String, MutableTransitionState<Boolean>>,
    modelBlockHeights: MutableMap<String, Float>,
    onAliasClick: ((String) -> Unit)?,
    onDetailsClick: ((String) -> Unit)?,
    onEnabledChange: (String, Boolean) -> Unit,
) {
    groups.forEachIndexed { providerIndex, group ->
        val providerName = group.providerName
        val displayProviderName = providerDisplayName(providerName, customProviders)
        val models = group.models
        val providerStateKey = "$keyPrefix:$providerName"
        val transitionState = expandedProviders.getOrPut(providerStateKey) {
            MutableTransitionState(false)
        }
        val isFirstProvider = providerIndex == 0
        val isLastProvider = providerIndex == groups.lastIndex
        val topRadius = if (isFirstProvider && firstHeaderStartsSection) 24f else 5f
        val collapsedBottomRadius =
            if (isLastProvider && lastGroupClosesSection) 24f else 5f

        item(key = "${keyPrefix}_header_$providerName") {
            val isExpanded = transitionState.targetState
            val currentHeight = modelBlockHeights[providerStateKey] ?: 0f
            val collapsedRatio =
                (1f - currentHeight / collapsedBottomRadius).coerceIn(0f, 1f)
            val bottomRadius = (collapsedBottomRadius * collapsedRatio).dp
            val headerShape = RoundedCornerShape(
                topStart = topRadius.dp,
                topEnd = topRadius.dp,
                bottomStart = bottomRadius,
                bottomEnd = bottomRadius,
            )

            CardSurface(
                shape = headerShape,
                addTopGap = !(isFirstProvider && firstHeaderStartsSection),
            ) {
                val headerIconRes = providerIcon(displayProviderName)
                val isLocalHeader =
                    displayProviderName.equals(Constants.PROVIDER_LOCAL, ignoreCase = true)
                SettingsItem(
                    headlineContent = { Text(displayProviderName) },
                    supportingContent = {
                        val enabledCount = models.count { it in enabledModels }
                        Text(
                            stringResource(
                                if (searchActive) {
                                    R.string.models_search_count_status
                                } else {
                                    R.string.models_count_status
                                },
                                enabledCount,
                                models.size,
                            )
                        )
                    },
                    leadingContent = {
                        when {
                            isLocalHeader -> Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            headerIconRes != 0 -> Icon(
                                painterResource(headerIconRes),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            else -> Icon(
                                Icons.Default.Cloud,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    },
                    trailingContent = {
                        Icon(
                            if (isExpanded) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable {
                        transitionState.targetState = !transitionState.targetState
                    },
                )
            }
        }

        item(key = "${keyPrefix}_models_$providerName") {
            val density = LocalDensity.current
            key(transitionState) {
                AnimatedVisibility(
                    visibleState = transitionState,
                    enter = if (allowSpatialTransitions) {
                        expandVertically()
                    } else {
                        fadeIn()
                    },
                    exit = if (allowSpatialTransitions) {
                        shrinkVertically()
                    } else {
                        fadeOut()
                    },
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        modelBlockHeights[providerStateKey] =
                            coordinates.size.height / density.density
                    },
                ) {
                    Column {
                        models.forEachIndexed { modelIndex, model ->
                            val isLastModel = modelIndex == models.lastIndex
                            val modelShape = when {
                                isLastModel && isLastProvider && lastGroupClosesSection ->
                                    FlatToBottom
                                isLastModel -> FiveBottom
                                else -> FlatShape
                            }
                            CardSurface(shape = modelShape) {
                                val isEnabled = model in enabledModels
                                val displayName = modelAliasDisplayName(
                                    model,
                                    modelAliases,
                                    customProviders,
                                )
                                SettingsItem(
                                    headlineContent = { Text(displayName) },
                                    supportingContent = {
                                        Text(modelApiDisplayName(model, customProviders))
                                    },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (onDetailsClick != null) {
                                                IconButton(
                                                    onClick = { onDetailsClick(model) },
                                                ) {
                                                    Icon(
                                                        Icons.Default.Edit,
                                                        contentDescription =
                                                            stringResource(
                                                                R.string.models_custom_details
                                                            ),
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                }
                                            } else if (onAliasClick != null) {
                                                IconButton(
                                                    onClick = { onAliasClick(model) },
                                                ) {
                                                    Icon(
                                                        Icons.Default.Edit,
                                                        contentDescription =
                                                            stringResource(
                                                                R.string.models_rename
                                                            ),
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                }
                                            }
                                            Checkbox(
                                                checked = isEnabled,
                                                onCheckedChange = {
                                                    onEnabledChange(model, it)
                                                },
                                            )
                                        }
                                    },
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
