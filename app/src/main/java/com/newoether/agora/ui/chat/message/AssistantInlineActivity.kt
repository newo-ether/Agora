package com.newoether.agora.ui.chat.message

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.chat.GenerationActivityDot
import com.newoether.agora.ui.chat.StreamingTailAnchorHeight

private val AssistantInlineActivityHeight = StreamingTailAnchorHeight

internal enum class AssistantInlineActivityMode {
    NONE,
    EMPTY,
    RETRY,
}

internal fun assistantInlineActivityMode(
    generationActive: Boolean,
    hasAnswer: Boolean,
    hasVisibleInfoSegment: Boolean,
    retryText: String?,
): AssistantInlineActivityMode = when {
    !generationActive -> AssistantInlineActivityMode.NONE
    !retryText.isNullOrBlank() -> AssistantInlineActivityMode.RETRY
    !hasAnswer && !hasVisibleInfoSegment -> AssistantInlineActivityMode.EMPTY
    else -> AssistantInlineActivityMode.NONE
}

internal data class AssistantInlineActivityPresentation(
    val mode: AssistantInlineActivityMode,
    val retainLayout: Boolean,
)

internal fun assistantInlineActivityPresentation(
    generationActive: Boolean,
    isStopping: Boolean,
    hasAnswer: Boolean,
    hasVisibleInfoSegment: Boolean,
    retryText: String?,
): AssistantInlineActivityPresentation {
    val ownedMode = assistantInlineActivityMode(
        generationActive,
        hasAnswer,
        hasVisibleInfoSegment,
        retryText,
    )
    return AssistantInlineActivityPresentation(
        mode = if (isStopping) AssistantInlineActivityMode.NONE else ownedMode,
        retainLayout = isStopping && ownedMode != AssistantInlineActivityMode.NONE,
    )
}

@Composable
internal fun AssistantInlineActivity(
    mode: AssistantInlineActivityMode,
    retryText: String?,
    visibilityTransition: Transition<Boolean>,
    activityOpacity: Float,
    retainExitLayout: Boolean,
    terminalText: String?,
    terminalIsError: Boolean,
    terminalShowLocalContextHelp: Boolean,
    precededByCard: Boolean,
) {
    var retainedMode by remember {
        mutableStateOf(
            mode.takeUnless { it == AssistantInlineActivityMode.NONE }
                ?: AssistantInlineActivityMode.EMPTY,
        )
    }
    var retainedRetryText by remember { mutableStateOf(retryText) }
    LaunchedEffect(mode, retryText) {
        if (mode != AssistantInlineActivityMode.NONE) {
            retainedMode = mode
            retainedRetryText = retryText
        }
    }
    val activityVisible = visibilityTransition.targetState
    val ownsCurrentActivity = activityVisible && mode != AssistantInlineActivityMode.NONE
    val visibleMode = if (ownsCurrentActivity) mode else retainedMode
    val visibleRetryText = if (ownsCurrentActivity) retryText else retainedRetryText
    val terminalTransition = updateTransition(
        targetState = terminalText != null,
        label = "AssistantInlineTerminalTransition",
    )
    val terminalOpacity by terminalTransition.animateFloat(
        transitionSpec = { tween(durationMillis = 180, easing = LinearEasing) },
        label = "AssistantInlineTerminalOpacity",
    ) { terminal -> if (terminal) 1f else 0f }
    if (visibilityTransition.targetState || retainExitLayout || terminalText != null) {
        Box(
            modifier = Modifier
                .padding(top = if (precededByCard) 12.dp else 0.dp)
                .heightIn(min = AssistantInlineActivityHeight),
        ) {
            if (!terminalTransition.currentState || !terminalTransition.targetState) {
                // Terminal content alone measures the slot. Fade the paints directly so a
                // translated or enlarged breathing dot never enters a tight alpha layer.
                val drawOpacity = (1f - terminalOpacity) *
                    if (terminalText == null) activityOpacity else 1f
                Box(modifier = if (terminalText != null) Modifier.matchParentSize() else Modifier) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (visibleMode == AssistantInlineActivityMode.RETRY) {
                            RetryActivityIndicator(
                                label = visibleRetryText.orEmpty() + "...",
                                opacity = drawOpacity,
                            )
                        } else {
                            GenerationActivityDot(opacity = drawOpacity)
                        }
                    }
                }
            }
            if (terminalText != null) {
                Box(modifier = Modifier.graphicsLayer {
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    alpha = terminalOpacity
                    clip = false
                }) {
                    if (terminalIsError) {
                        GenerationErrorBar(
                            errorText = terminalText,
                            showLocalContextHelp = terminalShowLocalContextHelp,
                            topPadding = 0.dp,
                        )
                    } else {
                        GenerationTerminalText(terminalText)
                    }
                }
            }
        }
    }
}
