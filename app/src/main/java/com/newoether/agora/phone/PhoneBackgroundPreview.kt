package com.newoether.agora.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Floating live view of the background screen. Drag to move; tap to open it full screen and tap
 * again to shrink it back. It only shows frames and never forwards input to the display.
 */
internal class PhoneBackgroundPreview(private val context: Context, private val screen: PhoneVirtualScreen) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val density = context.resources.displayMetrics.density
    private val displayWidth = context.resources.displayMetrics.widthPixels
    private val smallWidth = (SMALL_WIDTH_DP * density).roundToInt()
    private val smallHeight = smallWidth * screen.height / screen.width
    private val margin = (MARGIN_DP * density).roundToInt()
    private var corner = CORNER_DP * density
    private val accent = if (Build.VERSION.SDK_INT >= 31) {
        context.getColor(android.R.color.system_accent1_400)
    } else Color.rgb(0x4C, 0xAF, 0x50)
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.BLACK)
    }
    private val border = GradientDrawable().apply {
        setStroke((BORDER_DP * density).roundToInt(), accent)
        cornerRadius = corner
    }
    private val root = FrameLayout(context).apply {
        addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        foreground = border
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, corner)
        }
        clipToOutline = true
        elevation = 6 * density
    }
    private val params = WindowManager.LayoutParams(
        smallWidth, smallHeight, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = displayWidth - smallWidth - margin
        y = (TOP_DP * density).roundToInt()
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    private var expanded = false
    private var smallX = params.x
    private var smallY = params.y
    private var added = false

    fun show() {
        windows.addView(root, params)
        added = true
        root.setOnTouchListener(touch())
        scope.launch { screen.frame.collect { image.setImageBitmap(it) } }
    }

    fun remove() {
        scope.cancel()
        if (added) runCatching { windows.removeViewImmediate(root) }
        added = false
        image.setImageDrawable(null)
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        if (value) {
            smallX = params.x; smallY = params.y
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.x = 0; params.y = 0
            corner = 0f
        } else {
            params.width = smallWidth; params.height = smallHeight
            params.x = smallX; params.y = smallY
            corner = CORNER_DP * density
        }
        border.cornerRadius = corner
        root.invalidateOutline()
        if (added) runCatching { windows.updateViewLayout(root, params) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun touch() = object : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!expanded && (dragging || abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        params.x = (startX + dx).roundToInt()
                        params.y = (startY + dy).roundToInt().coerceAtLeast(0)
                        if (added) runCatching { windows.updateViewLayout(root, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) {
                    view.performClick()
                    setExpanded(!expanded)
                }
            }
            return true
        }
    }

    private companion object {
        const val SMALL_WIDTH_DP = 112
        const val MARGIN_DP = 12
        const val TOP_DP = 96
        const val CORNER_DP = 14f
        const val BORDER_DP = 2f
    }
}
