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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.newoether.agora.viewmodel.SpeechLevels
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.exp

private const val TICK_NANOS = SPEECH_LEVEL_TICK_MS * 1_000_000.0

/**
 * How many ticks the scroll runs behind the sample clock. Sample n is due at tick n but reaches
 * the right edge only at tick n + [HEAD_LAG] - 1, so a sample delivered up to one tick late is
 * still off-screen when it arrives and never pops in.
 */
private const val HEAD_LAG = 2.0

/**
 * Loudness history that scrolls continuously instead of stepping one bar per sample. Each new
 * sample enters past the right edge and slides in over the measured sample interval, and both
 * ends fade out through an alpha mask. While [transcribing], the history freezes and a soft
 * highlight sweeps across it, so the capsule keeps moving while the transcript is being made.
 */
@Composable
internal fun ComposerLevelHistory(
    levels: StateFlow<SpeechLevels>,
    transcribing: Boolean,
    color: Color,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    // Scroll position in ticks on the producer's clock. Bars are placed by their absolute sample
    // number, so neither arrival jitter nor conflated emissions (two ticks in one frame) can
    // move them; only the frame time does.
    val head = remember { mutableDoubleStateOf(0.0) }
    val frozen by rememberUpdatedState(transcribing)
    LaunchedEffect(levels) {
        while (true) {
            withFrameNanos { now ->
                val current = levels.value
                if (!frozen && current.startNanos != 0L) {
                    head.doubleValue = (now - current.startNanos) / TICK_NANOS - HEAD_LAG
                }
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
        val history = levels.value
        val bars = history.samples
        val position = head.doubleValue
        val strength = sweepStrength
        val sweepCenter = sweep?.let { -0.25f + 1.5f * it.value } ?: 0f
        clipRect {
            for (k in bars.indices.reversed()) {
                // Sample number n is due at tick n and enters at the right edge at head n - 1.
                val n = history.total - (bars.size - 1 - k)
                val x = size.width - step * (position - n + 1).toFloat()
                if (x >= size.width) continue
                if (x + barWidth < 0f) break
                val level = bars[k]
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
