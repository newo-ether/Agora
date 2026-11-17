package com.newoether.agora.ui.chat.message

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import kotlin.math.roundToInt

/** A single reveal owns height; Expand is an overlay and Collapse follows the full body. */
@Composable
internal fun CollapsibleMessageBody(
    messageKey: String,
    searchActive: Boolean = false,
    modifier: Modifier = Modifier,
    sizeAnimationReady: Boolean = true,
    onLayoutMutationStarted: (String) -> Unit = {},
    onLayoutMutationSettled: (String) -> Unit = {},
    onMotionChanged: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(messageKey) { mutableStateOf(false) }
    var overflows by rememberSaveable(messageKey) { mutableStateOf(false) }
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    val mutationKey = "user-disclosure:$messageKey"
    val currentOnStarted by rememberUpdatedState(onLayoutMutationStarted)
    val currentOnSettled by rememberUpdatedState(onLayoutMutationSettled)
    val currentOnMotionChanged by rememberUpdatedState(onMotionChanged)
    var mutationPending by remember(messageKey) { mutableStateOf(false) }
    val target = if (searchActive) null else expanded
    var admittedTarget by remember(messageKey) { mutableStateOf(target) }
    val startMutation = {
        currentOnStarted(mutationKey)
        mutationPending = true
        currentOnMotionChanged(true)
    }
    // Search owns its own list seek, but its reveal must still exclude the outer size animation.
    SideEffect {
        if (admittedTarget != target) {
            admittedTarget = target
            startMutation()
        }
    }
    // Null reveals search results without either manual disclosure control.
    val transition = updateTransition(
        targetState = target,
        label = "userMessageDisclosure",
    )
    val reveal by transition.animateFloat(
        transitionSpec = {
            if (sizeAnimationReady && allowSpatialTransitions) tween(400) else snap()
        },
        label = "bodyReveal",
    ) { if (it == false) 0f else 1f }
    val footerReveal by transition.animateFloat(
        transitionSpec = {
            if (sizeAnimationReady && allowSpatialTransitions) tween(400) else snap()
        },
        label = "footerReveal",
    ) { if (it == true) 1f else 0f }
    val collapsedOpacity = remember(messageKey) { Animatable(if (searchActive || expanded) 0f else 1f) }
    val expandedOpacity = remember(messageKey) { Animatable(if (!searchActive && expanded) 1f else 0f) }
    val collapsedTarget = if (!searchActive && !expanded) 1f else 0f
    val expandedTarget = if (!searchActive && expanded) 1f else 0f
    // Unlike Transition's interruption spring, opacity must remain linear even on reversal.
    LaunchedEffect(collapsedTarget) {
        collapsedOpacity.animateTo(collapsedTarget, tween(180, easing = LinearEasing))
    }
    LaunchedEffect(expandedTarget) {
        expandedOpacity.animateTo(expandedTarget, tween(180, easing = LinearEasing))
    }

    LaunchedEffect(transition, mutationKey) {
        snapshotFlow {
            val latest = admittedTarget
            mutationPending && !transition.isRunning &&
                transition.currentState == latest && transition.targetState == latest &&
                !collapsedOpacity.isRunning && !expandedOpacity.isRunning &&
                collapsedOpacity.value == (if (latest == false) 1f else 0f) &&
                expandedOpacity.value == (if (latest == true) 1f else 0f)
        }.collect { settled ->
            if (settled) {
                mutationPending = false
                currentOnMotionChanged(false)
                currentOnSettled(mutationKey)
            }
        }
    }
    DisposableEffect(mutationKey) {
        onDispose {
            if (mutationPending) {
                mutationPending = false
                currentOnMotionChanged(false)
                currentOnSettled(mutationKey)
            }
        }
    }
    val toggle = {
        // Capture before changing measurement, including a reversal of the current transition.
        admittedTarget = !expanded
        startMutation()
        expanded = !expanded
    }
    val controlColors = ButtonDefaults.textButtonColors(
        disabledContentColor = MaterialTheme.colorScheme.primary,
    )
    Layout(
        modifier = modifier,
        content = {
            Layout(
                content = {
                    Column { content() }
                    if (overflows) {
                        DisableSelection {
                            TextButton(
                                onClick = toggle,
                                shape = CircleShape,
                                modifier = Modifier
                                    .graphicsLayer { alpha = expandedOpacity.value }
                                    .then(if (expanded && !searchActive) Modifier else Modifier.clearAndSetSemantics {}),
                                enabled = expanded && !searchActive,
                                colors = controlColors,
                            ) { Text(stringResource(R.string.collapse)) }
                        }
                    }
                },
                modifier = Modifier
                    .graphicsLayer {
                        clip = true
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        if (overflows && collapsedOpacity.value > 0f) {
                            val fadeEnd = (size.height - 48.dp.toPx()).coerceAtLeast(0f)
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Black, Color.Black.copy(alpha = 1f - collapsedOpacity.value)),
                                    startY = (fadeEnd - 64.dp.toPx()).coerceAtLeast(0f),
                                    endY = fadeEnd.coerceAtLeast(1f),
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    },
            ) { measurables, constraints ->
                val fullConstraints = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
                val body = measurables.first().measure(fullConstraints)
                val footer = measurables.getOrNull(1)?.measure(fullConstraints.copy(minWidth = 0))
                val previewHeight = minOf(body.height, 320.dp.roundToPx())
                overflows = body.height > previewHeight
                val visibleHeight = previewHeight +
                    ((body.height - previewHeight) * reveal + (footer?.height ?: 0) * footerReveal).roundToInt()
                layout(body.width, constraints.constrainHeight(visibleHeight)) {
                    body.placeRelative(0, 0)
                    // Collapse occupies its own row after the complete body, at physical left.
                    footer?.place(0, body.height)
                }
            }
            if (overflows) {
                DisableSelection {
                    TextButton(
                        onClick = toggle,
                        shape = CircleShape,
                        modifier = Modifier
                            .graphicsLayer { alpha = collapsedOpacity.value }
                            .then(if (!searchActive && !expanded) Modifier else Modifier.clearAndSetSemantics {}),
                        enabled = !expanded && !searchActive,
                        colors = controlColors,
                    ) { Text(stringResource(R.string.expand)) }
                }
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val body = measurables.first().measure(loose)
        val expand = measurables.getOrNull(1)?.measure(loose)
        // Expand's dimensions never change the body layout or the occupied viewport size.
        val width = maxOf(constraints.minWidth, body.width)
        val height = constraints.constrainHeight(body.height)
        layout(width, height) {
            body.placeRelative(0, 0)
            if (collapsedOpacity.value > 0f || (!expanded && !searchActive)) {
                expand?.place(0, height - expand.height)
            }
        }
    }
}
