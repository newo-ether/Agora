package com.newoether.agora.ui.chat.message

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.chat.GenerationActivityDot
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.ChatType
import java.text.BreakIterator
import kotlin.math.floor
import com.newoether.agora.ui.components.Text

private const val RETRY_REVEAL_MS_PER_GRAPHEME = 27
private const val RETRY_REVEAL_MIN_MS = 225
private const val RETRY_REVEAL_MAX_MS = 600
private val RetryDotGap = 8.dp
private val RetryDotSize = 11.dp

internal fun retryGraphemeBoundaries(text: String): IntArray {
    if (text.isEmpty()) return intArrayOf(0)
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val boundaries = ArrayList<Int>(text.length + 1)
    var boundary = iterator.first()
    while (boundary != BreakIterator.DONE) {
        if (boundaries.lastOrNull() != boundary) {
            boundaries += boundary
        }
        boundary = iterator.next()
    }
    if (boundaries.lastOrNull() != text.length) {
        boundaries += text.length
    }
    return boundaries.toIntArray()
}

internal fun retryRevealDurationMillis(graphemeCount: Int): Int =
    if (graphemeCount <= 0) {
        0
    } else {
        (graphemeCount * RETRY_REVEAL_MS_PER_GRAPHEME)
            .coerceIn(RETRY_REVEAL_MIN_MS, RETRY_REVEAL_MAX_MS)
    }

internal fun shouldAnimateRetryEntrance(
    entranceStarted: Boolean,
    allowSpatialTransitions: Boolean,
    graphemeCount: Int,
): Boolean = !entranceStarted && allowSpatialTransitions && graphemeCount > 0

internal fun retryGraphemeAlpha(
    progress: Float,
    index: Int,
): Float = (progress - index.toFloat()).coerceIn(0f, 1f)

internal fun retryCaretPosition(
    progress: Float,
    caretPositions: FloatArray,
): Float {
    if (caretPositions.isEmpty()) return 0f
    if (caretPositions.size == 1) return caretPositions[0]
    val clamped = progress.coerceIn(0f, caretPositions.lastIndex.toFloat())
    val lowerIndex = floor(clamped).toInt()
    val upperIndex = (lowerIndex + 1).coerceAtMost(caretPositions.lastIndex)
    val fraction = clamped - lowerIndex.toFloat()
    return caretPositions[lowerIndex] +
        (caretPositions[upperIndex] - caretPositions[lowerIndex]) * fraction
}

@Composable
internal fun RetryActivityIndicator(
    label: String,
    modifier: Modifier = Modifier,
) {
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val textStyle = ChatType.body
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val graphemeBoundaries = remember(label) { retryGraphemeBoundaries(label) }
    val graphemeCount = (graphemeBoundaries.size - 1).coerceAtLeast(0)
    val entranceLabel = remember { label }
    val revealProgress = remember {
        Animatable(if (allowSpatialTransitions) 0f else graphemeCount.toFloat())
    }
    val entranceStarted = remember { mutableStateOf(false) }
    LaunchedEffect(label, graphemeCount, allowSpatialTransitions) {
        val shouldAnimate = shouldAnimateRetryEntrance(
            entranceStarted = entranceStarted.value,
            allowSpatialTransitions = allowSpatialTransitions,
            graphemeCount = graphemeCount,
        )
        entranceStarted.value = true
        if (shouldAnimate) {
            revealProgress.snapTo(0f)
            revealProgress.animateTo(
                targetValue = graphemeCount.toFloat(),
                animationSpec = tween(
                    durationMillis = retryRevealDurationMillis(graphemeCount),
                    easing = LinearOutSlowInEasing,
                ),
            )
        } else {
            revealProgress.snapTo(graphemeCount.toFloat())
        }
    }
    val displayedProgress = if (label == entranceLabel) {
        revealProgress.value
    } else {
        graphemeCount.toFloat()
    }

    val textMeasurer = rememberTextMeasurer()
    val textLayout = remember(
        textMeasurer,
        label,
        textStyle,
        layoutDirection,
        density,
    ) {
        textMeasurer.measure(
            text = AnnotatedString(label),
            style = textStyle,
            softWrap = false,
            maxLines = 1,
        )
    }
    val caretPositions = remember(textLayout, graphemeBoundaries) {
        FloatArray(graphemeBoundaries.size) { index ->
            textLayout.getHorizontalPosition(
                offset = graphemeBoundaries[index],
                usePrimaryDirection = true,
            )
        }
    }
    val animatedLabel = buildAnnotatedString {
        append(label)
        repeat(graphemeCount) { index ->
            addStyle(
                style = SpanStyle(
                    color = baseColor.copy(
                        alpha = baseColor.alpha *
                            retryGraphemeAlpha(displayedProgress, index),
                    ),
                ),
                start = graphemeBoundaries[index],
                end = graphemeBoundaries[index + 1],
            )
        }
    }

    val gapPx = with(density) { RetryDotGap.toPx() }
    val dotSizePx = with(density) { RetryDotSize.toPx() }
    val textWidthPx = textLayout.size.width.toFloat()
    val textHeightPx = textLayout.size.height.toFloat()
    val containerWidthPx = textWidthPx + gapPx + dotSizePx
    val containerWidth = with(density) {
        containerWidthPx.toDp()
    }
    val containerHeight = with(density) {
        maxOf(textHeightPx, dotSizePx).toDp()
    }
    val textStartPx = if (layoutDirection == LayoutDirection.Ltr) {
        0f
    } else {
        gapPx + dotSizePx
    }
    val caretPx = retryCaretPosition(displayedProgress, caretPositions)
    val dotOffsetPx = if (layoutDirection == LayoutDirection.Ltr) {
        textStartPx + caretPx + gapPx
    } else {
        textStartPx + caretPx - gapPx - dotSizePx
    }
    val dotPlacementStartPx = if (layoutDirection == LayoutDirection.Ltr) {
        0f
    } else {
        containerWidthPx - dotSizePx
    }
    val dotTranslationPx = dotOffsetPx - dotPlacementStartPx
    val dotVerticalOffsetPx = (textHeightPx - dotSizePx).coerceAtLeast(0f) / 2f

    Box(
        modifier = modifier
            .width(containerWidth)
            .height(containerHeight),
        contentAlignment = Alignment.TopStart,
    ) {
        Text(
            text = animatedLabel,
            style = textStyle,
            maxLines = 1,
            softWrap = false,
        )
        GenerationActivityDot(
            modifier = Modifier.graphicsLayer {
                translationX = dotTranslationPx
                translationY = dotVerticalOffsetPx
                clip = false
            },
        )
    }
}
