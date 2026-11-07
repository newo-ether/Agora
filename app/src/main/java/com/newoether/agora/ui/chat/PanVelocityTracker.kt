package com.newoether.agora.ui.chat

import androidx.compose.ui.geometry.Offset

/**
 * Release velocity for zoomed image panning, computed from the accumulated pan deltas that move
 * the image: displacement over the last [WINDOW_MS] of movement, zero when the finger rested
 * longer than [REST_MS] before lifting.
 *
 * Replaces Compose's VelocityTracker here. On short fast flicks (3-4 samples) it returned
 * velocities pointing against the finger and clamped to the maximum, so the decay fling threw the
 * image backwards right after a long swipe.
 */
internal class PanVelocityTracker {
    private val times = ArrayList<Long>()
    private val positions = ArrayList<Offset>()
    private var total = Offset.Zero
    private var lastMoveTime = 0L
    private var lastTime = 0L

    fun add(time: Long, delta: Offset) {
        total += delta
        lastTime = time
        if (delta != Offset.Zero || times.isEmpty()) {
            times += time
            positions += total
            lastMoveTime = time
        }
    }

    /** Multi-touch restarts tracking so a pinch never feeds the fling. */
    fun reset(time: Long) {
        times.clear()
        positions.clear()
        total = Offset.Zero
        times += time
        positions += total
        lastMoveTime = time
        lastTime = time
    }

    fun velocity(): Offset {
        if (times.size < 2 || lastTime - lastMoveTime > REST_MS) return Offset.Zero
        val end = times.size - 1
        var start = end
        while (start > 0 && times[end] - times[start - 1] <= WINDOW_MS) start--
        if (start == end) start = end - 1
        val dt = (times[end] - times[start]).toFloat()
        if (dt <= 0f) return Offset.Zero
        return (positions[end] - positions[start]) * (1000f / dt)
    }

    private companion object {
        const val WINDOW_MS = 80L
        const val REST_MS = 50L
    }
}
