package com.newoether.agora.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import kotlin.math.abs
import kotlin.math.roundToInt

private val BudgetToggleToSliderSpacing = 32.dp
private val AdvancedChevronSize = 18.dp

@Composable
fun ThinkingControlPanel(
    enabled: Boolean,
    level: String,
    budgetEnabled: Boolean,
    budgetTokens: Int,
    onEnabledChange: (Boolean) -> Unit,
    onLevelChange: (String) -> Unit,
    onBudgetEnabledChange: (Boolean) -> Unit,
    onBudgetTokensChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    showEnabledToggle: Boolean = true,
    animateSections: Boolean = false,
    availableEfforts: List<String>? = null,
    controlsEnabled: Boolean = true,
    allowDisable: Boolean = true,
    showBudgetControls: Boolean = true,
    settingsRevision: Long = 0,
) {
    val normalizedEffort = if (availableEfforts == null) ThinkingLevels.normalize(level) else level
    // A caller that knows the selected model passes that model's accepted efforts. Without one
    // (the global default page) every documented level is offered; no provider-name range exists.
    val providerRange = availableEfforts?.indices?.takeUnless { it.isEmpty() }
        ?: ThinkingLevels.effortValues.indices
    val maxIndex = providerRange.last
    fun effortAt(index: Int) = availableEfforts?.getOrNull(index) ?: if (availableEfforts == null) {
        ThinkingLevels.effortForIndex(index)
    } else ""
    val effortGate = remember(providerRange.first, providerRange.last, availableEfforts, settingsRevision) {
        PersistedSliderFeedbackGate(
            initialPersisted = normalizedEffort,
            toDisplay = { persisted ->
                (availableEfforts?.indexOf(persisted) ?: ThinkingLevels.indexForEffort(persisted))
                    .coerceIn(providerRange).toFloat()
            },
        )
    }
    LaunchedEffect(normalizedEffort, effortGate) {
        effortGate.reconcile(normalizedEffort)
    }
    val sliderPosition = effortGate.displayed
    var showAdvanced by rememberSaveable { mutableStateOf(budgetEnabled) }
    val sliderEnabled = (enabled || !showEnabledToggle) && !budgetEnabled && controlsEnabled && availableEfforts?.isEmpty() != true

    LaunchedEffect(budgetEnabled) {
        if (budgetEnabled) showAdvanced = true
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (showHeader) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.neurology_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(if (showEnabledToggle) R.string.gen_thinking_enabled else R.string.thinking),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = thinkingControlShortLabel(enabled, normalizedEffort, budgetEnabled, budgetTokens, normalizeLevel = availableEfforts == null),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (showEnabledToggle) {
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(checked = enabled, onCheckedChange = onEnabledChange, enabled = controlsEnabled && (allowDisable || !enabled))
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth().alpha(if (sliderEnabled) 1f else 0.38f),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                painter = painterResource(id = R.drawable.neurology_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.thinking_effort_label),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = effortLabel(effortAt(sliderPosition.roundToInt())),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = stringResource(R.string.thinking_effort_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Slider(
                    value = sliderPosition,
                    onValueChange = { if (sliderEnabled) effortGate.updateFromGesture(it) },
                    onValueChangeFinished = {
                        if (sliderEnabled) {
                            val idx = sliderPosition.roundToInt().coerceIn(providerRange)
                            val effort = effortAt(idx)
                            if (effort == normalizedEffort) {
                                effortGate.settleWithoutWrite(normalizedEffort, idx.toFloat())
                            } else {
                                effortGate.expectPersisted(effort, idx.toFloat())
                            }
                            if (availableEfforts == null) onEnabledChange(true)
                            onLevelChange(effort)
                        }
                    },
                    valueRange = 0f..maxIndex.coerceAtLeast(1).toFloat(),
                    steps = if (maxIndex > 0) maxIndex - 1 else 0,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = sliderEnabled && maxIndex > 0
                )
            }
        }

        if (!showBudgetControls) return@Column
        Spacer(modifier = Modifier.height(8.dp))
        val chevronRotation by animateFloatAsState(
            targetValue = if (showAdvanced) 180f else 0f,
            animationSpec = if (animateSections) tween(400) else tween(0),
            label = "advancedChevronRotation"
        )
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (showAdvanced) stringResource(R.string.thinking_advanced_hide) else stringResource(R.string.advanced_settings))
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier
                        .size(AdvancedChevronSize)
                        .rotate(chevronRotation)
                )
            }
        }

        MaybeAnimatedVisibility(visible = showAdvanced, animate = animateSections) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (enabled) 1f else 0.38f)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.neurology_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.thinking_use_budget),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.thinking_budget_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = budgetEnabled,
                        onCheckedChange = if (enabled) { { checked ->
                            if (checked) {
                                onEnabledChange(true)
                                if (budgetTokens < 1) {
                                    onBudgetTokensChange(ThinkingLevels.DefaultBudgetTokens)
                                }
                            }
                            onBudgetEnabledChange(checked)
                        } } else { { } },
                        enabled = enabled
                    )
                }

                MaybeAnimatedVisibility(visible = budgetEnabled, animate = animateSections) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(BudgetToggleToSliderSpacing))
                        val budgetPresets = ThinkingLevels.budgetPresets
                        val currentBudget = budgetTokens.coerceAtLeast(1)
                        val budgetGate = remember(budgetPresets) {
                            PersistedSliderFeedbackGate(
                                initialPersisted = currentBudget,
                                toDisplay = { persisted ->
                                    budgetPresets.indices.minByOrNull {
                                        abs(budgetPresets[it] - persisted)
                                    }?.toFloat() ?: 1f
                                },
                            )
                        }
                        LaunchedEffect(currentBudget) { budgetGate.reconcile(currentBudget) }
                        val budgetSliderPos = budgetGate.displayed

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.neurology_24),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.thinking_budget_input_label),
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = stringResource(R.string.thinking_budget_tokens, budgetPresets[budgetSliderPos.roundToInt()]),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Slider(
                                    value = budgetSliderPos,
                                    onValueChange = {
                                        if (enabled) budgetGate.updateFromGesture(it)
                                    },
                                    onValueChangeFinished = {
                                        if (enabled) {
                                            val idx = budgetSliderPos.roundToInt().coerceIn(0, budgetPresets.lastIndex)
                                            val tokens = budgetPresets[idx]
                                            if (tokens == currentBudget) {
                                                budgetGate.settleWithoutWrite(
                                                    currentBudget,
                                                    idx.toFloat(),
                                                )
                                            } else {
                                                budgetGate.expectPersisted(tokens, idx.toFloat())
                                            }
                                            onEnabledChange(true)
                                            onBudgetTokensChange(tokens)
                                        }
                                    },
                                    valueRange = 0f..(budgetPresets.size - 1).toFloat(),
                                    steps = budgetPresets.size - 2,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    enabled = enabled
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaybeAnimatedVisibility(
    visible: Boolean,
    animate: Boolean,
    content: @Composable () -> Unit
) {
    if (animate) {
        val allowSpatialTransitions =
            LocalAgoraMotionPolicy.current.allowSpatialTransitions
        AnimatedVisibility(
            visible = visible,
            enter = if (allowSpatialTransitions) {
                fadeIn(animationSpec = tween(400)) + expandVertically(
                    animationSpec = tween(400),
                    expandFrom = Alignment.Top
                )
            } else {
                fadeIn(animationSpec = tween(400))
            },
            exit = if (allowSpatialTransitions) {
                fadeOut(animationSpec = tween(400)) + shrinkVertically(
                    animationSpec = tween(400),
                    shrinkTowards = Alignment.Top
                )
            } else {
                fadeOut(animationSpec = tween(400))
            },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    } else if (visible) {
        content()
    }
}

@Composable
fun thinkingControlShortLabel(
    enabled: Boolean,
    level: String,
    budgetEnabled: Boolean = false,
    budgetTokens: Int = ThinkingLevels.DefaultBudgetTokens,
    normalizeLevel: Boolean = true,
): String {
    if (!enabled) return stringResource(R.string.thinking_control_off)
    val effortText = effortLabel(if (normalizeLevel) ThinkingLevels.normalize(level) else level)
    if (!budgetEnabled) return effortText
    return stringResource(R.string.thinking_budget_tokens, budgetTokens.coerceAtLeast(1))
}

@Composable
internal fun effortLabel(effort: String): String = when (effort) {
    "minimal" -> stringResource(R.string.gen_thinking_level_minimal)
    "low" -> stringResource(R.string.gen_thinking_level_low)
    "medium" -> stringResource(R.string.gen_thinking_level_medium)
    "high" -> stringResource(R.string.gen_thinking_level_high)
    "xhigh" -> stringResource(R.string.gen_thinking_level_xhigh)
    "max" -> stringResource(R.string.gen_thinking_level_max)
    else -> effort
}
