package com.newoether.agora.ui.chat.bottombar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.newoether.agora.viewmodel.SPEECH_LEVEL_TICK_MS
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.exp

/** Frame-clock state for the scrolling history; read only while drawing, so it never recomposes. */
private class LevelScroll {
    var drawn: List<Float> = emptyList()
    var seen: List<Float>? = null
    /** Samples received since the history was last cleared. */
    var received = 0
    /** Scroll position in samples; the newest bar is fully in view at [received]. */
    var head = 0f
    var lastFrame = 0L
}

private const val TICK_NANOS = SPEECH_LEVEL_TICK_MS * 1_000_000f

/**
 * Where [head] settles behind the newest sample. Half a sample of slack absorbs early and late
 * ticks, so arrival jitter changes the scroll speed slightly instead of moving the bars in steps.
 */
private const val HEAD_LAG = 0.5f

/** How strongly the scroll speed corrects toward [HEAD_LAG] per sample of error. */
private const val HEAD_GAIN = 0.6f

/**
 * Loudness history that scrolls continuously instead of stepping one bar per sample. Each new
 * sample enters past the right edge and slides in over the measured sample interval, and both
 * ends fade out through an alpha mask. While [transcribing], the history freezes and a soft
 * highlight sweeps across it, so the capsule keeps moving while the transcript is being made.
 */
@Composable
internal fun ComposerLevelHistory(
    levels: StateFlow<List<Float>>,
    transcribing: Boolean,
    color: Color,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    val scroll = remember { LevelScroll() }
    // 0: the newest bar sits just past the right edge. 1: it has slid fully into view.
    val shift = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(levels) {
        while (true) {
            withFrameNanos { now ->
                val current = levels.value
                if (current !== scroll.seen) {
                    val previous = scroll.seen
                    scroll.seen = current
                    scroll.drawn = current
                    if (previous == null || current.size < previous.size) {
                        // A cleared or first history has nothing to slide in.
                        scroll.received = current.size
                        scroll.head = current.size.toFloat()
                    } else {
                        // Each emission appends one sample, also once the list is capped.
                        scroll.received += 1
                    }
                }
                // Advance at the nominal tick rate, nudged toward a fixed lag behind the newest sample.
                val dt = if (scroll.lastFrame == 0L) 0L else now - scroll.lastFrame
                scroll.lastFrame = now
                val target = scroll.received - HEAD_LAG
                val rate = (1f + HEAD_GAIN * (target - scroll.head)).coerceIn(0.25f, 3f)
                scroll.head = (scroll.head + dt / TICK_NANOS * rate)
                    .coerceIn(scroll.received - 2f, scroll.received.toFloat())
                shift.floatValue = (scroll.head - (scroll.received - 1)).coerceIn(-1f, 1f)
            }
        }
    }
    val sweepStrength by animateFloatAsState(
        targetValue = if (transcribing) 1f else 0f,
        animationSpec = tween(durationMillis = 240),
        label = "levelSweepStrength",
    )
    val sweep = if (transcribing || sweepStrength > 0f) {
        val transition = rememberInfiniteTransition(label = "levelSweep")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
            label = "levelSweepPosition",
        )
    } else {
        null
    }
    Canvas(
        modifier.graphicsLayer {
            this.alpha = alpha()
            // The edge mask needs its own layer so DstIn only cuts this canvas.
            compositingStrategy = CompositingStrategy.Offscreen
        },
    ) {
        val barWidth = 3.dp.toPx()
        val step = barWidth + 2.dp.toPx()
        val bars = scroll.drawn
        val offset = shift.floatValue
        val strength = sweepStrength
        val sweepCenter = sweep?.let { -0.25f + 1.5f * it.value } ?: 0f
        val count = minOf(bars.size, (size.width / step).toInt() + 2)
        clipRect {
            for (k in 0 until count) {
                val level = bars[bars.size - 1 - k]
                val x = size.width - step * (k + offset)
                if (x + barWidth < 0f) break
                val u = (x + barWidth / 2f) / size.width
                val glow = if (strength > 0f) {
                    val d = (u - sweepCenter) / 0.14f
                    strength * exp(-d * d)
                } else {
                    0f
                }
                val barAlpha = 0.9f - strength * 0.5f + glow * 0.55f
                val height = maxOf(barWidth, level * size.height * (1f + glow * 0.2f)).coerceAtMost(size.height)
                drawRoundRect(
                    color = color.copy(alpha = barAlpha.coerceIn(0f, 1f)),
                    topLeft = Offset(x, (size.height - height) / 2f),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }
        val fade = (16.dp.toPx() / size.width).coerceAtMost(0.5f)
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                fade to Color.Black,
                1f - fade to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
}
