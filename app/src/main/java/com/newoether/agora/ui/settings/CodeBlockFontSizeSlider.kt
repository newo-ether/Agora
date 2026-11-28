package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
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
import com.newoether.agora.data.CODE_BLOCK_FONT_SIZE_PRESETS
import com.newoether.agora.data.normalizeCodeBlockFontSizeSp
import com.newoether.agora.ui.common.PersistedSliderFeedbackGate
import kotlin.math.roundToInt

@Composable
internal fun CodeBlockFontSizeSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = CODE_BLOCK_FONT_SIZE_PRESETS
    val persistedValue = normalizeCodeBlockFontSizeSp(value)
    val gate = remember {
        PersistedSliderFeedbackGate(
            initialPersisted = persistedValue,
            toDisplay = { presets.indexOf(it).toFloat() },
        )
    }
    LaunchedEffect(persistedValue) { gate.reconcile(persistedValue) }
    val selectedIndex = gate.displayed.roundToInt().coerceIn(presets.indices)
    val title = stringResource(R.string.code_block_font_size)

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Rounded.Code,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp).size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.code_block_font_size_value, presets[selectedIndex]),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = stringResource(R.string.code_block_font_size_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Slider(
                    value = gate.displayed,
                    onValueChange = gate::updateFromGesture,
                    onValueChangeFinished = {
                        val index = gate.displayed.roundToInt().coerceIn(presets.indices)
                        val sizeSp = presets[index]
                        if (sizeSp != persistedValue) {
                            gate.expectPersisted(sizeSp, index.toFloat())
                            onValueChange(sizeSp)
                        } else {
                            gate.settleWithoutWrite(persistedValue, index.toFloat())
                        }
                    },
                    valueRange = 0f..presets.lastIndex.toFloat(),
                    steps = presets.size - 2,
                    modifier = modifier.fillMaxWidth().padding(top = 8.dp)
                        .semantics { contentDescription = title },
                )
            }
        }
    }
}
