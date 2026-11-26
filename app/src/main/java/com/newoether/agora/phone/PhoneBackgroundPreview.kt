package com.newoether.agora.phone

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The floating window of the background screen. The card shows live frames, a handle above it drags
 * it, and three grips on its edge resize it with the display's aspect ratio locked. Tapping the card
 * opens [PhoneBackgroundActivity]; the window hides itself while that view owns the screen.
 *
 * The card's screen rect is the model; the window is derived from it. While a grip resizes the card,
 * the window spans the whole safe area and only the card moves inside it, so no frame waits on a
 * window relayout and the card cannot shudder against its own window.
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

    /** Card rect on screen. */
    private var cardX = 0
    private var cardY = 0
    private var cardWidth by mutableIntStateOf(0)
    private var cardHeight by mutableIntStateOf(0)

    /** Card origin inside the window. */
    private var cardLeft by mutableIntStateOf(0)
    private var cardTop by mutableIntStateOf(0)

    /** True while a drag holds the card; the card grows a little under the finger. */
    private var lifted by mutableStateOf(false)

    /** True while a grip resizes the card and the window spans the safe area. */
    private var workspace = false
    private var root: ComposeView? = null
    private var added = false
    private val pushed = IntArray(6) { Int.MIN_VALUE }

    /** Screen area that excludes the system bars, so the card never hides under them. */
    private var safe: Rect = safeArea()
    private var settling: ValueAnimator? = null

    /** A rotation changes the safe area; the card is refitted into the new one. */
    private val configuration = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            safe = safeArea()
            settling?.cancel()
            settling = null
            workspace = false
            lifted = false
            gesture = Gesture.NONE
            setSize(cardWidth)
            placeCard(cardX, cardY)
            savePlacement()
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    /**
     * Read from the application context every time: a configuration context made for the app
     * language keeps the metrics of the orientation it was created in.
     */
    private fun safeArea(): Rect {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= 30) {
            val metrics = app.getSystemService(WindowManager::class.java).currentWindowMetrics
            val bars = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
            Rect(0, bars.top, metrics.bounds.width(), metrics.bounds.height() - bars.bottom)
        } else {
            val metrics = app.resources.displayMetrics
            Rect(0, (24 * density).roundToInt(), metrics.widthPixels,
                metrics.heightPixels - (48 * density).roundToInt())
        }
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
        setSize(initial.roundToInt())
        val x = if (storedX != Int.MIN_VALUE) storedX else safe.right - pad - margin - cardWidth
        val y = if (storedY != Int.MIN_VALUE) storedY else safe.top + (TOP_DP * density).roundToInt()
        placeCard(x, y)
    }

    fun show() {
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@PhoneBackgroundPreview)
            setViewTreeSavedStateRegistryOwner(this@PhoneBackgroundPreview)
            setViewTreeViewModelStoreOwner(this@PhoneBackgroundPreview)
            // Inside apply, a bare Content() binds to ComposeView.Content, which calls this lambda again without end.
            setContent { PhoneOverlayTheme(runtime.settings) { PreviewCard() } }
        }
        root = view
        try { windows.addView(view, params) } catch (error: Exception) { view.disposeComposition(); throw error }
        added = true
        markPushed()
        context.applicationContext.registerComponentCallbacks(configuration)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        scope.launch { runtime.fullscreen.collect { hidden -> setHidden(hidden) } }
    }

    fun remove() {
        scope.cancel()
        settling?.cancel()
        settling = null
        if (added) context.applicationContext.unregisterComponentCallbacks(configuration)
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
        val byWidth = safe.width() - 2 * (pad + margin)
        val byHeight = ((safe.height() - 2 * (pad + margin) - handleZone) * aspect).toInt()
        return min(byWidth, byHeight).coerceAtLeast(minWidth)
    }

    private fun setSize(width: Int) {
        val w = width.coerceIn(minWidth, maxWidth())
        cardWidth = w
        cardHeight = (w / aspect).roundToInt()
    }

    private fun minCardX() = safe.left + pad
    private fun maxCardX() = max(minCardX(), safe.right - pad - cardWidth)
    private fun minCardY() = safe.top + pad + handleZone
    private fun maxCardY() = max(minCardY(), safe.bottom - pad - cardHeight)

    private fun placeCard(x: Int, y: Int) {
        cardX = x.coerceIn(minCardX(), maxCardX())
        cardY = y.coerceIn(minCardY(), maxCardY())
        layoutWindow()
    }

    /** Derives the window from the card: a tight frame normally, the safe area while resizing. */
    private fun layoutWindow() {
        if (workspace) {
            params.x = safe.left
            params.y = safe.top
            params.width = safe.width()
            params.height = safe.height()
        } else {
            params.x = cardX - pad
            params.y = cardY - pad - handleZone
            params.width = cardWidth + 2 * pad
            params.height = cardHeight + 2 * pad + handleZone
        }
        cardLeft = cardX - params.x
        cardTop = cardY - params.y
        update()
    }

    /** Skips relayouts that change nothing; during a resize only the card state moves. */
    private fun update() {
        if (!added) return
        val now = intArrayOf(params.x, params.y, params.width, params.height, params.flags, params.alpha.toRawBits())
        if (now.contentEquals(pushed)) return
        root?.let { view -> runCatching { windows.updateViewLayout(view, params) } }
        now.copyInto(pushed)
    }

    private fun markPushed() {
        intArrayOf(params.x, params.y, params.width, params.height, params.flags, params.alpha.toRawBits())
            .copyInto(pushed)
    }

    private fun savePlacement() {
        storedWidth = cardWidth
        storedX = cardX
        storedY = cardY
        // The full screen view shrinks back into this rect, which a rotation may have moved.
        runtime.previewCard = Rect(cardX, cardY, cardX + cardWidth, cardY + cardHeight)
    }

    /** Snap clear of the screen edge with a short ease; the drag itself follows the finger exactly. */
    private fun settle() {
        settling?.cancel()
        settling = null
        savePlacement()
        val loX = minCardX() + margin
        val loY = minCardY()
        val targetX = cardX.coerceIn(loX, max(loX, maxCardX() - margin))
        val targetY = cardY.coerceIn(loY, max(loY, maxCardY() - margin))
        if (targetX == cardX && targetY == cardY) return
        val fromX = cardX
        val fromY = cardY
        settling = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                placeCard(
                    (fromX + (targetX - fromX) * progress).roundToInt(),
                    (fromY + (targetY - fromY) * progress).roundToInt(),
                )
                // A grab mid-snap cancels this animator; the place it stopped is the place kept.
                savePlacement()
            }
            start()
        }
    }

    private fun openFullscreen() {
        if (runtime.fullscreen.value) return
        val card = Rect(cardX, cardY, cardX + cardWidth, cardY + cardHeight)
        runtime.previewCard = card
        runtime.fullscreen.value = true
        runCatching { context.startActivity(PhoneBackgroundActivity.intent(context, card)) }
            .onFailure { runtime.fullscreen.value = false }
    }

    private fun begin(event: MotionEvent, kind: Gesture) {
        // A running snap would keep writing the position under the finger.
        settling?.cancel()
        settling = null
        downRawX = event.rawX
        downRawY = event.rawY
        startX = cardX
        startY = cardY
        startWidth = cardWidth
        startHeight = cardHeight
        moved = false
        gesture = kind
        lifted = kind == Gesture.MOVE
    }

    private fun track(event: MotionEvent) {
        if (gesture == Gesture.NONE) return
        val dx = event.rawX - downRawX
        val dy = event.rawY - downRawY
        if (!moved) {
            if (hypot(dx, dy) <= slop) return
            moved = true
            // Measured from where the finger crossed the slop, so the card does not jump by it.
            downRawX = event.rawX
            downRawY = event.rawY
            if (gesture != Gesture.MOVE) {
                workspace = true
                layoutWindow()
            }
            return
        }
        if (gesture == Gesture.MOVE) placeCard((startX + dx).roundToInt(), (startY + dy).roundToInt())
        else resizeBy(gesture, dx, dy)
    }

    private fun finish(tap: Boolean) {
        val kind = gesture
        gesture = Gesture.NONE
        lifted = false
        if (workspace) {
            workspace = false
            layoutWindow()
        }
        if (moved) settle() else if (tap && kind == Gesture.MOVE) openFullscreen()
        moved = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun cardTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(event, hit(event.x, event.y))
            MotionEvent.ACTION_MOVE -> track(event)
            MotionEvent.ACTION_UP -> finish(tap = true)
            MotionEvent.ACTION_CANCEL -> finish(tap = false)
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(event, Gesture.MOVE)
            MotionEvent.ACTION_MOVE -> track(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish(tap = false)
        }
        return true
    }

    /** Corner and edge grips own the outer band of the card; the rest of it drags. */
    private fun hit(x: Float, y: Float): Gesture {
        val corner = CORNER_HIT_DP * density
        val width = cardWidth.toFloat()
        val height = cardHeight.toFloat()
        return when {
            y > height - corner * 1.4f && x < corner -> Gesture.BOTTOM_START
            y > height - corner * 1.4f && x > width - corner -> Gesture.BOTTOM_END
            y > height - CENTER_HIT_DP * density && abs(x - width / 2) < 44 * density -> Gesture.BOTTOM_CENTER
            else -> Gesture.MOVE
        }
    }

    /**
     * The grabbed point slides along the line the locked ratio allows; the finger's offset is
     * projected onto that line, so diagonal drags track smoothly instead of flipping between axes.
     */
    private fun resizeBy(kind: Gesture, dx: Float, dy: Float) {
        val w0 = startWidth.toFloat()
        val h0 = startHeight.toFloat()
        val growth = when (kind) {
            Gesture.BOTTOM_END -> (dx * w0 + dy * h0) / (w0 * w0 + h0 * h0)
            Gesture.BOTTOM_START -> (-dx * w0 + dy * h0) / (w0 * w0 + h0 * h0)
            else -> dy / h0
        }
        val right = startX + startWidth
        val centerX = startX + startWidth / 2f
        // The anchored edge stays put, so the room on the free side bounds the size.
        val room = when (kind) {
            Gesture.BOTTOM_END -> (safe.right - pad - startX).toFloat()
            Gesture.BOTTOM_START -> (right - safe.left - pad).toFloat()
            else -> 2f * min(centerX - safe.left - pad, safe.right - pad - centerX)
        }
        val roomBelow = (safe.bottom - pad - startY) * aspect
        val limit = min(min(room, roomBelow), maxWidth().toFloat()).coerceAtLeast(minWidth.toFloat())
        val width = (w0 * (1f + growth)).coerceIn(minWidth.toFloat(), limit).roundToInt()
        setSize(width)
        cardX = when (kind) {
            Gesture.BOTTOM_END -> startX
            Gesture.BOTTOM_START -> right - cardWidth
            else -> (centerX - cardWidth / 2f).roundToInt()
        }
        cardY = startY
        layoutWindow()
    }

    @Composable
    private fun PreviewCard() {
        val frame by screen.frame.collectAsState()
        val lift by animateFloatAsState(
            targetValue = if (lifted) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            label = "lift",
        )
        val shape = RoundedCornerShape(CORNER_DP.dp)
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .offset { IntOffset(cardLeft, cardTop - handleZone) }
                    .exactSize(cardWidth, cardHeight + handleZone)
                    .graphicsLayer {
                        val grow = 1f + lift * LIFT_DP.dp.toPx() * 2f / cardWidth.coerceAtLeast(1)
                        scaleX = grow
                        scaleY = grow
                        transformOrigin = TransformOrigin(
                            0.5f, (handleZone + cardHeight / 2f) / (handleZone + cardHeight).coerceAtLeast(1),
                        )
                    },
            ) {
                Box(
                    Modifier
                        .exactSize(cardWidth, handleZone)
                        .pointerInteropFilter { event -> handleTouch(event) },
                ) {
                    Canvas(Modifier.fillMaxSize()) { drawHandle() }
                }
                Box(
                    Modifier
                        .offset { IntOffset(0, handleZone) }
                        .exactSize(cardWidth, cardHeight)
                        .graphicsLayer {
                            shadowElevation = (SHADOW_DP + (LIFTED_SHADOW_DP - SHADOW_DP) * lift).dp.toPx()
                            this.shape = shape
                            clip = true
                        }
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
            }
        }
    }

    /** Pixel-exact size; dp round trips would leave the card a pixel off its window frame. */
    private fun Modifier.exactSize(width: Int, height: Int) = layout { measurable, _ ->
        val placeable = measurable.measure(Constraints.fixed(width.coerceAtLeast(0), height.coerceAtLeast(0)))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    private fun DrawScope.drawHandle() {
        val width = 3.dp.toPx()
        val half = 22.dp.toPx()
        val centerX = size.width / 2
        val centerY = size.height - HANDLE_GAP_DP.dp.toPx() - width / 2
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
        val radius = 16.dp.toPx()
        val inset = GRIP_INSET_DP.dp.toPx()
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
        /** Transparent frame around the card: room for the lift and its deeper shadow. */
        const val PAD_DP = 14
        const val HANDLE_ZONE_DP = 20
        const val HANDLE_GAP_DP = 7
        const val GRIP_INSET_DP = 7
        const val CORNER_DP = 14
        const val SHADOW_DP = 8
        const val LIFTED_SHADOW_DP = 14
        /** Each side of a held card grows by this much, whatever the card's size. */
        const val LIFT_DP = 4
        const val MARGIN_DP = 6
        const val TOP_DP = 88
        const val CORNER_HIT_DP = 30f
        const val CENTER_HIT_DP = 30f

        // Survives release and reclaim within the process, so the card returns where it was left.
        private var storedWidth = 0
        private var storedX = Int.MIN_VALUE
        private var storedY = Int.MIN_VALUE
    }
}
