package com.newoether.agora.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.max
import com.newoether.agora.ui.components.Text

enum class TypewriterMode {
    CURSOR,
    TEXT_GRADIENT,
}

enum class TypewriterCursor(val glyph: String) {
    BAR("|"),
    ROUND_DOT("●"),
}

/**
 * One finite, frame-clocked typing component for entry copy.
 *
 * Progress is counted in Unicode code points, so an emoji surrogate pair is never split across
 * frames. The complete transparent text permanently owns layout while the animated layer draws
 * over it, preventing width, height, and line-wrap changes during typing.
 *
 * [TypewriterMode.CURSOR] supports either a bar or round-dot [cursor]. In
 * [TypewriterMode.TEXT_GRADIENT], each newly revealed code point starts translucent and becomes
 * fully opaque as a function of its age; the animation stops after the final glyph is solid.
 */
@Composable
fun TypewriterText(
    text: String,
    animationKey: Any,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    typeSpeedMs: Int = 100,
    initialDelayMs: Int = 0,
    animate: Boolean = true,
    showText: Boolean = true,
    mode: TypewriterMode = TypewriterMode.CURSOR,
    cursor: TypewriterCursor = TypewriterCursor.BAR,
    gradientFadeDurationMs: Int = 420,
    onDone: () -> Unit = {},
) {
    val codePointCount = remember(text) { text.codePointCount(0, text.length) }
    val safeTypeSpeedMs = typeSpeedMs.coerceAtLeast(1)
    val safeInitialDelayMs = initialDelayMs.coerceAtLeast(0)
    val safeGradientFadeMs = gradientFadeDurationMs.coerceAtLeast(1)
    val baseColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> LocalContentColor.current
    }
    val latestOnDone by rememberUpdatedState(onDone)

    var visibleCodePoints by rememberSaveable(animationKey, text, animate) {
        mutableIntStateOf(if (animate) 0 else codePointCount)
    }
    var completed by rememberSaveable(animationKey, text, animate) {
        mutableStateOf(!animate)
    }
    var animationElapsedMs by remember(animationKey, text, animate) {
        mutableLongStateOf(0L)
    }

    LaunchedEffect(
        animationKey,
        text,
        animate,
        mode,
        safeTypeSpeedMs,
        safeInitialDelayMs,
        safeGradientFadeMs,
    ) {
        if (!animate || completed) return@LaunchedEffect
        if (codePointCount == 0) {
            visibleCodePoints = 0
            completed = true
            latestOnDone()
            return@LaunchedEffect
        }

        val resumedElapsedMs =
            if (visibleCodePoints == 0) {
                0L
            } else {
                safeInitialDelayMs.toLong() +
                    visibleCodePoints.toLong() * safeTypeSpeedMs
            }
        val firstFrameNanos = withFrameNanos { it }
        val startNanos = firstFrameNanos - resumedElapsedMs * 1_000_000L
        val revealDurationMs = codePointCount.toLong() * safeTypeSpeedMs
        val finishElapsedMs =
            safeInitialDelayMs.toLong() +
                revealDurationMs +
                if (mode == TypewriterMode.TEXT_GRADIENT) safeGradientFadeMs else 0

        while (!completed) {
            val frameNanos = withFrameNanos { it }
            val elapsedMs =
                ((frameNanos - startNanos).coerceAtLeast(0L) / 1_000_000L)
            animationElapsedMs = elapsedMs
            val revealElapsedMs =
                (elapsedMs - safeInitialDelayMs).coerceAtLeast(0L)
            val targetCount =
                (revealElapsedMs / safeTypeSpeedMs)
                    .toInt()
                    .coerceIn(0, codePointCount)
            visibleCodePoints = max(visibleCodePoints, targetCount)

            if (elapsedMs >= finishElapsedMs) {
                visibleCodePoints = codePointCount
                completed = true
                latestOnDone()
            }
        }
    }

    if (mode == TypewriterMode.TEXT_GRADIENT) {
        StableGradientTypingText(
            text = text,
            visibleCodePoints = visibleCodePoints,
            completed = completed,
            elapsedMs = animationElapsedMs,
            initialDelayMs = safeInitialDelayMs,
            typeSpeedMs = safeTypeSpeedMs,
            gradientFadeDurationMs = safeGradientFadeMs,
            modifier = modifier,
            style = style,
            fontWeight = fontWeight,
            baseColor = baseColor,
            textAlign = textAlign,
            showText = showText,
        )
        return
    }

    val animatedText = remember(
        text,
        visibleCodePoints,
        completed,
        animationElapsedMs,
        cursor,
        baseColor,
    ) {
        buildCursorTypingText(
            text = text,
            visibleCodePoints = visibleCodePoints,
            completed = completed,
            elapsedMs = animationElapsedMs,
            cursor = cursor,
            baseColor = baseColor,
        )
    }

    Box(modifier = modifier) {
        Text(
            text = text,
            style = style,
            fontWeight = fontWeight,
            color = Color.Transparent,
            textAlign = textAlign,
            modifier = Modifier.clearAndSetSemantics { },
        )
        if (showText) {
            Text(
                text = animatedText,
                style = style,
                fontWeight = fontWeight,
                color = baseColor,
                textAlign = textAlign,
            )
        }
    }
}

/**
 * The paragraph is shaped exactly once as the complete plain string. Alpha is applied only while
 * painting clipped glyph ranges, so foreground changes cannot split/merge shaping runs or toggle
 * kerning at the completion boundary.
 */
@Composable
private fun StableGradientTypingText(
    text: String,
    visibleCodePoints: Int,
    completed: Boolean,
    elapsedMs: Long,
    initialDelayMs: Int,
    typeSpeedMs: Int,
    gradientFadeDurationMs: Int,
    modifier: Modifier,
    style: TextStyle,
    fontWeight: FontWeight?,
    baseColor: Color,
    textAlign: TextAlign?,
    showText: Boolean,
) {
    var layoutResult by remember(text) {
        mutableStateOf<TextLayoutResult?>(null)
    }
    val offsets = remember(text) { codePointOffsets(text) }
    Text(
        text = text,
        style = style,
        fontWeight = fontWeight,
        color = baseColor,
        textAlign = textAlign,
        onTextLayout = { layoutResult = it },
        modifier = modifier
            .then(
                if (showText) {
                    Modifier
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            )
            .drawWithContent {
                if (!showText) return@drawWithContent
                val layout = layoutResult ?: return@drawWithContent
                val codePointCount = offsets.lastIndex
                if (completed) {
                    // Keep the custom paint path at completion; only the number of clipped draws
                    // collapses to one. The Text and its TextLayoutResult never change identity.
                    drawText(layout, color = baseColor)
                    return@drawWithContent
                }

                val visible = visibleCodePoints.coerceIn(0, codePointCount)
                var opaqueCodePoints = 0
                while (
                    opaqueCodePoints < visible &&
                    typingGlyphAlpha(
                        codePointIndex = opaqueCodePoints,
                        elapsedMs = elapsedMs,
                        initialDelayMs = initialDelayMs,
                        typeSpeedMs = typeSpeedMs,
                        gradientFadeDurationMs = gradientFadeDurationMs,
                    ) >= 0.999f
                ) {
                    opaqueCodePoints++
                }

                if (opaqueCodePoints > 0) {
                    clipPath(layout.getPathForRange(0, offsets[opaqueCodePoints])) {
                        drawText(layout, color = baseColor)
                    }
                }
                for (index in opaqueCodePoints until visible) {
                    val alpha = typingGlyphAlpha(
                        codePointIndex = index,
                        elapsedMs = elapsedMs,
                        initialDelayMs = initialDelayMs,
                        typeSpeedMs = typeSpeedMs,
                        gradientFadeDurationMs = gradientFadeDurationMs,
                    )
                    if (alpha <= 0f) continue
                    val start = offsets[index]
                    val end = offsets[index + 1]
                    clipPath(layout.getPathForRange(start, end)) {
                        drawText(
                            textLayoutResult = layout,
                            color = baseColor,
                            alpha = alpha,
                        )
                    }
                }
            },
    )
}

private fun codePointOffsets(text: String): IntArray {
    val count = text.codePointCount(0, text.length)
    val offsets = IntArray(count + 1)
    var charOffset = 0
    repeat(count) { index ->
        offsets[index] = charOffset
        charOffset += Character.charCount(text.codePointAt(charOffset))
    }
    offsets[count] = text.length
    return offsets
}

private fun typingGlyphAlpha(
    codePointIndex: Int,
    elapsedMs: Long,
    initialDelayMs: Int,
    typeSpeedMs: Int,
    gradientFadeDurationMs: Int,
): Float {
    val revealedAtMs =
        initialDelayMs.toLong() + (codePointIndex + 1L) * typeSpeedMs
    val ageFraction =
        ((elapsedMs - revealedAtMs).toFloat() / gradientFadeDurationMs)
            .coerceIn(0f, 1f)
    return 0.16f + 0.84f * ageFraction
}

private fun buildCursorTypingText(
    text: String,
    visibleCodePoints: Int,
    completed: Boolean,
    elapsedMs: Long,
    cursor: TypewriterCursor,
    baseColor: Color,
): AnnotatedString = buildAnnotatedString {
    if (completed) {
        append(text)
        return@buildAnnotatedString
    }

    var charOffset = 0
    repeat(visibleCodePoints) {
        val start = charOffset
        charOffset += Character.charCount(text.codePointAt(charOffset))
        append(text, start, charOffset)
    }

    val cursorStart = length
    append(cursor.glyph)
    val cycleMs = 1_060L
    val halfCycleMs = cycleMs / 2L
    val phaseMs = elapsedMs.mod(cycleMs)
    val cursorAlpha =
        if (phaseMs <= halfCycleMs) {
            1f - phaseMs.toFloat() / halfCycleMs
        } else {
            (phaseMs - halfCycleMs).toFloat() / halfCycleMs
        }
    addStyle(
        SpanStyle(color = baseColor.copy(alpha = baseColor.alpha * cursorAlpha)),
        start = cursorStart,
        end = length,
    )
}
