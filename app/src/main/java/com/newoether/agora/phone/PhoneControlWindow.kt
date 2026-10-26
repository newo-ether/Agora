package com.newoether.agora.phone

import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal const val PHONE_CONTROL_FADE_MS = 180L
internal const val PHONE_CONTROL_RESTORE_DELAY_MS = 300L

/** The existing control window's alpha, input exclusion and interrupted restoration boundary. */
internal class PhoneControlWindow(
    private val view: View,
    private val params: WindowManager.LayoutParams,
    private val manager: WindowManager,
    private val scope: CoroutineScope,
) {
    private val restingAlpha = params.alpha
    private val userPriority = MutableStateFlow(false)
    private var animation: Job? = null
    private var animationTarget = 1f
    private var restore: Job? = null
    private var operating = false
    private var closed = false
    private var alpha = 1f
    var touchable = true
        private set

    suspend fun beforeOperation() {
        if (closed) throw PhoneFailure("overlay_unavailable")
        operating = true
        restore?.cancel()
        restore = null
        // A pending question keeps Stop and its inputs usable. No device work crosses this wait.
        while (true) {
            userPriority.first { !it }
            if (closed) throw PhoneFailure("overlay_unavailable")
            setTouchable(false)
            transition(0f).join()
            if (closed) throw PhoneFailure("overlay_unavailable")
            if (!userPriority.value && alpha == 0f) return
        }
    }

    fun afterOperation() {
        operating = false
        if (closed || userPriority.value) return
        restore?.cancel()
        restore = scope.launch {
            delay(PHONE_CONTROL_RESTORE_DELAY_MS)
            if (!operating && !closed) {
                transition(1f).join()
                if (!operating && !closed && alpha == 1f) setTouchable(true)
            }
        }
    }

    fun showForUser(show: Boolean) {
        if (closed || userPriority.value == show) return
        userPriority.value = show
        restore?.cancel()
        restore = null
        if (show) {
            setTouchable(true)
            transition(1f)
        } else if (operating) {
            setTouchable(false)
            transition(0f)
        } else afterOperation()
    }

    fun close() {
        if (closed) return
        closed = true
        userPriority.value = false
        restore?.cancel()
        animation?.cancel()
        setTouchable(false)
    }

    private fun transition(target: Float): Job {
        if (animationTarget == target && animation?.isActive == true) return checkNotNull(animation)
        animation?.cancel()
        animationTarget = target
        return scope.launch {
            val start = alpha
            if (start == target) return@launch
            var elapsed = 0L
            while (elapsed < PHONE_CONTROL_FADE_MS) {
                val step = minOf(16L, PHONE_CONTROL_FADE_MS - elapsed)
                delay(step)
                elapsed += step
                alpha = start + (target - start) * elapsed.toFloat() / PHONE_CONTROL_FADE_MS
                view.alpha = alpha
                params.alpha = if (alpha == 0f) 0f else restingAlpha
                manager.updateViewLayout(view, params)
            }
            alpha = target
            view.alpha = target
        }.also { animation = it }
    }

    private fun setTouchable(enabled: Boolean) {
        touchable = enabled
        params.flags = if (enabled) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        // Fully faded windows also publish zero platform opacity, preserving touch pass-through.
        params.alpha = if (alpha == 0f) 0f else restingAlpha
        manager.updateViewLayout(view, params)
        view.requestLayout()
    }
}
