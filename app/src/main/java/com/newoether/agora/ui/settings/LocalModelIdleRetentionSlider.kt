package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.LOCAL_MODEL_IDLE_RETENTION_PRESETS
import com.newoether.agora.ui.common.PersistedSliderFeedbackGate
import kotlin.math.abs
import kotlin.math.roundToInt
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Text

@Composable
internal fun LocalModelIdleRetentionSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    val presets = LOCAL_MODEL_IDLE_RETENTION_PRESETS
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
    val selectedMinutes = presets[selectedIndex]
    val valueLabel = if (selectedMinutes == 0) {
        stringResource(R.string.local_model_idle_retention_immediate)
    } else {
        stringResource(R.string.local_model_idle_retention_minutes, selectedMinutes)
    }

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
                Icons.Default.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.local_model_idle_retention),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = valueLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = stringResource(R.string.local_model_idle_retention_desc),
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
