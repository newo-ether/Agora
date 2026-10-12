package com.newoether.agora.ui.components

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.util.lerp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import com.newoether.agora.ui.motion.animateFloat

private data class BlobSpec(
    val centerXFrac: Float,
    val centerYFrac: Float,
    val radiusDp: Float,
    val xAmp: Float,
    val yAmp: Float,
    val xPeriodSec: Float,
    val yPeriodSec: Float,
)

@Composable
fun AnimatedBlobBackground(
    modifier: Modifier = Modifier,
    blurRadius: Float = 40f,
    centerAlpha: Float = 0.10f,
    quarterAlpha: Float = 0.05f,
    edgeAlpha: Float = 0.0f,
    dark: Boolean = true,
    // When false, neither the RenderEffect blur nor the ~16ms animation loop run. This is the
    // root-cause fix for the system photo picker being composited as transparent on some HWC
    // paths (e.g. Moto g84 / Android 15): an unconditional, every-frame RenderEffect layer on the
    // bottom-most background got promoted to an overlay and clobbered the picker's z-order. Gating
    // it on the existing "Blur Effects" setting both gives the user a real escape hatch (the
    // setting previously had no effect on this layer) and restores correct composition when off.
    blurEnabled: Boolean = true,
    // Motion is useful on the welcome surface, where the blobs are prominent. Chat/streaming uses
    // a nearly transparent background; keeping it static prevents an invisible full-screen
    // animation from competing with text layout and scrolling for every frame.
    motionEnabled: Boolean = true,
) {
    if (com.newoether.agora.ui.motion.LocalAgoraMotionPolicy.current.eInkMode) return
    val cs = MaterialTheme.colorScheme
    val blobColor = if (dark) Color(
        red = lerp(cs.primaryContainer.red, cs.primary.red, 0.3f) * 0.5f,
        green = lerp(cs.primaryContainer.green, cs.primary.green, 0.3f) * 0.5f,
        blue = lerp(cs.primaryContainer.blue, cs.primary.blue, 0.3f) * 0.5f,
        alpha = cs.primaryContainer.alpha,
    ) else Color(
        red = lerp(cs.background.red, cs.primary.red, 0.2f),
        green = lerp(cs.background.green, cs.primary.green, 0.2f),
        blue = lerp(cs.background.blue, cs.primary.blue, 0.2f),
        alpha = cs.background.alpha,
    )
    val blobColors = List(4) { blobColor }

    val blobs = remember {
        val rng = Random(System.nanoTime())
        List(4) {
            BlobSpec(
                centerXFrac = rng.nextFloat() * 0.8f + 0.1f,
                centerYFrac = rng.nextFloat() * 0.7f + 0.15f,
                radiusDp = rng.nextFloat() * 40f + 180f,
                xAmp = rng.nextFloat() * 0.08f + 0.06f,
                yAmp = rng.nextFloat() * 0.08f + 0.06f,
                xPeriodSec = rng.nextFloat() * 12f + 10f,
                yPeriodSec = rng.nextFloat() * 12f + 8f,
            )
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }

        if (motionEnabled && blurEnabled) {
            val motion = rememberInfiniteTransition(label = "blobMotion")
            blobs.forEachIndexed { index, blob ->
                val xPhase = motion.blobPhase(blob.xPeriodSec, "blobX$index")
                val yPhase = motion.blobPhase(blob.yPeriodSec, "blobY$index")
                BlobLayer(
                    blob = blob,
                    index = index,
                    color = blobColors[index],
                    centerAlpha = centerAlpha,
                    quarterAlpha = quarterAlpha,
                    edgeAlpha = edgeAlpha,
                    blurRadius = blurRadius,
                    blurEnabled = true,
                    containerWidth = maxWidth,
                    containerHeight = maxHeight,
                    containerWidthPx = containerWidthPx,
                    containerHeightPx = containerHeightPx,
                    xPhase = xPhase,
                    yPhase = yPhase,
                )
            }
        } else {
            blobs.forEachIndexed { index, blob ->
                BlobLayer(
                    blob = blob,
                    index = index,
                    color = blobColors[index],
                    centerAlpha = centerAlpha,
                    quarterAlpha = quarterAlpha,
                    edgeAlpha = edgeAlpha,
                    blurRadius = blurRadius,
                    blurEnabled = blurEnabled,
                    containerWidth = maxWidth,
                    containerHeight = maxHeight,
                    containerWidthPx = containerWidthPx,
                    containerHeightPx = containerHeightPx,
                    xPhase = null,
                    yPhase = null,
                )
            }
        }

        Canvas(modifier = Modifier.fillMaxSize().alpha(0.12f)) {
            val primary = blobColors[0]
            val tertiary = blobColors[2]
            drawRect(
                brush = Brush.linearGradient(
                    0.0f to primary.copy(alpha = 0.6f),
                    0.5f to tertiary.copy(alpha = 0.3f),
                    1.0f to primary.copy(alpha = 0.6f),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                )
            )
            drawRect(
                brush = Brush.linearGradient(
                    0.0f to Color.Transparent,
                    1.0f to primary.copy(alpha = 0.2f),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, 0f),
                )
            )
        }
    }
}

@Composable
private fun InfiniteTransition.blobPhase(
    periodSeconds: Float,
    label: String,
): State<Float> = animateFloat(
    initialValue = 0f,
    targetValue = (2.0 * PI).toFloat(),
    animationSpec = infiniteRepeatable(
        animation = tween(
            durationMillis = (periodSeconds * 1_000f).toInt().coerceAtLeast(1),
            easing = LinearEasing,
        ),
    ),
    label = label,
)

@Composable
private fun BlobLayer(
    blob: BlobSpec,
    index: Int,
    color: Color,
    centerAlpha: Float,
    quarterAlpha: Float,
    edgeAlpha: Float,
    blurRadius: Float,
    blurEnabled: Boolean,
    containerWidth: Dp,
    containerHeight: Dp,
    containerWidthPx: Float,
    containerHeightPx: Float,
    xPhase: State<Float>?,
    yPhase: State<Float>?,
) {
    val density = LocalDensity.current
    val radius = blob.radiusDp.dp
    val blurPadding = if (blurEnabled) blurRadius.dp else 0.dp
    val halfExtent = radius + blurPadding
    val baseX = containerWidth * blob.centerXFrac - halfExtent
    val baseY = containerHeight * blob.centerYFrac - halfExtent
    val phaseOffset = index.toDouble() * 1.3

    Canvas(
        modifier = Modifier
            .offset(x = baseX, y = baseY)
            .size(halfExtent * 2f)
            // The blurred circle below is a static cached layer. Animation updates only these
            // translation properties, avoiding Canvas recomposition, re-recording, remeasure and
            // a full-screen RenderEffect on every vsync.
            .graphicsLayer {
                val horizontalPhase = xPhase?.value
                val verticalPhase = yPhase?.value
                translationX = if (horizontalPhase == null) {
                    0f
                } else {
                    containerWidthPx * blob.xAmp *
                        sin(horizontalPhase.toDouble() + phaseOffset).toFloat()
                }
                translationY = if (verticalPhase == null) {
                    0f
                } else {
                    containerHeightPx * blob.yAmp *
                        cos(verticalPhase.toDouble() + phaseOffset).toFloat()
                }
            }
            .then(if (blurEnabled) Modifier.blur(radius = blurRadius.dp) else Modifier)
    ) {
        val radiusPx = with(density) { radius.toPx() }
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to color.copy(alpha = centerAlpha),
                0.25f to color.copy(alpha = quarterAlpha),
                1.0f to color.copy(alpha = edgeAlpha),
                center = center,
                radius = radiusPx,
            ),
            radius = radiusPx,
            center = center,
        )
    }
}
