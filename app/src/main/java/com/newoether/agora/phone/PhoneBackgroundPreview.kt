package com.newoether.agora.phone

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The floating window of the background screen. The card shows live frames, a handle above it drags
 * it, and three grips on its edge resize it with the display's aspect ratio locked. Tapping the card
 * opens [PhoneBackgroundActivity]; the window hides itself while that view owns the screen.
 */
internal class PhoneBackgroundPreview(
    private val context: Context,
    private val screen: PhoneVirtualScreen,
    private val runtime: PhoneUseRuntime,
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    private val windows = context.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val density = context.resources.displayMetrics.density
    private val aspect = screen.width.toFloat() / screen.height.toFloat()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val pad = (PAD_DP * density).roundToInt()
    private val handleZone = (HANDLE_ZONE_DP * density).roundToInt()
    private val margin = (MARGIN_DP * density).roundToInt()
    private val minWidth = (MIN_WIDTH_DP * density).roundToInt()
    private var cardWidth by mutableIntStateOf(0)
    private var cardHeight by mutableIntStateOf(0)
    private var root: ComposeView? = null
    private var added = false

    /** Screen area that excludes the system bars, so the card never hides under them. */
    private val safe: Rect = if (Build.VERSION.SDK_INT >= 30) {
        val metrics = windows.currentWindowMetrics
        val bars = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        Rect(0, bars.top, metrics.bounds.width(), metrics.bounds.height() - bars.bottom)
    } else {
        val metrics = context.resources.displayMetrics
        Rect(0, (24 * density).roundToInt(), metrics.widthPixels,
            metrics.heightPixels - (48 * density).roundToInt())
    }

    private val params = WindowManager.LayoutParams(
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

    private var gesture = Gesture.NONE
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var startWidth = 0
    private var startHeight = 0
    private var moved = false

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        val initial = if (storedWidth > 0) storedWidth.toFloat()
            else (if (screen.width > screen.height) WIDE_WIDTH_DP else CARD_WIDTH_DP) * density
        resize(initial.roundToInt())
        val x = if (storedX != Int.MIN_VALUE) storedX else safe.right - params.width
        val y = if (storedY != Int.MIN_VALUE) storedY else safe.top + (TOP_DP * density).roundToInt()
        moveTo(x, y)
    }

    fun show() {
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@PhoneBackgroundPreview)
            setViewTreeSavedStateRegistryOwner(this@PhoneBackgroundPreview)
            setViewTreeViewModelStoreOwner(this@PhoneBackgroundPreview)
            setContent { PhoneOverlayTheme(runtime.settings) { Content() } }
        }
        root = view
        try { windows.addView(view, params) } catch (error: Exception) { view.disposeComposition(); throw error }
        added = true
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        scope.launch { runtime.fullscreen.collect { hidden -> setHidden(hidden) } }
    }

    fun remove() {
        scope.cancel()
        root?.let { view ->
            runCatching { windows.removeViewImmediate(view) }
            view.disposeComposition()
        }
        root = null
        added = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }

    /** The full-screen view is opaque over this window; it must not intercept touches there. */
    private fun setHidden(hidden: Boolean) {
        params.alpha = if (hidden) 0f else 1f
        params.flags = if (hidden) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        update()
    }

    private fun maxWidth(): Int {
        val byWidth = safe.width() - 2 * margin
        val byHeight = ((safe.height() - 2 * margin) * aspect).toInt()
        return min(byWidth, byHeight).coerceAtLeast(minWidth)
    }

    private fun resize(width: Int) {
        val w = width.coerceIn(minWidth, maxWidth())
        cardWidth = w
        cardHeight = (w / aspect).roundToInt()
        params.width = w + 2 * pad
        params.height = cardHeight + 2 * pad + handleZone
    }

    private fun moveTo(x: Int, y: Int) {
        params.x = x.coerceIn(safe.left, (safe.right - params.width).coerceAtLeast(safe.left))
        params.y = y.coerceIn(safe.top, (safe.bottom - params.height).coerceAtLeast(safe.top))
        update()
    }

    private fun update() {
        if (!added) return
        root?.let { view -> runCatching { windows.updateViewLayout(view, params) } }
    }

    private fun remember() {
        storedWidth = cardWidth
        storedX = params.x
        storedY = params.y
    }

    /** Snap fully on screen with a short ease; the drag itself follows the finger exactly. */
    private fun settle() {
        remember()
        val targetX = params.x.coerceIn(safe.left + margin, (safe.right - params.width - margin).coerceAtLeast(safe.left))
        val targetY = params.y.coerceIn(safe.top, (safe.bottom - params.height - margin).coerceAtLeast(safe.top))
        if (targetX == params.x && targetY == params.y) return
        val fromX = params.x
        val fromY = params.y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                params.x = (fromX + (targetX - fromX) * progress).roundToInt()
                params.y = (fromY + (targetY - fromY) * progress).roundToInt()
                update()
            }
            start()
        }
    }

    private fun openFullscreen() {
        if (runtime.fullscreen.value) return
        val card = Rect(
            params.x + pad, params.y + pad + handleZone,
            params.x + pad + cardWidth, params.y + pad + handleZone + cardHeight,
        )
        runtime.previewCard = card
        runtime.fullscreen.value = true
        runCatching { context.startActivity(PhoneBackgroundActivity.intent(context, card)) }
            .onFailure { runtime.fullscreen.value = false }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun cardTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = params.x
                startY = params.y
                startWidth = cardWidth
                startHeight = cardHeight
                moved = false
                gesture = hit(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!moved && hypot(dx, dy) > slop) moved = true
                if (moved) apply(gesture, dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                if (moved) settle() else if (gesture == Gesture.MOVE) openFullscreen()
                gesture = Gesture.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (moved) settle()
                gesture = Gesture.NONE
            }
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = params.x
                startY = params.y
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!moved && hypot(dx, dy) > slop) moved = true
                if (moved) moveTo((startX + dx).roundToInt(), (startY + dy).roundToInt())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (moved) settle()
                moved = false
            }
        }
        return true
    }

    /** Corner and edge grips own the outer band of the card; the rest of it drags. */
    private fun hit(x: Float, y: Float): Gesture {
        val corner = CORNER_HIT_DP * density
        val width = cardWidth.toFloat()
        val height = cardHeight.toFloat()
        return when {
            y > height - corner * 1.6f && x < corner -> Gesture.BOTTOM_START
            y > height - corner * 1.6f && x > width - corner -> Gesture.BOTTOM_END
            y > height - CENTER_HIT_DP * density && abs(x - width / 2) < 44 * density -> Gesture.BOTTOM_CENTER
            else -> Gesture.MOVE
        }
    }

    private fun apply(kind: Gesture, dx: Float, dy: Float) {
        if (kind == Gesture.MOVE) {
            moveTo((startX + dx).roundToInt(), (startY + dy).roundToInt())
            return
        }
        val byWidth = when (kind) {
            Gesture.BOTTOM_START -> startWidth - dx
            Gesture.BOTTOM_END -> startWidth + dx
            else -> startWidth + dx * 2
        }
        val byHeight = (startHeight + dy) * aspect
        // The axis the finger moved further along wins, so the locked ratio still feels tracked.
        val width = if (abs(byWidth - startWidth) >= abs(byHeight - startWidth)) byWidth else byHeight
        resize(width.roundToInt())
        val x = when (kind) {
            Gesture.BOTTOM_START -> startX + startWidth - cardWidth
            Gesture.BOTTOM_END -> startX
            else -> startX + startWidth / 2 - cardWidth / 2
        }
        moveTo(x, startY)
    }

    @Composable
    private fun Content() {
        val frame by screen.frame.collectAsState()
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .padding(start = PAD_DP.dp, top = (PAD_DP + HANDLE_ZONE_DP).dp, end = PAD_DP.dp, bottom = PAD_DP.dp)
                    .fillMaxSize()
                    .shadow(SHADOW_DP.dp, RoundedCornerShape(CORNER_DP.dp), clip = false)
                    .clip(RoundedCornerShape(CORNER_DP.dp))
                    .background(Color.Black)
                    .pointerInteropFilter { event -> cardTouch(event) },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    frame?.let { bitmap ->
                        drawImage(
                            bitmap.asImageBitmap(),
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        )
                    }
                }
                Canvas(Modifier.fillMaxSize()) { drawGrips() }
            }
            Box(
                Modifier
                    .padding(start = PAD_DP.dp, top = PAD_DP.dp, end = PAD_DP.dp)
                    .fillMaxWidth()
                    .height(HANDLE_ZONE_DP.dp)
                    .pointerInteropFilter { event -> handleTouch(event) },
            ) {
                Canvas(Modifier.fillMaxSize()) { drawHandle() }
            }
        }
    }

    private fun DrawScope.drawHandle() {
        val width = 3.dp.toPx()
        val half = 22.dp.toPx()
        val centerX = size.width / 2
        val centerY = size.height - 1.5.dp.toPx()
        drawLine(
            Color.Black.copy(alpha = 0.35f),
            Offset(centerX - half, centerY + 1.5.dp.toPx()), Offset(centerX + half, centerY + 1.5.dp.toPx()),
            strokeWidth = width + 1.6.dp.toPx(), cap = StrokeCap.Round,
        )
        drawLine(
            Color.White.copy(alpha = 0.92f),
            Offset(centerX - half, centerY), Offset(centerX + half, centerY),
            strokeWidth = width, cap = StrokeCap.Round,
        )
    }

    /** Two corner arcs and one bottom line; each is drawn twice for a shadow that reads on any app. */
    private fun DrawScope.drawGrips() {
        val light = Color.White.copy(alpha = 0.9f)
        val dark = Color.Black.copy(alpha = 0.35f)
        val stroke = 2.2.dp.toPx()
        val radius = 20.dp.toPx()
        val inset = 2.dp.toPx()
        fun arc(startAngle: Float, centerX: Float, centerY: Float) {
            val topLeft = Offset(centerX - radius, centerY - radius)
            val arcSize = Size(radius * 2, radius * 2)
            drawArc(dark, startAngle, 48f, false, topLeft + Offset(0f, 1.5.dp.toPx()), arcSize,
                style = Stroke(stroke + 1.6.dp.toPx(), cap = StrokeCap.Round))
            drawArc(light, startAngle, 48f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        arc(90f, inset + radius, size.height - inset - radius)
        arc(42f, size.width - inset - radius, size.height - inset - radius)
        val y = size.height - inset - stroke / 2
        val half = 22.dp.toPx()
        val centerX = size.width / 2
        drawLine(dark, Offset(centerX - half, y + 1.5.dp.toPx()), Offset(centerX + half, y + 1.5.dp.toPx()),
            strokeWidth = stroke + 1.6.dp.toPx(), cap = StrokeCap.Round)
        drawLine(light, Offset(centerX - half, y), Offset(centerX + half, y),
            strokeWidth = stroke, cap = StrokeCap.Round)
    }

    private enum class Gesture { NONE, MOVE, BOTTOM_START, BOTTOM_END, BOTTOM_CENTER }

    private companion object {
        const val CARD_WIDTH_DP = 132
        const val WIDE_WIDTH_DP = 220
        const val MIN_WIDTH_DP = 88
        const val PAD_DP = 10
        const val HANDLE_ZONE_DP = 16
        const val CORNER_DP = 14
        const val SHADOW_DP = 8
        const val MARGIN_DP = 10
        const val TOP_DP = 88
        const val CORNER_HIT_DP = 22f
        const val CENTER_HIT_DP = 26f

        // Survives release and reclaim within the process, so the card returns where it was left.
        private var storedWidth = 0
        private var storedX = Int.MIN_VALUE
        private var storedY = Int.MIN_VALUE
    }
}
