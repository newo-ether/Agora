package com.newoether.agora.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.motion.IdentityLabelCrossfade
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.rememberIdentityClipWidth

/**
 * Screen area that excludes the system bars. Read from the application context every time: a
 * configuration context made for the app language keeps the metrics of the orientation it was
 * created in.
 */
internal fun previewSafeArea(context: Context, density: Float): android.graphics.Rect {
    val app = context.applicationContext
    return if (Build.VERSION.SDK_INT >= 30) {
        val metrics = app.getSystemService(WindowManager::class.java).currentWindowMetrics
        val bars = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        android.graphics.Rect(0, bars.top, metrics.bounds.width(), metrics.bounds.height() - bars.bottom)
    } else {
        val metrics = app.resources.displayMetrics
        android.graphics.Rect(0, (24 * density).roundToInt(), metrics.widthPixels,
            metrics.heightPixels - (48 * density).roundToInt())
    }
}

internal fun previewWindowParams() = WindowManager.LayoutParams(
    0, 0, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
    PixelFormat.TRANSLUCENT,
).apply {
    gravity = Gravity.TOP or Gravity.START
    if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
}

/** Pixel-exact size; dp round trips would leave the card a pixel off its window frame. */
internal fun Modifier.previewExactSize(width: Int, height: Int) = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints.fixed(width.coerceAtLeast(0), height.coerceAtLeast(0)))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** Geometry shared by the floating card's grips, its hit zones and the full-screen reveal. */
internal object PreviewGrip {
    /** Gap between the card edge and the near side of a grip stroke. */
    const val GAP_DP = 5
    const val STROKE_DP = 2.6f
    const val ACTIVE_DP = 4.8f
    /** Grip opacity while it is not the one being dragged. */
    const val IDLE_ALPHA = 0.5f
    /** Top and bottom bar length over the card width. */
    const val BAR_RATIO = 0.22f
    const val CORNER_DP = 14
}

/**
 * Draws in card coordinates, outside the card: the bar above it, two corner arcs concentric with
 * its rounded corners, and a bar below it. Bars span [PreviewGrip.BAR_RATIO] of the width. Each
 * weight in 0..1 thickens and brightens its grip; every stroke is drawn twice for a shadow that
 * reads on any app.
 */
internal fun DrawScope.drawPreviewGrips(bar: Float, start: Float, end: Float, center: Float) {
    val w = size.width
    val h = size.height
    val base = PreviewGrip.STROKE_DP.dp.toPx()
    val off = PreviewGrip.GAP_DP.dp.toPx() + base / 2
    val corner = PreviewGrip.CORNER_DP.dp.toPx()
    val radius = corner + off
    val half = PreviewGrip.BAR_RATIO * w / 2
    fun stroke(weight: Float) = base + (PreviewGrip.ACTIVE_DP.dp.toPx() - base) * weight
    fun grip(path: Path, weight: Float) {
        val width = stroke(weight)
        translate(top = 1.2.dp.toPx()) {
            drawPath(path, Color.Black.copy(alpha = 0.16f + 0.24f * weight),
                style = Stroke(width + 1.6.dp.toPx(), cap = StrokeCap.Round))
        }
        drawPath(path, Color.White.copy(alpha = PreviewGrip.IDLE_ALPHA + (1f - PreviewGrip.IDLE_ALPHA) * weight),
            style = Stroke(width, cap = StrokeCap.Round))
    }
    fun line(y: Float) = Path().apply { moveTo(w / 2 - half, y); lineTo(w / 2 + half, y) }
    grip(line(-off), bar)
    grip(line(h + off), center)
    grip(Path().apply { arcTo(Rect(Offset(corner, h - corner), radius), 180f, -90f, true) }, start)
    grip(Path().apply { arcTo(Rect(Offset(w - corner, h - corner), radius), 0f, 90f, true) }, end)
}

/** The display releases its last frame before an exit animation ends; this keeps drawing it. */
@Composable
internal fun retainedFrame(screen: PhoneVirtualScreen): Bitmap? {
    val frame by screen.frame.collectAsState()
    val retained = remember { arrayOfNulls<Bitmap>(1) }
    if (frame != null) SideEffect { retained[0] = frame }
    return frame ?: retained[0]
}

private val CAPSULE_SHADOW = 6.dp
private val CAPSULE_TEXT_MAX = 180.dp

/**
 * The minimized card: the Phone Use mark and the current action. Like the control strip's status
 * capsule, a new label crossfades while the painted width moves to the new label's width; the
 * content is always laid out at its final width. Touches go to the window.
 */
@Composable
internal fun PreviewCapsule(label: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val text = with(LocalDensity.current) {
        measurer.measure(AnnotatedString(label), style, maxLines = 1, softWrap = false).size.width.toDp()
    }.coerceAtMost(CAPSULE_TEXT_MAX)
    // Start and end padding, icon and gap, as laid out by PreviewCapsuleContent.
    val target = 12.dp + 20.dp + 8.dp + text + 16.dp
    val width = rememberIdentityClipWidth(label, target, LocalAgoraMotionPolicy.current.allowSpatialTransitions)
    Surface(
        modifier = modifier.width(width),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = CAPSULE_SHADOW,
    ) {
        PreviewCapsuleContent(label, Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(target))
    }
}

@Composable
private fun PreviewCapsuleContent(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(PhoneUseIcon, contentDescription = null, modifier = Modifier.size(20.dp))
        IdentityLabelCrossfade(label) { text ->
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = CAPSULE_TEXT_MAX),
            )
        }
    }
}

/**
 * The card on its way to or from the capsule, sized and placed by the caller. With [progress] the
 * corners round to a pill, the fill turns from the card's black to the capsule color, the frame
 * fades out cropped to the shrinking rect, and the capsule's content fades in at its own size.
 * At 0 it draws like the card, at 1 like the capsule, so either can take over without a seam.
 */
@Composable
internal fun PreviewMorphBody(bitmap: Bitmap?, label: String, progress: Float, cardShadow: Dp, modifier: Modifier) {
    val container = MaterialTheme.colorScheme.primaryContainer
    Box(
        modifier
            .graphicsLayer {
                val radius = PreviewGrip.CORNER_DP.dp.toPx() * (1f - progress) + size.height / 2f * progress
                shape = RoundedCornerShape(radius)
                clip = true
                shadowElevation = (cardShadow + (CAPSULE_SHADOW - cardShadow) * progress).toPx()
            }
            .drawBehind { drawRect(lerp(Color.Black, container, progress)) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val image = bitmap ?: return@Canvas
            val fade = (1f - progress / 0.6f).coerceIn(0f, 1f)
            if (fade <= 0f || size.width < 1f || size.height < 1f) return@Canvas
            // Cover: the frame keeps its aspect and is cropped to the rect, never squashed.
            val scale = max(size.width / image.width, size.height / image.height)
            val srcWidth = (size.width / scale).roundToInt().coerceIn(1, image.width)
            val srcHeight = (size.height / scale).roundToInt().coerceIn(1, image.height)
            drawImage(
                image.asImageBitmap(),
                srcOffset = IntOffset((image.width - srcWidth) / 2, (image.height - srcHeight) / 2),
                srcSize = IntSize(srcWidth, srcHeight),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                alpha = fade,
            )
        }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer) {
            PreviewCapsuleContent(
                label,
                Modifier.wrapContentSize(unbounded = true)
                    .graphicsLayer { alpha = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f) },
            )
        }
    }
}

/** Opened by a tap on the bar above the card; anchored to that bar. */
@Composable
internal fun PreviewMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onFullscreen: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onStop: () -> Unit,
) {
    AgoraDropdownMenu(expanded = expanded, onDismissRequest = onDismiss, offset = DpOffset(0.dp, 4.dp)) {
        @Composable
        fun item(icon: ImageVector, label: Int, color: Color? = null, action: () -> Unit) {
            val colors = if (color == null) MenuDefaults.itemColors()
                else MenuDefaults.itemColors(textColor = color, leadingIconColor = color)
            AgoraDropdownMenuItem(
                text = { Text(stringResource(label)) },
                leadingIcon = { Icon(icon, contentDescription = null) },
                colors = colors,
                onClick = {
                    onDismiss()
                    action()
                },
            )
        }
        item(Icons.Rounded.Fullscreen, R.string.phone_background_fullscreen, action = onFullscreen)
        item(Icons.Rounded.Remove, R.string.phone_background_minimize, action = onMinimize)
        item(Icons.Rounded.Close, R.string.phone_background_close, action = onClose)
        item(Icons.Rounded.Stop, R.string.phone_stop, MaterialTheme.colorScheme.error, onStop)
    }
}
