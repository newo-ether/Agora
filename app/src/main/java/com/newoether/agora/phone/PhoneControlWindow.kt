package com.newoether.agora.phone

import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Owns the existing control window's immediate input exclusion and question barrier. */
internal class PhoneControlWindow(
    private val view: View,
    private val params: WindowManager.LayoutParams,
    private val manager: WindowManager,
) {
    private val userPriority = MutableStateFlow(false)
    private var operating = false
    private var closed = false
    private var capturing = false
    var touchable = true
        private set

    suspend fun beforeOperation() {
        if (closed) throw PhoneFailure("overlay_unavailable")
        operating = true
        // Questions keep their inputs usable; no device effect crosses an unanswered question.
        userPriority.first { !it }
        if (closed) throw PhoneFailure("overlay_unavailable")
        setTouchable(false)
    }

    fun afterOperation() {
        operating = false
        if (!closed) setTouchable(!capturing)
    }

    fun showForUser(show: Boolean) {
        if (closed || userPriority.value == show) return
        userPriority.value = show
        setTouchable((show || !operating) && !capturing)
    }

    fun capture(active: Boolean) {
        if (closed) return
        capturing = active
        setTouchable(!active && (userPriority.value || !operating))
    }

    fun close() {
        if (closed) return
        closed = true
        userPriority.value = false
        setTouchable(false)
    }

    private fun setTouchable(enabled: Boolean) {
        touchable = enabled
        params.flags = if (enabled) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        // Keep both view alpha and the shared platform opacity unchanged during device work.
        manager.updateViewLayout(view, params)
        view.requestLayout()
    }
}
