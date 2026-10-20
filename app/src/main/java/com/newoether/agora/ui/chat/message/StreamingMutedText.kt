package com.newoether.agora.ui.chat.message

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.motion.Crossfade

private const val MUTED_STREAM_TAIL_CODE_POINTS = 42
private const val MUTED_STREAM_TAIL_ALPHA_BANDS = 6
private const val MUTED_STREAM_TAIL_NEWEST_ALPHA = 0.38f
private const val STREAMING_THOUGHT_PREVIEW_CODE_POINTS = 60

@Composable
internal fun StreamingMutedText(
    text: String,
    streaming: Boolean,
) {
    StableStreamingText(
        text = text,
        streaming = streaming,
        style = ChatType.metaNormal,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        tailFadeInitialAlpha = MUTED_STREAM_TAIL_NEWEST_ALPHA,
        tailFadeCodePoints = MUTED_STREAM_TAIL_CODE_POINTS,
        tailFadeSpatialBands = MUTED_STREAM_TAIL_ALPHA_BANDS,
    )
}

@Composable
internal fun StreamingThoughtPreviewText(
    content: String,
    streaming: Boolean,
) {
    val flat = remember(content) { AnnotatedString(content.replace('\n', ' ')) }
    val preview = remember(flat, streaming) {
        if (streaming) thoughtPreviewTail(flat) else flat
    }
    StreamingMutedText(
        text = preview.text,
        streaming = streaming,
    )
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun ToolSummaryText(
    presentation: ToolPresentation,
    streaming: Boolean,
) {
    val summary = toolSummary(presentation)
    val transition = updateTransition(
        targetState = presentation.state,
        label = "toolSummaryState",
    )
    transition.Crossfade(
        animationSpec = tween(STATUS_CROSSFADE_DURATION_MS, easing = LinearEasing),
    ) { renderedState ->
        val lastSummary = remember(renderedState) { mutableStateOf(summary) }
        val isCurrentState = renderedState == presentation.state
        val renderedSummary = if (isCurrentState) summary else lastSummary.value
        SideEffect {
            if (isCurrentState && lastSummary.value != summary) {
                lastSummary.value = summary
            }
        }
        StreamingMutedText(
            text = renderedSummary,
            streaming =
                streaming &&
                    presentation.isActive &&
                    isCurrentState &&
                    !transition.isRunning,
        )
    }
}

private fun thoughtPreviewTail(
    content: AnnotatedString,
    maximumCodePoints: Int = STREAMING_THOUGHT_PREVIEW_CODE_POINTS,
): AnnotatedString {
    if (content.isEmpty() || maximumCodePoints <= 0) return content
    val raw = content.text
    val codePointCount = raw.codePointCount(0, raw.length)
    if (codePointCount <= maximumCodePoints) return content
    val start = raw.offsetByCodePoints(0, codePointCount - maximumCodePoints)
    return AnnotatedString.Builder().apply {
        append("…")
        append(content.subSequence(start, content.length))
    }.toAnnotatedString()
}
