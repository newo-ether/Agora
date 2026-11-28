package com.newoether.agora.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.common.PersistedSliderFeedbackGate
import kotlin.math.roundToInt

@Composable
internal fun SubagentLimitSlider(
    value: Int,
    valueRange: IntRange,
    @StringRes title: Int,
    @StringRes description: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val persistedValue = value.coerceIn(valueRange)
    val sliderGate = remember(valueRange) {
        PersistedSliderFeedbackGate(
            initialPersisted = persistedValue,
            toDisplay = { it.toFloat() },
        )
    }
    LaunchedEffect(persistedValue, valueRange) {
        sliderGate.reconcile(persistedValue)
    }
    val selectedValue = sliderGate.displayed.roundToInt().coerceIn(valueRange)
    val titleText = stringResource(title)

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Rounded.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.subagents_limit_value, selectedValue),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = stringResource(description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Slider(
                    value = selectedValue.toFloat(),
                    onValueChange = sliderGate::updateFromGesture,
                    onValueChangeFinished = {
                        val committedValue = sliderGate.displayed.roundToInt().coerceIn(valueRange)
                        if (committedValue != persistedValue) {
                            sliderGate.expectPersisted(committedValue, committedValue.toFloat())
                            onValueChange(committedValue)
                        } else {
                            sliderGate.settleWithoutWrite(persistedValue)
                        }
                    },
                    valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                    steps = (valueRange.last - valueRange.first - 1).coerceAtLeast(0),
                    enabled = valueRange.last > valueRange.first,
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .semantics { contentDescription = titleText },
                )
            }
        }
    }
}
