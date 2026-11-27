package com.newoether.agora.phone

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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

/**
 * One point of the screen outline and how it moves inward. A corner point keeps its corner's
 * center and shrinks toward it until the radius reaches [inner]; deeper, the radius stays at
 * [inner] and the center moves in along the corner diagonal, so the inset outline keeps a round
 * corner instead of turning sharp. An edge point moves straight along its normal.
 */
private class OutlinePoint(
    val ax: Float, val ay: Float,
    val ux: Float, val uy: Float,
    val reach: Float,
    val diagonalX: Float, val diagonalY: Float,
    val corner: Boolean,
    val inner: Float = 0f,
) {
    fun at(depth: Float, into: FloatArray, index: Int) {
        val radius = if (corner) max(reach - depth, min(inner, reach)) else reach - depth
        val shift = if (corner) radius - reach + depth else 0f
        into[index] = ax - diagonalX * shift + ux * radius
        into[index + 1] = ay - diagonalY * shift + uy * radius
    }
}

/**
 * Fill regions for a glow that hugs the screen edge with an uneven width. Region n covers the band
 * from the edge to depths[n] scaled by a smooth wobble along the outline, so stacked regions fade
 * inward and the inner edge breathes in and out. A corner the platform reports smaller than
 * [minRadius] (a virtual display reports none) is drawn with [minRadius], and the inner edge never
 * rounds tighter than [minRadius], so the glow keeps soft corners however deep it reaches.
 */
internal fun phoneGlowBands(
    size: Size,
    corners: List<RoundedCorner?>?,
    fallbackRadius: Float,
    minRadius: Float,
    step: Float,
    depths: List<Float>,
    wobble: Float = 0.4f,
): List<Path> {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return depths.map { Path() }
    val known = Build.VERSION.SDK_INT >= 31 && corners != null
    val startAngles = floatArrayOf(180f, 270f, 0f, 90f)
    val diagonals = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)
    val geometry = (0..3).map { position ->
        val platform = if (known) corners!![position] else null
        val usePlatform = platform != null && platform.radius >= minRadius
        val radius = when {
            !known -> fallbackRadius
            usePlatform -> platform!!.radius.toFloat()
            else -> minRadius
        }.coerceAtMost(min(w, h) / 2)
        val left = position == 0 || position == 3
        val upper = position < 2
        val cx = if (usePlatform) platform!!.center.x.toFloat() else if (left) radius else w - radius
        val cy = if (usePlatform) platform!!.center.y.toFloat() else if (upper) radius else h - radius
        Triple(cx.coerceIn(0f, w), cy.coerceIn(0f, h), radius)
    }
    val edgeNormals = listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f)
    val points = mutableListOf<OutlinePoint>()
    for (position in 0..3) {
        val (cx, cy, radius) = geometry[position]
        val (dx, dy) = diagonals[position]
        val arcSteps = max(2, ceil(radius * PI / 2 / step).toInt())
        for (j in 0..arcSteps) {
            val angle = Math.toRadians((startAngles[position] + 90f * j / arcSteps).toDouble())
            points += OutlinePoint(cx, cy, cos(angle).toFloat(), sin(angle).toFloat(), radius, dx, dy, true, minRadius)
        }
        // Straight edge to the next corner's arc, sampled so the wobble can bend it too.
        val next = (position + 1) % 4
        val (nx, ny, nr) = geometry[next]
        val endAngle = Math.toRadians((startAngles[position] + 90f).toDouble())
        val fromX = cx + radius * cos(endAngle).toFloat()
        val fromY = cy + radius * sin(endAngle).toFloat()
        val toX = nx + nr * cos(endAngle).toFloat()
        val toY = ny + nr * sin(endAngle).toFloat()
        val (ux, uy) = edgeNormals[position]
        val steps = ceil(hypot(toX - fromX, toY - fromY) / step).toInt()
        for (k in 1 until steps) {
            val t = k.toFloat() / steps
            points += OutlinePoint(fromX + (toX - fromX) * t, fromY + (toY - fromY) * t, ux, uy, 0f, 0f, 0f, false)
        }
    }
    // Arc length at each point, for a wobble that closes seamlessly around the outline.
    val base = FloatArray(points.size * 2)
    points.forEachIndexed { i, point -> point.at(0f, base, i * 2) }
    val along = FloatArray(points.size)
    for (i in 1 until points.size) {
        along[i] = along[i - 1] + hypot(base[i * 2] - base[i * 2 - 2], base[i * 2 + 1] - base[i * 2 - 1])
    }
    val total = along.last() + hypot(base[0] - base[base.size - 2], base[1] - base[base.size - 1])
    val scale = FloatArray(points.size) { i ->
        val phase = 2 * PI * along[i] / total
        (1f + wobble * (0.55f * sin(5 * phase + 1.3) + 0.3f * sin(11 * phase + 4.1) +
            0.15f * sin(23 * phase + 2.2)).toFloat()).coerceAtLeast(0.1f)
    }
    val outline = Path().apply { polygon(base) }
    val inner = FloatArray(points.size * 2)
    return depths.map { depth ->
        points.forEachIndexed { i, point -> point.at(depth * scale[i], inner, i * 2) }
        Path().apply {
            fillType = PathFillType.EvenOdd
            addPath(outline)
            polygon(inner)
        }
    }
}

private fun Path.polygon(xy: FloatArray) {
    moveTo(xy[0], xy[1])
    for (i in 2 until xy.size step 2) lineTo(xy[i], xy[i + 1])
    close()
}

/** Each quarter-circle uses its own platform center, keeping stroke bands inside the mask. */
internal fun phoneBorderPath(size: Size, corners: List<RoundedCorner?>?, inset: Float, fallbackRadius: Float): Path {
    val positions = listOf(0, 1, 2, 3)
    val radii = positions.map { position ->
        val radius = if (Build.VERSION.SDK_INT >= 31 && corners != null) {
            corners[position]?.radius?.toFloat() ?: 0f
        } else fallbackRadius
        (radius - inset).coerceAtLeast(0f)
    }
    val centers = positions.map { position ->
        val center = if (Build.VERSION.SDK_INT >= 31) corners?.get(position)?.center else null
        val radius = if (corners == null) fallbackRadius else 0f
        val x = center?.x?.toFloat() ?: if (position == 0 || position == 3) radius else size.width - radius
        val y = center?.y?.toFloat() ?: if (position < 2) radius else size.height - radius
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
