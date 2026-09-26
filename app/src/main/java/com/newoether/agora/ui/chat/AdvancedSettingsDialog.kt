package com.newoether.agora.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.model.ContextBudget
import java.util.Locale

@Composable
fun AdvancedSettingsDialog(
    overrides: ConversationSettings,
    globalDefaults: ConversationSettings,
    onSave: (ConversationSettings) -> Unit,
    onResetToDefaults: () -> Unit,
    onDismiss: () -> Unit
) {
    var contextWindow by remember { mutableStateOf(overrides.contextWindow) }
    var temperature by remember { mutableStateOf(overrides.temperature) }
    var maxTokens by remember { mutableStateOf(overrides.maxTokens) }
    var topP by remember { mutableStateOf(overrides.topP) }
    var frequencyPenalty by remember { mutableStateOf(overrides.frequencyPenalty) }
    var presencePenalty by remember { mutableStateOf(overrides.presencePenalty) }

    fun currentSettings() = overrides.copy(
        contextWindow = contextWindow,
        temperature = temperature,
        maxTokens = maxTokens,
        topP = topP,
        frequencyPenalty = frequencyPenalty,
        presencePenalty = presencePenalty
    )

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.advanced_generation_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                val fmt2: (Float) -> String = { v -> String.format(Locale.US, "%.2f", v) }
                val gDefaults = globalDefaults

                // Context Window
                AdvancedParamRow(
                    label = stringResource(R.string.context_window),
                    value = contextWindow?.let(ContextBudget::normalize),
                    defaultVal = gDefaults.contextWindow?.let(ContextBudget::normalize),
                    presets = ContextBudget.PRESETS,
                    format = ContextBudget::compactLabel,
                    onChange = { contextWindow = it },
                    onReset = { contextWindow = null }
                )
                // Temperature
                AdvancedParamRow(
                    label = stringResource(R.string.gen_temperature),
                    value = temperature,
                    defaultVal = gDefaults.temperature,
                    valueRange = 0f..2f,
                    format = fmt2,
                    onChange = { temperature = it },
                    onReset = { temperature = null }
                )
                // Max Tokens
                val maxTokensPresets =
                    intArrayOf(256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072)
                AdvancedParamRow(
                    label = stringResource(R.string.gen_max_tokens),
                    value = maxTokens,
                    defaultVal = gDefaults.maxTokens,
                    presets = maxTokensPresets,
                    format = { it.toString() },
                    onChange = { maxTokens = it },
                    onReset = { maxTokens = null }
                )
                // Top P
                AdvancedParamRow(
                    label = stringResource(R.string.gen_top_p),
                    value = topP,
                    defaultVal = gDefaults.topP,
                    valueRange = 0f..1f,
                    format = fmt2,
                    onChange = { topP = it },
                    onReset = { topP = null }
                )
                // Frequency Penalty
                AdvancedParamRow(
                    label = stringResource(R.string.gen_frequency_penalty),
                    value = frequencyPenalty,
                    defaultVal = gDefaults.frequencyPenalty,
                    valueRange = -2f..2f,
                    format = fmt2,
                    onChange = { frequencyPenalty = it },
                    onReset = { frequencyPenalty = null }
                )
                // Presence Penalty
                AdvancedParamRow(
                    label = stringResource(R.string.gen_presence_penalty),
                    value = presencePenalty,
                    defaultVal = gDefaults.presencePenalty,
                    valueRange = -2f..2f,
                    format = fmt2,
                    onChange = { presencePenalty = it },
                    onReset = { presencePenalty = null }
                )
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    onResetToDefaults()
                    contextWindow = null; temperature = null; maxTokens = null
                    topP = null; frequencyPenalty = null; presencePenalty = null
                }) { Text(stringResource(R.string.gen_reset)) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.provider_cancel))
                    }
                    TextButton(onClick = { onSave(currentSettings()) }) {
                        Text(stringResource(R.string.provider_save))
                    }
                }
            }
        },
        dismissButton = null
    )
}

/**
 * Parameter row for the Advanced dialog. Always shows value.
 * When value is null, shows the default value passed from global settings.
 * Reset clears the local override (doesn't close dialog).
 */
@Composable
private fun AdvancedParamRow(
    label: String,
    value: Float?,
    defaultVal: Float?,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    val hasDefault = defaultVal != null
    val sliderPos = value ?: defaultVal ?: (valueRange.start + valueRange.endInclusive) / 2f
    val isOverride = value != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (isOverride) {
                Text(
                    text = format(sliderPos),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = stringResource(R.string.gen_reset),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.clickable(onClick = onReset)
                )
            } else if (hasDefault) {
                Text(
                    text = format(sliderPos),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal
                )
            } else {
                Text(
                    text = stringResource(R.string.gen_not_specified),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Slider(
            value = sliderPos,
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AdvancedParamRow(
    label: String,
    value: Int?,
    defaultVal: Int?,
    presets: IntArray,
    format: (Int) -> String,
    onChange: (Int) -> Unit,
    onReset: () -> Unit
) {
    fun toIndex(v: Int) = presets.indices.minByOrNull { kotlin.math.abs(presets[it] - v) } ?: 3
    val hasDefault = defaultVal != null
    val effective = value ?: defaultVal ?: 4096
    val index = toIndex(effective)
    val isOverride = value != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (isOverride) {
                Text(
                    text = format(value!!),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = stringResource(R.string.gen_reset),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.clickable(onClick = onReset)
                )
            } else if (hasDefault) {
                Text(
                    text = format(effective),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal
                )
            } else {
                Text(
                    text = stringResource(R.string.gen_not_specified),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Slider(
            value = index.toFloat(),
            onValueChange = { onChange(presets[it.toInt().coerceIn(0, presets.lastIndex)]) },
            valueRange = 0f..(presets.size - 1).toFloat(),
            steps = presets.size - 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
