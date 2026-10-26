package com.newoether.agora.phone

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Owns the control window's opacity, input exclusion and question admission barrier. */
internal class PhoneControlWindow(
    private val view: View,
    private val params: WindowManager.LayoutParams,
    private val manager: WindowManager,
    private val operationAlpha: Float,
) {
    private val operationReady = MutableStateFlow(false)
    private val captureReady = MutableStateFlow(false)
    private var userPriority = false
    private var operating = false
    private var closed = false
    private var capturing = false
    private var animator: ValueAnimator? = null
    private var alphaTarget = params.alpha
    var touchable = true
        private set
    val readyToOperate: Boolean get() = !closed && operationReady.value

    suspend fun beforeOperation() {
        if (closed) throw PhoneFailure("overlay_unavailable")
        operating = true
        refresh()
        // Stop remains usable during the fade; no device effect precedes the safe alpha/input state.
        operationReady.first { it }
        if (closed) throw PhoneFailure("overlay_unavailable")
    }

    fun afterOperation() {
        operating = false
        if (!closed) refresh()
    }

    fun showForUser(show: Boolean) {
        if (closed || userPriority == show) return
        userPriority = show
        refresh()
    }

    fun capture(active: Boolean) {
        if (closed) return
        capturing = active
        refresh()
    }

    suspend fun awaitCaptureHidden() {
        captureReady.first { it }
        if (closed || !capturing || params.alpha != 0f) throw PhoneFailure("overlay_unavailable")
    }

    fun close() {
        if (closed) return
        closed = true
        cancelAnimation()
        publish()
        // Wake an old waiter so it fails instead of waiting on a removed window.
        operationReady.value = true
        captureReady.value = true
    }

    private fun refresh() {
        val target = when {
            capturing -> 0f
            operating && !userPriority -> operationAlpha
            else -> 1f
        }
        publish()
        if (animator != null && alphaTarget == target) return
        cancelAnimation()
        alphaTarget = target
        if (params.alpha == target || !ValueAnimator.areAnimatorsEnabled()) {
            params.alpha = target
            publish()
            return
        }
        val next = ValueAnimator.ofFloat(params.alpha, target).apply {
            duration = 180L
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener {
                params.alpha = it.animatedValue as Float
                publish()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    params.alpha = target
                    publish()
                }
            })
        }
        animator = next
        next.start()
    }

    private fun cancelAnimation() {
        animator?.let {
            it.removeAllUpdateListeners()
            it.removeAllListeners()
            it.cancel()
        }
        animator = null
    }

    private fun publish() {
        val passThrough = capturing || (operating && !userPriority)
        touchable = !closed && !capturing && (!passThrough || params.alpha > operationAlpha)
        params.flags = if (touchable) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        manager.updateViewLayout(view, params)
        view.requestLayout()
        captureReady.value = !closed && capturing && params.alpha == 0f && animator == null
        operationReady.value = !closed && operating && !userPriority && !capturing &&
            !touchable && params.alpha <= operationAlpha
    }
}
