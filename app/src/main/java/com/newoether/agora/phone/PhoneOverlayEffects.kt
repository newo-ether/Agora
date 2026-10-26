package com.newoether.agora.phone

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
    ) { ProvideAgoraMotionPolicy(reduce) { content() } }
}

@Composable
internal fun PhoneEffect(runtime: PhoneUseRuntime, overlay: PhoneOverlay) {
    val primary = MaterialTheme.colorScheme.primary
    val reduce = LocalAgoraMotionPolicy.current.reduceMotion
    val reveal = remember { Animatable(0f) }
    val visible = overlay.visible.value
    LaunchedEffect(visible, reduce) { reveal.animateTo(if (visible) 1f else 0f, tween(if (reduce) 180 else 240)) }
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
        val paths = (1..18).associateWith { index ->
            phoneBorderPath(size, corners, index.dp.toPx() / 2, 28.dp.toPx())
        }
        onDrawBehind {
            val progress = reveal.value
            val band = 90.dp.toPx()
            val top = size.height - (size.height + band) * progress
            if (reduce) phoneBorder(paths, primary, progress)
            else {
                val revealed = (top + band).coerceIn(0f, size.height)
                if (revealed < size.height) clipRect(top = revealed) { phoneBorder(paths, primary, 1f) }
                for (slice in 0 until 24) {
                    val start = (top + band * slice / 24).coerceIn(0f, size.height)
                    val end = (top + band * (slice + 1) / 24).coerceIn(0f, size.height)
                    if (end > start) {
                        val strength = (slice + 0.5f) / 24
                        clipRect(top = start, bottom = end) {
                            phoneBorder(paths, primary, strength, sin(PI * strength).toFloat())
                        }
                    }
                }
            }
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
private fun DrawScope.phoneBorder(paths: Map<Int, Path>, color: Color, alpha: Float, glow: Float = 0f) {
    for (index in 18 downTo 1) {
        val width = index.dp.toPx()
        val opacity = (alpha * (0.035f + glow * 0.065f) * (1f - index / 20f)).coerceIn(0f, 1f)
        drawPath(checkNotNull(paths[index]), color.copy(alpha = opacity), style = Stroke(width))
    }
    drawPath(checkNotNull(paths[2]), color.copy(alpha = (alpha * 0.8f).coerceIn(0f, 1f)), style = Stroke(2.dp.toPx()))
}
