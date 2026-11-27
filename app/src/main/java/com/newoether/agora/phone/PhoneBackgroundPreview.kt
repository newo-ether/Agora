package com.newoether.agora.phone

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.MotionEvent
import android.view.ViewConfiguration
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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.animation.doOnEnd
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
import com.newoether.agora.R
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
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
 * The floating window of the background screen. The card shows live frames, a bar above it drags
 * it and opens a menu on tap, and three grips outside its bottom edge resize it with the display's
 * aspect ratio locked. Tapping the card opens [PhoneBackgroundActivity]; the window hides itself
 * while that view owns the screen. Minimized, the card becomes a capsule with the current action.
 *
 * The visible element's screen rect is the model; the window is derived from it. While a grip
 * resizes the card or the card morphs to or from the capsule, the window spans the whole safe area
 * and only the content moves inside it. Offsets inside the window are chosen at layout time from the
 * window size actually laid out, so the frame where the window switches between a tight frame and
 * the safe area cannot draw anything at the wrong place.
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
    private var cardX by mutableIntStateOf(0)
    private var cardY by mutableIntStateOf(0)
    private var cardWidth by mutableIntStateOf(0)
    private var cardHeight by mutableIntStateOf(0)

    /** Capsule rect on screen; its size is measured from the label it shows. */
    private var capsuleX by mutableIntStateOf(0)
    private var capsuleY by mutableIntStateOf(0)
    private var capsuleWidth by mutableIntStateOf(0)
    private var capsuleHeight by mutableIntStateOf(0)

    /** Origins inside the window as last laid out; touches are hit-tested against them. */
    private var shownX = 0
    private var shownY = 0
    private var capsuleShownX = 0
    private var capsuleShownY = 0

    /** True while a drag holds the card; the card grows a little under the finger. */
    private var lifted by mutableStateOf(false)

    /** The grip or bar being used; it thickens. */
    private var active by mutableStateOf(Gesture.NONE)

    /** Entrance and exit, 0 gone to 1 shown. */
    private var presence by mutableFloatStateOf(0f)
    private var presenting: ValueAnimator? = null
    private var leaving = false

    /** Target state, and the morph between card (0) and capsule (1). */
    private var minimized by mutableStateOf(false)
    private var morph by mutableFloatStateOf(0f)
    private var morphing: ValueAnimator? = null
    /** True while the morph body stands in for both the card and the capsule. */
    private var morphShown by mutableStateOf(false)

    private var menuOpen by mutableStateOf(false)

    /** True while a grip resizes the card and the window spans the safe area. */
    private var workspace = false
    private var root: ComposeView? = null
    private var added = false
    private val pushed = IntArray(6) { Int.MIN_VALUE }

    /** Screen area that excludes the system bars, so the card never hides under them. */
    private var safe: Rect = previewSafeArea(context, density)
    private var settling: ValueAnimator? = null

    /** A rotation changes the safe area; the card is refitted into the new one. */
    private val configuration = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            safe = previewSafeArea(context, density)
            settling?.cancel()
            settling = null
            finishMorph()
            workspace = false
            lifted = false
            menuOpen = false
            gesture = Gesture.NONE
            setSize(cardWidth)
            placeCard(cardX, cardY)
            placeCapsule(capsuleX, capsuleY)
            savePlacement()
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    private val params = previewWindowParams()

    private var gesture = Gesture.NONE
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var startWidth = 0
    private var startHeight = 0
    private var moved = false
    private var origin = Gesture.NONE

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
            setContent { PhoneOverlayTheme(runtime.settings) { PreviewWindow() } }
        }
        root = view
        try { windows.addView(view, params) } catch (error: Exception) { view.disposeComposition(); throw error }
        added = true
        markPushed()
        context.applicationContext.registerComponentCallbacks(configuration)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        scope.launch { runtime.fullscreen.collect { hidden -> setHidden(hidden) } }
        present(1f, ENTER_MS)
    }

    /** Scales and fades out, then removes the window. Hidden behind full screen, it goes at once. */
    fun dismiss() {
        if (!added || leaving) return
        if (runtime.fullscreen.value) return remove()
        leaving = true
        menuOpen = false
        gesture = Gesture.NONE
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        update()
        present(0f, EXIT_MS) { remove() }
    }

    fun remove() {
        scope.cancel()
        settling?.cancel()
        settling = null
        presenting?.let { presenting = null; it.cancel() }
        morphing?.let { morphing = null; it.cancel() }
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

    private fun present(target: Float, duration: Long, then: (() -> Unit)? = null) {
        presenting?.let { presenting = null; it.cancel() }
        val animator = ValueAnimator.ofFloat(presence, target)
        presenting = animator
        animator.duration = duration
        animator.interpolator = if (target > 0f) EMPHASIZED else EXIT
        animator.addUpdateListener { presence = it.animatedValue as Float }
        animator.doOnEnd {
            if (presenting === animator) {
                presenting = null
                then?.invoke()
            }
        }
        animator.start()
    }

    /** The full-screen view is opaque over this window; it must not intercept touches there. */
    private fun setHidden(hidden: Boolean) {
        if (hidden) menuOpen = false
        params.alpha = if (hidden) 0f else 1f
        params.flags = if (hidden || leaving) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
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

    private fun placeCapsule(x: Int, y: Int) {
        val left = safe.left + pad + margin
        val top = safe.top + pad
        capsuleX = x.coerceIn(left, max(left, safe.right - pad - margin - capsuleWidth))
        capsuleY = y.coerceIn(top, max(top, safe.bottom - pad - capsuleHeight))
        layoutWindow()
    }

    /** A new label resizes the capsule; the side nearer the screen edge stays put. */
    private fun capsuleMeasured(size: IntSize) {
        if (size.width == capsuleWidth && size.height == capsuleHeight) return
        val right = capsuleX + capsuleWidth
        val keepRight = capsuleWidth > 0 && capsuleX + capsuleWidth / 2 > safe.centerX()
        capsuleWidth = size.width
        capsuleHeight = size.height
        if (minimized) placeCapsule(if (keepRight) right - capsuleWidth else capsuleX, capsuleY)
    }

    /**
     * Derives the window from what is shown: the safe area while resizing or morphing, else a tight
     * frame around the card or the capsule.
     */
    private fun layoutWindow() {
        when {
            workspace || morphing != null -> {
                params.x = safe.left
                params.y = safe.top
                params.width = safe.width()
                params.height = safe.height()
            }
            minimized -> {
                params.x = capsuleX - pad
                params.y = capsuleY - pad
                params.width = capsuleWidth + 2 * pad
                params.height = capsuleHeight + 2 * pad
            }
            else -> {
                params.x = cardX - pad
                params.y = cardY - pad - handleZone
                params.width = cardWidth + 2 * pad
                params.height = cardHeight + 2 * pad + handleZone
            }
        }
        update()
    }

    /**
     * Origin of a screen rect inside a window of the given laid-out size. The window manager applies
     * a new frame asynchronously, so neither the state nor the requested params say which frame is
     * live. A tight frame always holds its element at [tight], which also covers a move, where the
     * window follows the element. Any other frame stays put while the element changes, and the
     * layout pass runs after the frame is applied, so the window's real screen position is exact.
     */
    private fun offsetIn(isTight: Boolean, tight: IntOffset, x: Int, y: Int): IntOffset {
        if (isTight) return tight
        val at = IntArray(2)
        root?.getLocationOnScreen(at) ?: return tight
        return IntOffset(x - at[0], y - at[1])
    }

    private fun cardOffset(windowWidth: Int, windowHeight: Int) = offsetIn(
        !minimized && windowWidth == cardWidth + 2 * pad && windowHeight == cardHeight + 2 * pad + handleZone,
        IntOffset(pad, pad + handleZone), cardX, cardY,
    )

    private fun capsuleOffset(windowWidth: Int, windowHeight: Int) = offsetIn(
        minimized && windowWidth == capsuleWidth + 2 * pad && windowHeight == capsuleHeight + 2 * pad,
        IntOffset(pad, pad), capsuleX, capsuleY,
    )

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
            interpolator = EMPHASIZED
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
        if (runtime.fullscreen.value || minimized) return
        val card = Rect(cardX, cardY, cardX + cardWidth, cardY + cardHeight)
        runtime.previewCard = card
        runtime.fullscreen.value = true
        runCatching { context.startActivity(PhoneBackgroundActivity.intent(context, card)) }
            .onFailure { runtime.fullscreen.value = false }
    }

    /** The card shrinks into the capsule at its top, on the side nearer the screen edge. */
    private fun minimize() {
        if (minimized) return
        settling?.cancel()
        settling = null
        savePlacement()
        val keepRight = cardX + cardWidth / 2 > safe.centerX()
        minimized = true
        placeCapsule(if (keepRight) cardX + cardWidth - capsuleWidth else cardX, cardY)
        animateMorph(1f)
    }

    /** The card grows back out of the capsule, keeping the same corner. */
    private fun restore() {
        if (!minimized) return
        val keepRight = capsuleX + capsuleWidth / 2 > safe.centerX()
        minimized = false
        val loX = minCardX() + margin
        val x = if (keepRight) capsuleX + capsuleWidth - cardWidth else capsuleX
        placeCard(x.coerceIn(loX, max(loX, maxCardX() - margin)), capsuleY.coerceAtMost(maxCardY() - margin))
        savePlacement()
        animateMorph(0f)
    }

    private fun animateMorph(target: Float) {
        morphing?.let { morphing = null; it.cancel() }
        val animator = ValueAnimator.ofFloat(morph, target)
        morphing = animator
        animator.duration = MORPH_MS
        animator.interpolator = EMPHASIZED
        animator.addUpdateListener { morph = it.animatedValue as Float }
        animator.doOnEnd {
            if (morphing === animator) {
                morphing = null
                morphShown = false
                layoutWindow()
            }
        }
        morphShown = true
        layoutWindow()
        animator.start()
    }

    private fun finishMorph() {
        morphing?.let { morphing = null; it.cancel() }
        morphShown = false
        morph = if (minimized) 1f else 0f
    }

    private fun begin(event: MotionEvent, kind: Gesture) {
        // A running snap would keep writing the position under the finger.
        settling?.cancel()
        settling = null
        downRawX = event.rawX
        downRawY = event.rawY
        startX = if (kind == Gesture.CAPSULE) capsuleX else cardX
        startY = if (kind == Gesture.CAPSULE) capsuleY else cardY
        startWidth = cardWidth
        startHeight = cardHeight
        moved = false
        origin = kind
        gesture = if (kind == Gesture.CARD || kind == Gesture.HANDLE || kind == Gesture.CAPSULE) Gesture.MOVE else kind
        lifted = gesture == Gesture.MOVE
        // The card itself only shows the bar as active once it is really being dragged.
        active = if (kind == Gesture.CARD || kind == Gesture.CAPSULE) Gesture.NONE else gesture
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
            } else if (origin != Gesture.CAPSULE) active = Gesture.MOVE
            return
        }
        when {
            gesture != Gesture.MOVE -> resizeBy(gesture, dx, dy)
            origin == Gesture.CAPSULE -> placeCapsule((startX + dx).roundToInt(), (startY + dy).roundToInt())
            else -> placeCard((startX + dx).roundToInt(), (startY + dy).roundToInt())
        }
    }

    private fun finish(tap: Boolean) {
        val kind = origin
        gesture = Gesture.NONE
        lifted = false
        active = Gesture.NONE
        if (workspace) {
            workspace = false
            layoutWindow()
        }
        if (moved) {
            if (kind != Gesture.CAPSULE) settle()
        } else if (tap) when (kind) {
            Gesture.CARD -> openFullscreen()
            Gesture.HANDLE -> menuOpen = true
            Gesture.CAPSULE -> restore()
            else -> Unit
        }
        moved = false
        origin = Gesture.NONE
    }

    /** One listener for the whole window: the grips sit outside the card, in its transparent frame. */
    @SuppressLint("ClickableViewAccessibility")
    private fun touch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> hit(event.x, event.y).let { kind -> if (kind != Gesture.NONE) begin(event, kind) }
            MotionEvent.ACTION_MOVE -> track(event)
            MotionEvent.ACTION_UP -> finish(tap = true)
            MotionEvent.ACTION_CANCEL -> finish(tap = false)
        }
        return true
    }

    /**
     * Grip zones reach from a little inside the card out through the frame around it; the band
     * above the card is the bar; the rest of the card drags and taps. Nothing is grabbed mid-morph.
     */
    private fun hit(windowX: Float, windowY: Float): Gesture {
        if (morphing != null || leaving) return Gesture.NONE
        if (minimized) {
            val x = windowX - capsuleShownX
            val y = windowY - capsuleShownY
            return if (x in 0f..capsuleWidth.toFloat() && y in 0f..capsuleHeight.toFloat()) Gesture.CAPSULE
                else Gesture.NONE
        }
        val x = windowX - shownX
        val y = windowY - shownY
        val corner = CORNER_HIT_DP * density
        val width = cardWidth.toFloat()
        val height = cardHeight.toFloat()
        val reach = pad.toFloat()
        if (x < -reach || x > width + reach || y > height + reach) return Gesture.NONE
        val centerHalf = max(PreviewGrip.BAR_RATIO * width / 2 + 14 * density, 30 * density)
        return when {
            y < 0 -> if (y >= -handleZone - reach && x >= 0 && x <= width) Gesture.HANDLE else Gesture.NONE
            y > height - corner * 1.4f && x < corner -> Gesture.BOTTOM_START
            y > height - corner * 1.4f && x > width - corner -> Gesture.BOTTOM_END
            y > height - CENTER_HIT_DP * density && abs(x - width / 2) < centerHalf -> Gesture.BOTTOM_CENTER
            x in 0f..width && y in 0f..height -> Gesture.CARD
            else -> Gesture.NONE
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
    private fun gripWeight(kind: Gesture): State<Float> = animateFloatAsState(
        targetValue = if (active == kind) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "grip",
    )

    @Composable
    private fun PreviewWindow() {
        val motion = LocalAgoraMotionPolicy.current
        val still = motion.reduceMotion || motion.systemAnimationsDisabled
        val lift by animateFloatAsState(
            targetValue = if (lifted) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            label = "lift",
        )
        Box(
            Modifier
                .fillMaxSize()
                .pointerInteropFilter { event -> touch(event) }
                .graphicsLayer {
                    // Grows from and shrinks into the center of whatever is shown.
                    alpha = presence
                    val scale = if (still) 1f else ENTER_SCALE + (1f - ENTER_SCALE) * presence
                    scaleX = scale
                    scaleY = scale
                    val (cx, cy) = if (minimized) capsuleShownX + capsuleWidth / 2f to capsuleShownY + capsuleHeight / 2f
                        else shownX + cardWidth / 2f to shownY + (cardHeight - handleZone) / 2f
                    transformOrigin = TransformOrigin(
                        cx / size.width.coerceAtLeast(1f), cy / size.height.coerceAtLeast(1f),
                    )
                },
        ) {
            val bitmap = retainedFrame(screen)
            val action by runtime.actionName.collectAsState()
            val label = action ?: stringResource(R.string.phone_thinking)
            Card(bitmap, lift)
            Capsule(label, lift)
            if (morphShown) PreviewMorphBody(bitmap, label, morph, SHADOW_DP.dp, Modifier.morphPlacement())
        }
    }

    @Composable
    private fun Card(bitmap: Bitmap?, lift: Float) {
        val bar by gripWeight(Gesture.MOVE)
        val start by gripWeight(Gesture.BOTTOM_START)
        val end by gripWeight(Gesture.BOTTOM_END)
        val center by gripWeight(Gesture.BOTTOM_CENTER)
        val shape = RoundedCornerShape(PreviewGrip.CORNER_DP.dp)
        Box(
            Modifier
                .cardPlacement()
                .graphicsLayer {
                    val grow = 1f + lift * LIFT_DP.dp.toPx() * 2f / cardWidth.coerceAtLeast(1)
                    scaleX = grow
                    scaleY = grow
                    alpha = if (minimized && !morphShown) 0f else 1f
                    // The lift grows about the card's center, below the bar band.
                    val liftY = (handleZone + cardHeight / 2f) / (handleZone + cardHeight).coerceAtLeast(1)
                    transformOrigin = TransformOrigin(0.5f, liftY)
                },
        ) {
            Box(
                Modifier
                    .offset { IntOffset(0, handleZone) }
                    .previewExactSize(cardWidth, cardHeight)
                    .graphicsLayer {
                        // The morph body draws the card while it changes shape.
                        alpha = if (morphShown) 0f else 1f
                        shadowElevation = (SHADOW_DP + (LIFTED_SHADOW_DP - SHADOW_DP) * lift).dp.toPx()
                        this.shape = shape
                        clip = true
                    }
                    .background(Color.Black),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    bitmap?.let { image ->
                        drawImage(
                            image.asImageBitmap(),
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        )
                    }
                }
            }
            // Unclipped, so the grips draw in the transparent frame outside the card.
            Canvas(
                Modifier.offset { IntOffset(0, handleZone) }.previewExactSize(cardWidth, cardHeight)
                    .graphicsLayer { alpha = if (morphShown) (1f - morph * 4f).coerceAtLeast(0f) else 1f },
            ) {
                drawPreviewGrips(bar, start, end, center)
            }
            // The menu's anchor is the bar band, so it drops from where it was tapped.
            Box(Modifier.previewExactSize(cardWidth, handleZone)) {
                PreviewMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    onFullscreen = ::openFullscreen,
                    onMinimize = ::minimize,
                    onClose = runtime::closePreview,
                    onStop = runtime::stopByUser,
                )
            }
        }
    }

    /** Always composed, so its size is known before a minimize; drawn only once minimized. */
    @Composable
    private fun Capsule(label: String, lift: Float) {
        PreviewCapsule(
            label = label,
            modifier = Modifier
                .capsulePlacement()
                .onSizeChanged(::capsuleMeasured)
                .graphicsLayer {
                    val grow = 1f + lift * CAPSULE_LIFT
                    scaleX = grow
                    scaleY = grow
                    alpha = if (minimized && !morphShown) 1f else 0f
                },
        )
    }

    /** Between the card's and the capsule's screen rects by [morph], in the safe-area window. */
    private fun Modifier.morphPlacement() = layout { measurable, constraints ->
        val at = IntArray(2)
        root?.getLocationOnScreen(at)
        fun mix(from: Int, to: Int) = (from + (to - from) * morph).roundToInt()
        val placeable = measurable.measure(
            Constraints.fixed(mix(cardWidth, capsuleWidth).coerceAtLeast(0), mix(cardHeight, capsuleHeight).coerceAtLeast(0)),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(mix(cardX, capsuleX) - at[0], mix(cardY, capsuleY) - at[1])
        }
    }

    /**
     * Sizes the card with its bar band and places it for the window size this pass was handed,
     * which is the size the window manager has actually applied.
     */
    private fun Modifier.cardPlacement() = layout { measurable, constraints ->
        val placeable = measurable.measure(
            Constraints.fixed(cardWidth.coerceAtLeast(0), (cardHeight + handleZone).coerceAtLeast(0)),
        )
        val offset = cardOffset(constraints.maxWidth, constraints.maxHeight)
        shownX = offset.x
        shownY = offset.y
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(offset.x, offset.y - handleZone) }
    }

    /** Wraps the capsule's own size and places it like [cardPlacement]. */
    private fun Modifier.capsulePlacement() = layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints())
        val offset = capsuleOffset(constraints.maxWidth, constraints.maxHeight)
        capsuleShownX = offset.x
        capsuleShownY = offset.y
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(offset.x, offset.y) }
    }

    /** CARD, HANDLE and CAPSULE are hit results; each starts a MOVE and may end as a tap. */
    private enum class Gesture { NONE, MOVE, CARD, HANDLE, CAPSULE, BOTTOM_START, BOTTOM_END, BOTTOM_CENTER }

    private companion object {
        const val CARD_WIDTH_DP = 132
        const val WIDE_WIDTH_DP = 220
        const val MIN_WIDTH_DP = 88
        /** Transparent frame around the card: room for the outer grips, the lift and its shadow. */
        const val PAD_DP = 20
        const val HANDLE_ZONE_DP = 22
        const val SHADOW_DP = 8
        const val LIFTED_SHADOW_DP = 14
        /** Each side of a held card grows by this much, whatever the card's size. */
        const val LIFT_DP = 4
        const val CAPSULE_LIFT = 0.05f
        const val MARGIN_DP = 6
        const val TOP_DP = 88
        /** How far grip zones reach into the card; outside it they cover the whole frame. */
        const val CORNER_HIT_DP = 22f
        const val CENTER_HIT_DP = 22f
        const val ENTER_MS = 280L
        const val EXIT_MS = 200L
        const val MORPH_MS = 380L
        /** Scale the window enters from and leaves to. */
        const val ENTER_SCALE = 0.85f
        val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
        val EXIT = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

        // Survives release and reclaim within the process, so the card returns where it was left.
        private var storedWidth = 0
        private var storedX = Int.MIN_VALUE
        private var storedY = Int.MIN_VALUE
    }
}
