package com.newoether.agora.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ImageQualitySettings
import com.newoether.agora.ui.common.PersistedSliderFeedbackGate
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import kotlin.math.roundToInt

/** The seven selected UI stops; the processing policy still accepts legacy integer values. */
private val imageLongEdgePresets = intArrayOf(128, 256, 512, 1024, 2048, 4096, 8192)

/** Immediate-save controls for the shared processing boundary, independent of image generation. */
@Composable
internal fun SettingsImageQualityGroup(
    quality: ImageQualitySettings,
    onModeChange: (String) -> Unit,
    onMaxLongEdgeChange: (Int) -> Unit,
    onJpegQualityChange: (Int) -> Unit,
) {
    val modes = listOf(
        ImageQualitySettings.LIGHT to stringResource(R.string.image_quality_light),
        ImageQualitySettings.BALANCED to stringResource(R.string.image_quality_balanced),
        ImageQualitySettings.HIGH to stringResource(R.string.image_quality_high),
        ImageQualitySettings.CUSTOM to stringResource(R.string.image_quality_custom),
    )
    SettingsGroup(
        title = stringResource(R.string.image_quality_title),
        items = buildList {
            add {
                var expanded by remember { mutableStateOf(false) }
                val label = modes.first { it.first == quality.mode }.second
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.image_quality_mode)) },
                    supportingContent = {
                        Text(stringResource(
                            R.string.image_quality_mode_desc, quality.maxLongEdge, quality.jpegQuality,
                        ))
                    },
                    leadingContent = {
                        Icon(Icons.Default.Image, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Box {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(96.dp).padding(end = 4.dp),
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            AgoraDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 16.dp,
                            ) {
                                modes.forEach { (mode, title) ->
                                    AgoraDropdownMenuItem(
                                        text = { Text(title) },
                                        leadingIcon = {
                                            if (quality.mode == mode) {
                                                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                            }
                                        },
                                        onClick = { expanded = false; onModeChange(mode) },
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("image-quality-mode").clickable { expanded = true },
                )
            }
            if (quality.mode == ImageQualitySettings.CUSTOM) {
                add {
                    ImageQualitySlider(
                        value = quality.customMaxLongEdge,
                        range = ImageQualitySettings.MIN_LONG_EDGE..ImageQualitySettings.MAX_LONG_EDGE,
                        presets = imageLongEdgePresets,
                        title = R.string.image_quality_long_edge,
                        description = R.string.image_quality_long_edge_desc,
                        icon = Icons.Default.PhotoSizeSelectLarge,
                        format = { stringResource(R.string.image_quality_pixels, it) },
                        onValueChange = onMaxLongEdgeChange,
                        modifier = Modifier.testTag("image-quality-long-edge"),
                    )
                }
                add {
                    ImageQualitySlider(
                        value = quality.customJpegQuality,
                        range = ImageQualitySettings.MIN_JPEG_QUALITY..ImageQualitySettings.MAX_JPEG_QUALITY,
                        title = R.string.image_quality_jpeg,
                        description = R.string.image_quality_jpeg_desc,
                        icon = Icons.Default.Tune,
                        format = { it.toString() },
                        onValueChange = onJpegQualityChange,
                        modifier = Modifier.testTag("image-quality-jpeg"),
                    )
                }
            }
        },
    )
}

@Composable
private fun ImageQualitySlider(
    value: Int,
    range: IntRange,
    @StringRes title: Int,
    @StringRes description: Int,
    icon: ImageVector,
    format: @Composable (Int) -> String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    presets: IntArray? = null,
) {
    val persisted = value.coerceIn(range)
    val gate = remember(range, presets) {
        PersistedSliderFeedbackGate(initialPersisted = persisted, toDisplay = { it })
    }
    LaunchedEffect(persisted) { gate.reconcile(persisted) }
    val selected = gate.displayed.coerceIn(range)
    val sliderValue = presets?.indices?.minByOrNull { kotlin.math.abs(presets[it] - selected) }
        ?.toFloat() ?: selected.toFloat()
    val titleText = stringResource(title)
    val valueText = format(selected)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        titleText,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        valueText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    stringResource(description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { position ->
                        gate.updateFromGesture(presets?.let { it[position.roundToInt().coerceIn(it.indices)] }
                            ?: position.roundToInt().coerceIn(range))
                    },
                    onValueChangeFinished = {
                        val committed = gate.displayed.coerceIn(range)
                        if (committed != persisted) {
                            gate.expectPersisted(committed)
                            onValueChange(committed)
                        } else {
                            gate.settleWithoutWrite(persisted)
                        }
                    },
                    valueRange = if (presets != null) 0f..presets.lastIndex.toFloat()
                        else range.first.toFloat()..range.last.toFloat(),
                    steps = presets?.let { it.size - 2 } ?: 0,
                    modifier = modifier.fillMaxWidth().padding(top = 8.dp)
                        .semantics { contentDescription = titleText; stateDescription = valueText },
                )
            }
        }
    }
}
