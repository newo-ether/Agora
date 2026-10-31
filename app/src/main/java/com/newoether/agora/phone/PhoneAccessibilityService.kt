package com.newoether.agora.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The enabled platform service owns all Accessibility capabilities and window observations. */
class PhoneAccessibilityService : AccessibilityService() {
    @Volatile private var lastPackage: String? = null
    override fun onServiceConnected() { instance = this }
    private fun disconnected() {
        if (instance === this) {
            instance = null
            onDisconnected?.invoke()
        }
    }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        disconnected()
        return super.onUnbind(intent)
    }
    override fun onDestroy() { disconnected(); super.onDestroy() }
    override fun onInterrupt() = Unit
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastPackage = event.packageName?.toString()
        }
    }
    internal fun foregroundPackage(): String {
        val root = rootInActiveWindow
        return try { root?.packageName?.toString() ?: lastPackage ?: throw PhoneFailure("foreground_unknown") }
        finally { root?.recycle() }
    }

    internal suspend fun capture(): Bitmap {
        if (Build.VERSION.SDK_INT < 30) throw PhoneFailure("screenshot_requires_api_30")
        return suspendCancellableCoroutine { continuation ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardware = result.hardwareBuffer
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                            ?: throw PhoneFailure("screenshot_failed")
                        if (continuation.isActive) continuation.resume(bitmap) else bitmap.recycle()
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally { hardware.close() }
                }
                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resumeWithException(
                        PhoneFailure("screenshot_failed", "Accessibility screenshot error $errorCode"),
                    )
                }
            })
        }
    }

    internal fun snapshot(): List<PhoneNode> {
        val root = rootInActiveWindow ?: throw PhoneFailure("ui_tree_unavailable")
        val nodes = ArrayList<PhoneNode>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 64 || nodes.size >= 1000) return
            if (node.isVisibleToUser && (node.isClickable || node.isLongClickable || node.isEditable ||
                    node.isScrollable || node.isFocusable || !node.text.isNullOrEmpty())) {
                val rect = Rect().also(node::getBoundsInScreen)
                if (!rect.isEmpty) nodes += PhoneNode(
                    nodes.size, rect,
                    if (node.isPassword) "" else (node.text ?: node.contentDescription)?.toString().orEmpty().take(500),
                    node.className?.toString().orEmpty().substringAfterLast('.'),
                    node.isEnabled, node.isSelected || node.isChecked, node.isEditable, node.isPassword,
                )
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> try { visit(child, depth + 1) } finally { child.recycle() } }
            }
        }
        try { visit(root, 0) } finally { root.recycle() }
        return nodes
    }

    internal fun setText(text: String) {
        val root = rootInActiveWindow ?: throw PhoneFailure("no_focused_field")
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: throw PhoneFailure("no_focused_field")
            try {
                if (!focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    })) throw PhoneFailure("text_input_failed")
            } finally { focused.recycle() }
        } finally { root.recycle() }
    }

    internal suspend fun gesture(x: Int, y: Int, endX: Int, endY: Int, durationMs: Long) {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            if (x != endX || y != endY) lineTo(endX.toFloat(), endY.toFloat())
        }
        suspendCancellableCoroutine<Unit> { continuation ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resumeWithException(PhoneFailure("gesture_cancelled"))
                }
            }
            val accepted = dispatchGesture(GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 60_000)),
            ).build(), callback, null)
            if (!accepted && continuation.isActive) continuation.resumeWithException(PhoneFailure("gesture_refused"))
        }
    }

    internal fun globalKey(key: String) {
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> throw PhoneFailure("invalid_key")
        }
        if (!performGlobalAction(action)) throw PhoneFailure("key_failed")
    }
    companion object {
        @Volatile internal var instance: PhoneAccessibilityService? = null
            private set
        @Volatile internal var onDisconnected: (() -> Unit)? = null
    }
}
