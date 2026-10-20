package com.newoether.agora.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.OpenAiServiceTiers
import kotlin.math.roundToInt
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Switch
import com.newoether.agora.ui.components.Text

@Composable
fun OpenAiServiceTierControlPanel(
    enabled: Boolean,
    tier: String,
    onEnabledChange: (Boolean) -> Unit,
    onTierChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    showEnabledToggle: Boolean = true,
    availableTiers: List<String>? = null,
    tierLabels: Map<String, String> = emptyMap(),
    controlsEnabled: Boolean = true,
    settingsRevision: Long = 0,
    knownSelection: Boolean = true,
) {
    val normalizedTier = if (availableTiers == null) OpenAiServiceTiers.normalize(tier) else tier
    val tiers = availableTiers ?: OpenAiServiceTiers.values
    val sliderEnabled = (enabled || !showEnabledToggle) && controlsEnabled &&
        (tiers.size > 1 || tiers.size == 1 && !knownSelection)
    val tierGate = remember(tiers, settingsRevision) {
        PersistedSliderFeedbackGate(
            initialPersisted = normalizedTier,
            toDisplay = { persisted ->
                tiers.indexOf(persisted).coerceAtLeast(0).toFloat()
            },
        )
    }
    LaunchedEffect(normalizedTier, tierGate) { tierGate.reconcile(normalizedTier) }
    var choosingTier by remember(knownSelection, normalizedTier, tiers, settingsRevision) { mutableStateOf(false) }
    val showSelection = knownSelection || choosingTier
    val sliderPosition = tierGate.displayed

    Column(modifier = modifier.fillMaxWidth()) {
        if (showHeader) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.openai_service_tier_title),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.openai_service_tier_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (showEnabledToggle) {
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = enabled,
                        onCheckedChange = onEnabledChange,
                        enabled = controlsEnabled && (enabled || tiers.isNotEmpty()),
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled || !showEnabledToggle) 1f else 0.38f),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.Speed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.openai_service_tier_title),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (!showSelection) "" else tiers.getOrNull(sliderPosition.roundToInt()).let { selected ->
                            tierLabels[selected] ?: if (availableTiers == null) serviceTierLabel(selected.orEmpty())
                            else selected.orEmpty()
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = stringResource(R.string.openai_service_tier_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Slider(
                    value = sliderPosition,
                    onValueChange = {
                        if (sliderEnabled) {
                            choosingTier = true
                            tierGate.updateFromGesture(it.coerceIn(0f, tiers.lastIndex.toFloat()))
                        }
                    },
                    onValueChangeFinished = {
                        if (sliderEnabled) {
                            val index = tierGate.displayed
                                .roundToInt()
                                .coerceIn(tiers.indices)
                            val selectedTier = tiers[index]
                            if (selectedTier == normalizedTier) {
                                tierGate.settleWithoutWrite(normalizedTier, index.toFloat())
                            } else {
                                tierGate.expectPersisted(selectedTier, index.toFloat())
                            }
                            if (availableTiers == null) onEnabledChange(true)
                            if (selectedTier != normalizedTier || !knownSelection) onTierChange(selectedTier)
                        }
                    },
                    valueRange = 0f..tiers.lastIndex.coerceAtLeast(1).toFloat(),
                    steps = (tiers.size - 2).coerceAtLeast(0),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .semantics {
                            if (!showSelection) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        },
                    colors = if (showSelection) SliderDefaults.colors() else SliderDefaults.colors(
                        thumbColor = Color.Transparent, disabledThumbColor = Color.Transparent,
                        activeTrackColor = MaterialTheme.colorScheme.secondaryContainer,
                        activeTickColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    enabled = sliderEnabled,
                )
            }
        }
    }
}

@Composable
fun openAiServiceTierShortLabel(enabled: Boolean, tier: String, nativeLabel: String? = null): String =
    if (enabled) {
        nativeLabel ?: serviceTierLabel(OpenAiServiceTiers.normalize(tier))
    } else {
        stringResource(R.string.openai_service_tier_off)
    }

@Composable
private fun serviceTierLabel(tier: String): String = when (tier) {
    OpenAiServiceTiers.DEFAULT -> stringResource(R.string.openai_service_tier_default)
    OpenAiServiceTiers.FLEX -> stringResource(R.string.openai_service_tier_flex)
    OpenAiServiceTiers.FAST -> stringResource(R.string.openai_service_tier_fast)
    OpenAiServiceTiers.ULTRAFAST -> stringResource(R.string.openai_service_tier_ultrafast)
    else -> stringResource(R.string.openai_service_tier_auto)
}
