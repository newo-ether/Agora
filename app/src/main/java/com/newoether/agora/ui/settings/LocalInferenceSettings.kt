package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.LOCAL_INFERENCE_THREAD_PRESETS
import com.newoether.agora.data.LOCAL_KV_CACHE_TYPES
import com.newoether.agora.ui.common.PersistedSliderFeedbackGate
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun LocalInferenceThreadsSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    val presets = LOCAL_INFERENCE_THREAD_PRESETS
    fun indexOfNearest(candidate: Int): Int = presets.indices.minByOrNull {
        abs(presets[it] - candidate)
    } ?: 0

    val sliderGate = remember {
        PersistedSliderFeedbackGate(
            initialPersisted = value,
            toDisplay = { indexOfNearest(it).toFloat() },
        )
    }
    LaunchedEffect(value) {
        sliderGate.reconcile(value)
    }
    val sliderPosition = sliderGate.displayed
    val selectedIndex = sliderPosition.roundToInt().coerceIn(presets.indices)
    val selectedThreads = presets[selectedIndex]

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Rounded.Speed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.local_inference_threads),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$selectedThreads",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = stringResource(R.string.local_inference_threads_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Slider(
                    value = sliderPosition,
                    onValueChange = sliderGate::updateFromGesture,
                    onValueChangeFinished = {
                        val committedIndex = sliderPosition.roundToInt()
                            .coerceIn(presets.indices)
                        val committedValue = presets[committedIndex]
                        if (committedValue != value) {
                            sliderGate.expectPersisted(
                                committedValue,
                                committedIndex.toFloat(),
                            )
                            onValueChange(committedValue)
                        } else {
                            sliderGate.settleWithoutWrite(value, committedIndex.toFloat())
                        }
                    },
                    valueRange = 0f..presets.lastIndex.toFloat(),
                    steps = presets.size - 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun LocalKvCacheTypeItem(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    SettingsItem(
        modifier = Modifier.clickable { showMenu = true },
        headlineContent = { Text(stringResource(R.string.local_kv_cache_type)) },
        supportingContent = { Text(stringResource(R.string.local_kv_cache_type_desc)) },
        leadingContent = {
            Icon(
                Icons.Rounded.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Box {
                Text(
                    text = value.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(96.dp).padding(end = 4.dp),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AgoraDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 16.dp,
                ) {
                    LOCAL_KV_CACHE_TYPES.forEach { type ->
                        AgoraDropdownMenuItem(
                            text = { Text(type.uppercase()) },
                            leadingIcon = {
                                if (type == value) {
                                    Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            onClick = {
                                onValueChange(type)
                                showMenu = false
                            },
                        )
                    }
                }
            }
        },
    )
}
