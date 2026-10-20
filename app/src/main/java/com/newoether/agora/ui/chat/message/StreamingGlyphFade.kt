package com.newoether.agora.ui.chat.message

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import kotlinx.coroutines.delay
import kotlin.math.min

internal const val STREAM_TAIL_ALPHA_PER_SECOND = 2f
private const val STREAM_TAIL_FADE_TICK_MS = 40L

/** Applies temporal alpha, with optional spatial bands whose newest edge starts at [initialAlpha]. */
internal fun streamingTailAnnotatedString(
    text: String,
    color: Color,
    fadeCodePoints: Int? = null,
    birthTimesMs: LongArray? = null,
    nowMs: Long = 0L,
    alphaPerSecond: Float = STREAM_TAIL_ALPHA_PER_SECOND,
    initialAlpha: Float = 0f,
    spatialBands: Int = 0,
): AnnotatedString = streamingTailAnnotatedString(
    text = AnnotatedString(text),
    color = color,
    fadeCodePoints = fadeCodePoints,
    birthTimesMs = birthTimesMs,
    nowMs = nowMs,
    alphaPerSecond = alphaPerSecond,
    initialAlpha = initialAlpha,
    spatialBands = spatialBands,
)

/**
 * Adds only foreground-color spans to an already-rendered Markdown [AnnotatedString]. Existing
 * emphasis, links, inline-code, search highlights, font metrics, and paragraph layout are retained.
 */
internal fun streamingTailAnnotatedString(
    text: AnnotatedString,
    color: Color,
    fadeCodePoints: Int? = null,
    birthTimesMs: LongArray? = null,
    nowMs: Long = 0L,
    alphaPerSecond: Float = STREAM_TAIL_ALPHA_PER_SECOND,
    initialAlpha: Float = 0f,
    spatialBands: Int = 0,
): AnnotatedString {
    if (text.isEmpty()) return text
    val births = birthTimesMs ?: return text
    if (births.isEmpty()) return text

    val rawText = text.text
    val codePointCount = rawText.codePointCount(0, rawText.length)
    val requestedFadeCodePoints = fadeCodePoints ?: births.size
    if (requestedFadeCodePoints <= 0) return text
    val fadedCount = min(codePointCount, min(requestedFadeCodePoints, births.size))
    if (fadedCount == 0) return text
    val startAlpha = initialAlpha.coerceIn(0f, 1f)
    val actualBands = min(spatialBands.coerceAtLeast(0), fadedCount)

    val metadataStart = births.size - fadedCount
    val prefixCodePoints = codePointCount - fadedCount
    val builder = AnnotatedString.Builder().apply { append(text) }
    var rangeStartCodePoint = prefixCodePoints
    var rangeAlpha: Float? = null

    fun flushRange(endCodePoint: Int) {
        val alpha = rangeAlpha ?: return
        if (alpha < 0.999f) {
            builder.addStyle(
                SpanStyle(color = color.copy(alpha = color.alpha * alpha)),
                rawText.offsetByCodePoints(0, rangeStartCodePoint),
                rawText.offsetByCodePoints(0, endCodePoint),
            )
        }
    }

    for (suffixIndex in 0 until fadedCount) {
        val metadataIndex = metadataStart + suffixIndex
        val elapsedSeconds =
            (nowMs - births[metadataIndex]).coerceAtLeast(0L) / 1_000f
        val ageAlpha = alphaPerSecond.coerceAtLeast(0f) * elapsedSeconds
        val alpha = if (actualBands > 0) {
            val band = suffixIndex * actualBands / fadedCount
            val bandProgress = (band + 1).toFloat() / actualBands.toFloat()
            val spatialAlpha = 1f - bandProgress * (1f - startAlpha)
            (spatialAlpha + ageAlpha).coerceIn(0f, 1f)
        } else {
            val progress = ageAlpha.coerceIn(0f, 1f)
            startAlpha + (1f - startAlpha) * progress
        }
        if (rangeAlpha == null) {
            rangeAlpha = alpha
        } else if (kotlin.math.abs(checkNotNull(rangeAlpha) - alpha) > 0.0001f) {
            flushRange(prefixCodePoints + suffixIndex)
            rangeStartCodePoint = prefixCodePoints + suffixIndex
            rangeAlpha = alpha
        }
    }
    flushRange(codePointCount)
    return builder.toAnnotatedString()
}

/**
 * Owns the active time component of the glyph fade inside the final Markdown text composable.
 * Only this leaf recomposes while alpha changes; the parser, block column, and LazyColumn do not.
 * Birth times come from the document-level fade sample (via [StreamingGlyphNodeFade]), so node
 * restructures and subtree re-keying cannot reset or skip the gradient.
 */
@Composable
internal fun rememberStreamingGlyphFade(
    content: AnnotatedString,
    color: Color,
    fade: StreamingGlyphNodeFade?,
): AnnotatedString {
    if (com.newoether.agora.ui.motion.LocalAgoraMotionPolicy.current.eInkMode) return content
    if (fade == null || fade.tailCodePoints <= 0 || content.isEmpty()) return content
    val effective = remember(fade, content.text.length) {
        val displayCodePoints = content.text.codePointCount(0, content.text.length)
        val fadedCount = min(fade.tailCodePoints, displayCodePoints)
        when {
            fadedCount <= 0 -> null
            fadedCount == fade.birthTimesMs.size -> fade
            else -> {
                // Display text can outgrow the node's source range (citation superscripts).
                // Fade the final code points with the newest slice of the birth array.
                StreamingGlyphNodeFade(
                    tailCodePoints = fadedCount,
                    birthTimesMs = fade.birthTimesMs.copyOfRange(
                        fade.birthTimesMs.size - fadedCount,
                        fade.birthTimesMs.size,
                    ),
                )
            }
        }
    }
    if (effective == null) return content
    var fadeClockMs by remember(effective) {
        mutableLongStateOf(SystemClock.uptimeMillis())
    }
    LaunchedEffect(effective) {
        while (
            streamingTailFadeActive(
                birthTimesMs = effective.birthTimesMs,
                nowMs = fadeClockMs,
            )
        ) {
            delay(STREAM_TAIL_FADE_TICK_MS)
            fadeClockMs = SystemClock.uptimeMillis()
        }
    }
    return remember(content, color, effective, fadeClockMs) {
        streamingTailAnnotatedString(
            text = content,
            color = color,
            fadeCodePoints = effective.tailCodePoints,
            birthTimesMs = effective.birthTimesMs,
            nowMs = fadeClockMs,
        )
    }
}

/**
 * Standalone variant for plain-text streaming companions such as timeline entries
 * that do not render through the markdown block pipeline. Each instance keeps its own tracker.
 */
@Composable
internal fun rememberStreamingGlyphFade(
    content: AnnotatedString,
    color: Color,
    enabled: Boolean,
    initialAlpha: Float = 0f,
    fadeCodePoints: Int? = null,
    spatialBands: Int = 0,
): AnnotatedString {
    if (com.newoether.agora.ui.motion.LocalAgoraMotionPolicy.current.eInkMode) return content
    if (!enabled || content.isEmpty()) return content

    val fadeTracker = remember { StreamingTailFadeTracker() }
    val fadeSample = remember(content.text, fadeTracker) {
        fadeTracker.update(content.text, SystemClock.uptimeMillis())
    }
    var fadeClockMs by remember(fadeSample) {
        mutableLongStateOf(fadeSample.observedAtMs)
    }
    LaunchedEffect(fadeSample, initialAlpha, spatialBands) {
        while (
            streamingTailFadeActive(
                birthTimesMs = fadeSample.birthTimesMs,
                nowMs = fadeClockMs,
                initialAlpha = if (spatialBands > 0) initialAlpha else 0f,
            )
        ) {
            delay(STREAM_TAIL_FADE_TICK_MS)
            fadeClockMs = SystemClock.uptimeMillis()
        }
    }
    return remember(
        content,
        color,
        fadeSample,
        fadeClockMs,
        initialAlpha,
        fadeCodePoints,
        spatialBands,
    ) {
        streamingTailAnnotatedString(
            text = content,
            color = color,
            fadeCodePoints = fadeCodePoints,
            birthTimesMs = fadeSample.birthTimesMs,
            nowMs = fadeClockMs,
            initialAlpha = initialAlpha,
            spatialBands = spatialBands,
        )
    }
}

internal fun streamingTailFadeActive(
    birthTimesMs: LongArray,
    nowMs: Long,
    alphaPerSecond: Float = STREAM_TAIL_ALPHA_PER_SECOND,
    initialAlpha: Float = 0f,
): Boolean {
    if (birthTimesMs.isEmpty() || alphaPerSecond <= 0f) return false
    return birthTimesMs.any { birthTimeMs ->
        val elapsedSeconds = (nowMs - birthTimeMs).coerceAtLeast(0L) / 1_000f
        initialAlpha.coerceIn(0f, 1f) + alphaPerSecond * elapsedSeconds < 0.999f
    }
}
