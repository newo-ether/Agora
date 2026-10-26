package com.newoether.agora.phone

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Rect

/** Refreshes immutable platform corner data after attachment, insets, rotation and layout changes. */
@Composable
internal fun rememberPhoneCorners(view: View): List<RoundedCorner?>? {
    var corners by remember(view) { mutableStateOf<List<RoundedCorner?>?>(null) }
    DisposableEffect(view) {
        var lastInsets: WindowInsets? = null
        var lastGeometry = emptyList<Int>()
        fun refresh() {
            if (Build.VERSION.SDK_INT < 31 || !view.isAttachedToWindow) return
            val insets = view.rootWindowInsets
            val window = IntArray(2).also(view::getLocationInWindow)
            val screen = IntArray(2).also(view::getLocationOnScreen)
            val geometry = listOf(view.width, view.height, window[0], window[1], screen[0], screen[1], view.display?.rotation ?: 0)
            if (insets === lastInsets && geometry == lastGeometry) return
            lastInsets = insets
            lastGeometry = geometry
            if (insets == null && view.display == null) return
            corners = (0..3).map { position ->
                val insetCorner = insets?.getRoundedCorner(position)
                val corner = insetCorner ?: view.display?.getRoundedCorner(position)
                corner?.let {
                    val offset = if (insetCorner != null) window else screen
                    RoundedCorner(position, it.radius, it.center.x - offset[0], it.center.y - offset[1])
                }
            }
        }
        val preDraw = ViewTreeObserver.OnPreDrawListener { refresh(); true }
        val observer = view.viewTreeObserver
        observer.addOnPreDrawListener(preDraw)
        val attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.requestApplyInsets(); refresh() }
            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        view.addOnAttachStateChangeListener(attach)
        view.requestApplyInsets()
        refresh()
        onDispose {
            if (observer.isAlive) observer.removeOnPreDrawListener(preDraw)
            view.removeOnAttachStateChangeListener(attach)
        }
    }
    return corners
}

/** Each quarter-circle uses its own platform center, keeping stroke bands inside the mask. */
internal fun phoneBorderPath(size: Size, corners: List<RoundedCorner?>?, inset: Float, fallbackRadius: Float): Path {
    val positions = listOf(0, 1, 2, 3)
    val radii = positions.map { position ->
        ((if (corners == null) fallbackRadius else corners[position]?.radius?.toFloat() ?: 0f) - inset).coerceAtLeast(0f)
    }
    val centers = positions.map { position ->
        val corner = corners?.get(position)
        val radius = if (corners == null) fallbackRadius else 0f
        val x = corner?.center?.x?.toFloat() ?: if (position == 0 || position == 3) radius else size.width - radius
        val y = corner?.center?.y?.toFloat() ?: if (position < 2) radius else size.height - radius
        androidx.compose.ui.geometry.Offset(
            x.coerceIn(inset, (size.width - inset).coerceAtLeast(inset)),
            y.coerceIn(inset, (size.height - inset).coerceAtLeast(inset)),
        )
    }
    return Path().apply {
        moveTo(centers[0].x, centers[0].y - radii[0])
        positions.drop(1).plus(0).forEach { position ->
            val center = centers[position]
            val radius = radii[position]
            val startAngle = when (position) { 1 -> 270f; 2 -> 0f; 3 -> 90f; else -> 180f }
            if (radius > 0f) arcTo(
                Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius),
                startAngle, 90f, false,
            ) else lineTo(center.x, center.y)
        }
        close()
    }
}
