package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.common.PersistedSliderFeedbackGate
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Text

@Composable
internal fun SearchAdvancedSettings(
    searchContextWindow: Int,
    searchMatchLimit: Int,
    ragThreshold: Float,
    searchContextGate: PersistedSliderFeedbackGate<Float, Int>,
    searchMatchGate: PersistedSliderFeedbackGate<Float, Int>,
    ragThresholdGate: PersistedSliderFeedbackGate<Float, Float>,
    onSearchContextWindowChange: (Int) -> Unit,
    onSearchMatchLimitChange: (Int) -> Unit,
    onRagThresholdChange: (Float) -> Unit,
) {
    SettingsGroup(
        title = stringResource(R.string.advanced_title),
        items = listOf(
            {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.Top
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.text_compare_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.search_context_label),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(
                                    R.string.search_context_desc,
                                    searchContextGate.displayed.toInt(),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Slider(
                                value = searchContextGate.displayed,
                                onValueChange = searchContextGate::updateFromGesture,
                                onValueChangeFinished = {
                                    val committed = searchContextGate.displayed.toInt()
                                    if (committed == searchContextWindow) {
                                        searchContextGate.settleWithoutWrite(
                                            searchContextWindow,
                                            committed.toFloat(),
                                        )
                                    } else {
                                        searchContextGate.expectPersisted(
                                            committed,
                                            committed.toFloat(),
                                        )
                                        onSearchContextWindowChange(committed)
                                    }
                                },
                                valueRange = 4f..32f,
                                steps = 6,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                        }
                    }
                }
            },
            {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.Top
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.text_compare_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.search_match_label),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(
                                    R.string.search_match_desc,
                                    searchMatchGate.displayed.toInt(),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Slider(
                                value = searchMatchGate.displayed,
                                onValueChange = searchMatchGate::updateFromGesture,
                                onValueChangeFinished = {
                                    val committed = searchMatchGate.displayed.toInt()
                                    if (committed == searchMatchLimit) {
                                        searchMatchGate.settleWithoutWrite(
                                            searchMatchLimit,
                                            committed.toFloat(),
                                        )
                                    } else {
                                        searchMatchGate.expectPersisted(
                                            committed,
                                            committed.toFloat(),
                                        )
                                        onSearchMatchLimitChange(committed)
                                    }
                                },
                                valueRange = 5f..30f,
                                steps = 4,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                        }
                    }
                }
            },
            {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.Top
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.text_compare_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.rag_threshold_label),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "≥ ${"%.2f".format(ragThresholdGate.displayed)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Slider(
                                value = ragThresholdGate.displayed,
                                onValueChange = ragThresholdGate::updateFromGesture,
                                onValueChangeFinished = {
                                    val committed = ragThresholdGate.displayed
                                    if (kotlin.math.abs(committed - ragThreshold) < 0.0001f) {
                                        ragThresholdGate.settleWithoutWrite(
                                            ragThreshold,
                                            committed,
                                        )
                                    } else {
                                        ragThresholdGate.expectPersisted(committed, committed)
                                        onRagThresholdChange(committed)
                                    }
                                },
                                valueRange = 0f..1f,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
        )
    )
}
