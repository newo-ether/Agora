package com.newoether.agora.phone

import android.animation.ValueAnimator
import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * One drag feel for the control strip and the background card. Past its bounds a drag meets a
 * rubber band; what is dragged trails the finger slightly and eases to rest after release.
 */
internal object DragFeel {
    /** Share of the finger's motion kept at the bound; it falls as the overshoot grows. */
    const val RUBBER = 0.55f
    /** How far past a bound a drag can pull, however far the finger goes. */
    const val REACH_DP = 32f
    /** Time constant of the trail while the finger holds. */
    const val FOLLOW_MS = 28f
    /** Time constant of the ease to rest after release. */
    const val RELEASE_MS = 70f

    /** [value] inside [lo]..[hi] unchanged; outside, approaching [reach] past the bound. */
    fun rubberBand(value: Float, lo: Float, hi: Float, reach: Float): Float {
        val top = max(lo, hi)
        fun ease(over: Float) = if (reach <= 0f) 0f else reach * (1f - 1f / (over * RUBBER / reach + 1f))
        return when {
            value < lo -> lo - ease(lo - value)
            value > top -> top + ease(value - top)
            else -> value
        }
    }
}

/** Moves values toward a target once per vsync and hands each step to [apply]. Main thread only. */
internal class DragFollower(size: Int, private val apply: (FloatArray) -> Unit) : Choreographer.FrameCallback {
    private val now = FloatArray(size)
    private val target = FloatArray(size)
    private var timeConstant = DragFeel.FOLLOW_MS
    private var lastFrame = 0L
    private var running = false
    private var arrived: (() -> Unit)? = null

    /** Jumps to [values] without a trail, as when a drag starts. */
    fun snap(vararg values: Float) {
        stop()
        values.copyInto(now)
        values.copyInto(target)
    }

    /** The finger moved; the values trail toward [values]. */
    fun follow(vararg values: Float) = start(values, DragFeel.FOLLOW_MS, null)

    /** The finger lifted; the values ease to [values], then [then] runs. */
    fun release(vararg values: Float, then: () -> Unit) = start(values, DragFeel.RELEASE_MS, then)

    fun stop() {
        if (running) Choreographer.getInstance().removeFrameCallback(this)
        running = false
        arrived = null
    }

    private fun start(values: FloatArray, constant: Float, then: (() -> Unit)?) {
        values.copyInto(target)
        timeConstant = constant
        arrived = then
        if (!ValueAnimator.areAnimatorsEnabled()) {
            if (running) Choreographer.getInstance().removeFrameCallback(this)
            running = false
            target.copyInto(now)
            apply(now)
            return finish()
        }
        if (running) return
        running = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val elapsed = if (lastFrame == 0L) FRAME_MS else ((frameTimeNanos - lastFrame) / 1e6f).coerceIn(0f, MAX_STEP_MS)
        lastFrame = frameTimeNanos
        val step = 1f - exp(-elapsed / timeConstant)
        var settled = true
        for (i in now.indices) {
            now[i] += (target[i] - now[i]) * step
            if (abs(target[i] - now[i]) < SETTLED_PX) now[i] = target[i] else settled = false
        }
        apply(now)
        if (!settled) return Choreographer.getInstance().postFrameCallback(this)
        running = false
        finish()
    }

    private fun finish() {
        val then = arrived ?: return
        arrived = null
        then()
    }

    private companion object {
        const val FRAME_MS = 16.7f
        const val MAX_STEP_MS = 50f
        const val SETTLED_PX = 0.5f
    }
}
