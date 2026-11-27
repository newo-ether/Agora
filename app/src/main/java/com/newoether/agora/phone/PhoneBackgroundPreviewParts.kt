package com.newoether.agora.phone

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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

/** The minimized card: the Phone Use mark and the current action. Touches go to the window. */
@Composable
internal fun PreviewCapsule(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(PhoneUseIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
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
