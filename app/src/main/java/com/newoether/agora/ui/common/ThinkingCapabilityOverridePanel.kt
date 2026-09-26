package com.newoether.agora.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ModelThinkingCapability
import com.newoether.agora.model.ModelThinkingCapabilityOverride
import com.newoether.agora.model.ThinkingLevels

/**
 * Per-model correction of what the endpoint really accepts for thinking.
 *
 * It belongs next to the thinking controls because that is where a wrong built-in assumption becomes
 * visible: if the offered effort levels or the off switch do not match the model, the user fixes it
 * here and every later request for that model uses the corrected capability.
 *
 * [capability] is the effective capability, so the controls always show what is in force rather than
 * a separate override draft. Any edit stores an explicit override for all four editable fields;
 * resetting removes the entry and restores the documented default.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThinkingCapabilityOverridePanel(
    capability: ModelThinkingCapability,
    hasOverride: Boolean,
    onOverrideChange: (ModelThinkingCapabilityOverride?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    fun emit(
        efforts: List<String> = capability.supportedEfforts,
        canDisable: Boolean = capability.canDisableThinking,
        budget: Boolean = capability.supportsThinkingBudget,
        sampling: Boolean = capability.supportsSamplingParams,
    ) = onOverrideChange(
        ModelThinkingCapabilityOverride(
            canDisableThinking = canDisable,
            supportedEfforts = efforts,
            supportsThinkingBudget = budget,
            supportsSamplingParams = sampling,
        ),
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.thinking_capability_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(
                        if (hasOverride) {
                            R.string.thinking_capability_overridden
                        } else {
                            R.string.thinking_capability_desc
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.thinking_capability_efforts),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                FlowRow(modifier = Modifier.fillMaxWidth()) {
                    ThinkingLevels.effortValues.forEach { effort ->
                        val selected = effort in capability.supportedEfforts
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val next = if (selected) {
                                    capability.supportedEfforts - effort
                                } else {
                                    (capability.supportedEfforts + effort)
                                        .sortedBy { ThinkingLevels.effortValues.indexOf(it) }
                                }
                                emit(efforts = next)
                            },
                            label = { Text(effortLabel(effort)) },
                            modifier = Modifier.padding(end = 8.dp, bottom = 4.dp),
                        )
                    }
                }
                CapabilitySwitchRow(
                    label = stringResource(R.string.thinking_capability_can_disable),
                    checked = capability.canDisableThinking,
                    onCheckedChange = { emit(canDisable = it) },
                )
                CapabilitySwitchRow(
                    label = stringResource(R.string.thinking_capability_budget),
                    checked = capability.supportsThinkingBudget,
                    onCheckedChange = { emit(budget = it) },
                )
                CapabilitySwitchRow(
                    label = stringResource(R.string.thinking_capability_sampling),
                    checked = capability.supportsSamplingParams,
                    onCheckedChange = { emit(sampling = it) },
                )
                if (hasOverride) {
                    TextButton(onClick = { onOverrideChange(null) }) {
                        Text(stringResource(R.string.thinking_capability_reset))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun CapabilitySwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
