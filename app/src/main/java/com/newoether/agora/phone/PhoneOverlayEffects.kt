package com.newoether.agora.phone

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.ProvideAgoraMotionPolicy
import com.newoether.agora.ui.theme.*
import kotlin.math.*

@Composable
internal fun PhoneOverlayTheme(settings: SettingsRepository, content: @Composable () -> Unit) {
    val mode by settings.themeMode.collectAsState()
    val preset by settings.colorScheme.collectAsState()
    val style by settings.schemeStyle.collectAsState()
    val dynamic by settings.dynamicColor.collectAsState()
    val amoled by settings.amoledEnabled.collectAsState()
    val font by settings.fontPreference.collectAsState()
    val fontPath by settings.customFontPath.collectAsState()
    val reduce by settings.reduceMotion.collectAsState()
    AgoraTheme(
        themeMode = runCatching { ThemeMode.valueOf(mode) }.getOrDefault(ThemeMode.FOLLOW_DEVICE),
        colorSchemePreset = runCatching { ColorSchemePreset.valueOf(preset) }.getOrDefault(ColorSchemePreset.FOREST),
        schemeStyle = runCatching { SchemeStyle.valueOf(style) }.getOrDefault(SchemeStyle.TONAL_SPOT),
        dynamicColor = dynamic, amoledEnabled = amoled, fontPreference = font, customFontPath = fontPath,
    ) {
        // Match the activity root Surface without painting over the underlying application.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            ProvideAgoraMotionPolicy(reduce) { content() }
        }
    }
}

@Composable
internal fun PhoneEffect(runtime: PhoneUseRuntime, overlay: PhoneOverlay) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary
    val noise = remember { phoneNoiseBrush() }
    val motion = LocalAgoraMotionPolicy.current
    val reduce = motion.reduceMotion
    val reveal = remember { Animatable(0f) }
    val visible = overlay.visible.value
    LaunchedEffect(visible, reduce, motion.systemAnimationsDisabled) {
        val target = if (visible) 1f else 0f
        if (motion.systemAnimationsDisabled) reveal.snapTo(target)
        else reveal.animateTo(target, tween(if (reduce) 180 else if (visible) 400 else 240,
            easing = FastOutSlowInEasing))
    }
    val cursor by runtime.cursor.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(cursor) {
        val current = cursor ?: return@LaunchedEffect
        while (SystemClock.elapsedRealtime() - current.startedAt < current.durationMs + 1000) {
            withFrameNanos { now = SystemClock.elapsedRealtime() }
        }
    }
    val view = LocalView.current
    val corners = rememberPhoneCorners(view)
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        origin = Offset(location[0].toFloat(), location[1].toFloat())
    }.drawWithCache {
        val paths = phoneGlowBands(
            size, corners, fallbackRadius = 28.dp.toPx(), minRadius = GLOW_MIN_CORNER_DP.dp.toPx(),
            step = 6.dp.toPx(), depths = (1..GLOW_LAYERS).map { it.dp.toPx() },
        )
        val glow = Brush.sweepGradient(listOf(primary, tertiary, secondary, primary), center = size.center)
        onDrawBehind {
            val progress = reveal.value
            val band = 90.dp.toPx()
            val top = size.height - (size.height + band) * progress
            // The glow is grained in its own layer: the noise tile keeps a random share of each pixel.
            drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), Paint()) }
            if (reduce) phoneBorder(paths, glow, progress)
            else {
                val revealed = (top + band).coerceIn(0f, size.height)
                if (revealed < size.height) clipRect(top = revealed) { phoneBorder(paths, glow, 1f) }
                for (slice in 0 until 24) {
                    val start = (top + band * slice / 24).coerceIn(0f, size.height)
                    val end = (top + band * (slice + 1) / 24).coerceIn(0f, size.height)
                    if (end > start) {
                        val strength = (slice + 0.5f) / 24
                        clipRect(top = start, bottom = end) {
                            phoneBorder(paths, glow, strength, sin(PI * strength).toFloat())
                        }
                    }
                }
            }
            drawRect(noise, blendMode = BlendMode.DstIn)
            drawIntoCanvas { it.restore() }
            val current = cursor
            if (current != null && !overlay.capturing.value) {
                val elapsed = (now - current.startedAt).coerceAtLeast(0)
                val gesture = (elapsed.toFloat() / current.durationMs).coerceIn(0f, 1f)
                val eased = gesture * gesture * (3 - 2 * gesture)
                val position = if (reduce) Offset(current.endX.toFloat(), current.endY.toFloat()) else Offset(
                    current.x + (current.endX - current.x) * eased, current.y + (current.endY - current.y) * eased)
                val after = elapsed - current.durationMs
                val alpha = when { after < 600 -> 1f; else -> (1 - (after - 600) / 200f).coerceIn(0f, 1f) }
                val press = if (reduce) 1f else when {
                    elapsed < 90 -> 1f - 0.25f * elapsed / 90
                    gesture < 1 -> 0.75f + if (current.kind == "phone_long_press") 0.045f * sin(elapsed / 200f) else 0f
                    else -> 1f + 0.16f * exp(-after / 100f) * sin(after / 35f)
                }
                drawCircle(primary.copy(alpha = alpha), 10.dp.toPx() * press, position - origin)
            }
        }
    })
}
private const val GLOW_LAYERS = 28
/** Corner radius the glow keeps where the display reports a smaller one, e.g. a virtual display. */
private const val GLOW_MIN_CORNER_DP = 20

/**
 * A soft inner glow with no hard line, so a platform corner radius that is a little off does not
 * show. Band n reaches about n dp in from the edge, more or less along the outline; overlapping
 * bands make the glow fade inward.
 */
private fun DrawScope.phoneBorder(paths: List<Path>, brush: Brush, alpha: Float, glow: Float = 0f) {
    for (index in GLOW_LAYERS downTo 1) {
        val opacity = (alpha * (0.08f + glow * 0.08f) * (1f - index / (GLOW_LAYERS + 4f))).coerceIn(0f, 1f)
        drawPath(paths[index - 1], brush, alpha = opacity)
    }
}

/** A tiled grain mask: 2 px cells with random coverage, fixed seed so it never flickers. */
private fun phoneNoiseBrush(): ShaderBrush {
    val cells = 192
    val cell = 2
    val random = java.util.Random(7)
    val pixels = IntArray(cells * cell * cells * cell)
    val side = cells * cell
    for (y in 0 until cells) for (x in 0 until cells) {
        val roll = random.nextFloat()
        // Mostly partial coverage, with a few full grains and a few gaps for the stippled look.
        val coverage = when { roll < 0.18f -> 0.15f; roll > 0.9f -> 1f; else -> 0.4f + 0.45f * random.nextFloat() }
        val argb = (coverage * 255).toInt() shl 24 or 0xFFFFFF
        for (dy in 0 until cell) for (dx in 0 until cell) pixels[(y * cell + dy) * side + x * cell + dx] = argb
    }
    val bitmap = android.graphics.Bitmap.createBitmap(pixels, side, side, android.graphics.Bitmap.Config.ARGB_8888)
    return ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}
