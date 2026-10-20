package com.newoether.agora.util

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect

private const val MAX_GRADIENT_BLUR_DP = 5f

/**
 * Two-pass top-edge blur with an optional bottom alpha fade in the same drawing layer.
 *
 * The larger requested radius is capped at 5 dp and falls to zero over the top 150 dp.
 * Both radius parameters retain their existing maximum-only semantics.
 *
 * Android 13+ applies the blur after the optional foreground alpha mask. Older devices
 * and a zero radius retain only that mask, without creating a RuntimeShader.
 */
fun Modifier.gradientBlur(
    blurAtTopDp: Float,
    blurAtBottomDp: Float,
    fadeHeightDp: Float = 0f,
    bottomOverlayHeight: Dp = 0.dp,
): Modifier = composed {
    if (com.newoether.agora.ui.motion.LocalAgoraMotionPolicy.current.eInkMode) return@composed Modifier
    val density = LocalDensity.current.density
    val maxBlurPx = maxOf(blurAtTopDp, blurAtBottomDp)
        .coerceAtMost(MAX_GRADIENT_BLUR_DP) * density
    val fadeRangePx = 150f * density

    val renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxBlurPx > 0f) {
        val horizontalShader = remember(maxBlurPx, fadeRangePx) {
            RuntimeShader(VARIABLE_BLUR_SHADER).apply {
                setFloatUniform("uParams", maxBlurPx, fadeRangePx)
                setFloatUniform("uDirection", 1f, 0f)
            }
        }
        val verticalShader = remember(maxBlurPx, fadeRangePx) {
            RuntimeShader(VARIABLE_BLUR_SHADER).apply {
                setFloatUniform("uParams", maxBlurPx, fadeRangePx)
                setFloatUniform("uDirection", 0f, 1f)
            }
        }

        remember(horizontalShader, verticalShader) {
            val horizontal = RenderEffect.createRuntimeShaderEffect(horizontalShader, "content")
            val vertical = RenderEffect.createRuntimeShaderEffect(verticalShader, "content")
            RenderEffect.createChainEffect(vertical, horizontal).asComposeRenderEffect()
        }
    } else {
        null
    }

    Modifier.verticalBottomOverlayFade(fadeHeightDp, bottomOverlayHeight, renderEffect)
}

/**
 * Gradient blur with edge fade at both top and bottom.
 * Blur ramps from 0 at the edge to [maxBlurDp] over [edgeFadeDp] distance.
 */
fun Modifier.gradientBlurEdges(maxBlurDp: Float, edgeFadeDp: Float = 20f, topWeight: Float = 1f, bottomWeight: Float = 1f): Modifier = composed {
    if (com.newoether.agora.ui.motion.LocalAgoraMotionPolicy.current.eInkMode) return@composed Modifier
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@composed this
    if (topWeight <= 0f && bottomWeight <= 0f) return@composed this

    val density = LocalDensity.current.density
    val maxBlurPx = maxBlurDp.coerceAtMost(MAX_GRADIENT_BLUR_DP) * density
    if (maxBlurPx <= 0f) return@composed this

    val fadePx = edgeFadeDp * density
    var composableHPx by remember { mutableFloatStateOf(2400f) }

    val horizontalShader = remember(maxBlurPx, fadePx, topWeight, bottomWeight, composableHPx) {
        RuntimeShader(EDGE_BLUR_SHADER).apply {
            setFloatUniform("uMaxBlur", maxBlurPx)
            setFloatUniform("uFade", fadePx)
            setFloatUniform("uH", composableHPx)
            setFloatUniform("uWeights", topWeight, bottomWeight)
            setFloatUniform("uDirection", 1f, 0f)
        }
    }
    val verticalShader = remember(maxBlurPx, fadePx, topWeight, bottomWeight, composableHPx) {
        RuntimeShader(EDGE_BLUR_SHADER).apply {
            setFloatUniform("uMaxBlur", maxBlurPx)
            setFloatUniform("uFade", fadePx)
            setFloatUniform("uH", composableHPx)
            setFloatUniform("uWeights", topWeight, bottomWeight)
            setFloatUniform("uDirection", 0f, 1f)
        }
    }

    val renderEffect = remember(horizontalShader, verticalShader) {
        val horizontal = RenderEffect.createRuntimeShaderEffect(horizontalShader, "content")
        val vertical = RenderEffect.createRuntimeShaderEffect(verticalShader, "content")
        RenderEffect.createChainEffect(vertical, horizontal).asComposeRenderEffect()
    }

    Modifier
        .onSizeChanged { composableHPx = it.height.toFloat() }
        .graphicsLayer { this.renderEffect = renderEffect }
}

/**
 * Applies the same top-edge falloff with a single requested radius.
 */
fun Modifier.gradientBlur(radiusDp: Float): Modifier =
    gradientBlur(radiusDp, radiusDp)

internal fun bottomOverlayFadeStops(
    canvasHeightPx: Float,
    fadeHeightPx: Float,
    bottomOverlayHeightPx: Float,
): Pair<Float, Float> {
    val height = canvasHeightPx.coerceAtLeast(1f)
    val overlayHeight = bottomOverlayHeightPx.coerceIn(0f, height)
    val fadeStart = ((height - overlayHeight) / height).coerceIn(0f, 1f)
    val fadeEnd = ((height - overlayHeight + fadeHeightPx.coerceAtLeast(0f)) / height)
        .coerceIn(fadeStart, 1f)
    return fadeStart to fadeEnd
}

/**
 * Fades foreground content through the top edge of a bottom overlay while leaving the real
 * background beneath this layer untouched. An optional effect processes the masked content
 * in this same layer, avoiding an additional full-viewport offscreen layer.
 */
fun Modifier.verticalBottomOverlayFade(
    fadeHeightDp: Float,
    bottomOverlayHeight: Dp,
    renderEffect: ComposeRenderEffect? = null,
): Modifier = composed {
    if (com.newoether.agora.ui.motion.LocalAgoraMotionPolicy.current.eInkMode) return@composed Modifier
    val density = LocalDensity.current.density
    val fadeHeightPx = fadeHeightDp.coerceAtLeast(0f) * density
    val bottomOverlayHeightPx = bottomOverlayHeight.value.coerceAtLeast(0f) * density
    val hasFade = fadeHeightPx > 0f && bottomOverlayHeightPx > 0f
    if (!hasFade && renderEffect == null) return@composed this

    val layer = Modifier.graphicsLayer {
        this.renderEffect = renderEffect
        compositingStrategy = if (hasFade) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    if (!hasFade) return@composed layer

    layer.drawWithCache {
        val (fadeStart, fadeEnd) = bottomOverlayFadeStops(
            canvasHeightPx = size.height,
            fadeHeightPx = fadeHeightPx,
            bottomOverlayHeightPx = bottomOverlayHeightPx,
        )
        val fadeBrush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Black,
                fadeStart to Color.Black,
                fadeEnd to Color.Transparent,
                1f to Color.Transparent,
            ),
        )
        onDrawWithContent {
            drawContent()
            drawRect(brush = fadeBrush, blendMode = BlendMode.DstIn)
        }
    }
}

/**
 * Fades content alpha near the top and bottom edges.
 *
 * Use this for scrollable list edges when the goal is to soften clipping rather
 * than optically blur the pixels. It avoids the per-pixel sampling cost of blur.
 */
fun Modifier.verticalEdgeFade(edgeFadeDp: Float = 20f, topWeight: Float = 1f, bottomWeight: Float = 1f): Modifier = composed {
    if (com.newoether.agora.ui.motion.LocalAgoraMotionPolicy.current.eInkMode) return@composed Modifier
    val density = LocalDensity.current.density
    val fadePx = edgeFadeDp.coerceAtLeast(0f) * density
    val topAlpha = topWeight.coerceIn(0f, 1f)
    val bottomAlpha = bottomWeight.coerceIn(0f, 1f)

    if (fadePx <= 0f || (topAlpha <= 0f && bottomAlpha <= 0f)) return@composed this

    Modifier
        .graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
        }
        .drawWithContent {
            drawContent()

            val height = size.height.coerceAtLeast(1f)
            val normalizedFade = (fadePx / height).coerceIn(0f, 0.5f)
            val topFadeEnd = normalizedFade
            val bottomFadeStart = 1f - normalizedFade
            val opaque = Color.Black

            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to opaque.copy(alpha = 1f - topAlpha),
                        topFadeEnd to opaque,
                        bottomFadeStart to opaque,
                        1f to opaque.copy(alpha = 1f - bottomAlpha)
                    )
                ),
                blendMode = BlendMode.DstIn
            )
        }
}

/** Draw-only horizontal counterpart of the drawer's vertical alpha mask. */
fun Modifier.horizontalEdgeFade(
    edgeFadeDp: Float = 40f,
    leftWeight: Float = 1f,
    rightWeight: Float = 1f,
): Modifier = composed {
    val fadePx = edgeFadeDp.coerceAtLeast(0f) * LocalDensity.current.density
    val leftAlpha = leftWeight.coerceIn(0f, 1f)
    val rightAlpha = rightWeight.coerceIn(0f, 1f)
    if (fadePx <= 0f || (leftAlpha <= 0f && rightAlpha <= 0f)) return@composed this

    Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = (fadePx / size.width.coerceAtLeast(1f)).coerceIn(0f, 0.5f)
            drawRect(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to Color.Black.copy(alpha = 1f - leftAlpha),
                        fade to Color.Black,
                        (1f - fade) to Color.Black,
                        1f to Color.Black.copy(alpha = 1f - rightAlpha),
                    ),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

/**
 * Gradient blur using a fixed 9-tap separable kernel.
 *
 * The previous shader used a dense 2D grid and evaluated exp() for every tap,
 * which was much too expensive for a scrolling list. This version chains a
 * horizontal pass and a vertical pass. Each pass samples 9 texels with constant
 * Gaussian weights, so the total drops from hundreds of texture reads per pixel
 * to 18 reads with no dynamic loops.
 */
private val VARIABLE_BLUR_SHADER = """
    uniform shader content;
    uniform float2 uParams;       // x = maxBlur (px), y = fadeRange (px)
    uniform float2 uDirection;    // one pass uses (1, 0), the other uses (0, 1)

    half4 main(float2 coord) {
        float t = saturate(coord.y / uParams.y);
        float s = uParams.x * (1.0 - t);
        if (s < 0.5) return content.eval(coord);

        float2 axis = uDirection * s;
        half4 accum = half4(content.eval(coord)) * 0.24084130;
        accum += half4(content.eval(coord + axis * 0.6)) * 0.20116756;
        accum += half4(content.eval(coord - axis * 0.6)) * 0.20116756;
        accum += half4(content.eval(coord + axis * 1.2)) * 0.11723004;
        accum += half4(content.eval(coord - axis * 1.2)) * 0.11723004;
        accum += half4(content.eval(coord + axis * 1.8)) * 0.04766218;
        accum += half4(content.eval(coord - axis * 1.8)) * 0.04766218;
        accum += half4(content.eval(coord + axis * 2.4)) * 0.01351957;
        accum += half4(content.eval(coord - axis * 2.4)) * 0.01351957;
        return accum;
    }
""".trimIndent()

/**
 * Edge-fade blur shader. Blur is strongest at edges, fading to 0 toward
 * center over [uFade] pixels. Global weights [uWeights] decouple top/bottom.
 */
private val EDGE_BLUR_SHADER = """
    uniform shader content;
    uniform float uMaxBlur;    // blur at edge (4dp → px)
    uniform float uFade;       // fade-in distance (40dp → px)
    uniform float uH;          // composable height in px
    uniform float2 uWeights;   // x = top global multiplier, y = bottom global multiplier
    uniform float2 uDirection;

    half4 main(float2 coord) {
        if (uWeights.x <= 0.0 && uWeights.y <= 0.0) return content.eval(coord);
        float t = saturate(1.0 - coord.y / uFade) * uWeights.x;
        float b = saturate(1.0 - (uH - coord.y) / uFade) * uWeights.y;
        float s = uMaxBlur * max(t, b);
        if (s < 0.5) return content.eval(coord);

        float2 axis = uDirection * s;
        half4 accum = half4(content.eval(coord)) * 0.24084130;
        accum += half4(content.eval(coord + axis * 0.6)) * 0.20116756;
        accum += half4(content.eval(coord - axis * 0.6)) * 0.20116756;
        accum += half4(content.eval(coord + axis * 1.2)) * 0.11723004;
        accum += half4(content.eval(coord - axis * 1.2)) * 0.11723004;
        accum += half4(content.eval(coord + axis * 1.8)) * 0.04766218;
        accum += half4(content.eval(coord - axis * 1.8)) * 0.04766218;
        accum += half4(content.eval(coord + axis * 2.4)) * 0.01351957;
        accum += half4(content.eval(coord - axis * 2.4)) * 0.01351957;
        return accum;
    }
""".trimIndent()
