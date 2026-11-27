package com.newoether.agora.phone

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
import com.newoether.agora.util.withAppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.floor

/** Owns exactly two windows. Transparent control gaps are excluded from the input region. */
internal class PhoneOverlay(
    private val context: Context,
    private val runtime: PhoneUseRuntime,
    val epoch: Long,
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    private var manager: WindowManager? = null
    private var mainContext: Context? = null
    private var effect: ComposeView? = null
    private var effectManager: WindowManager? = null
    private var effectType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private var effectAlpha = 1f
    /** The background display the effect window draws on; INVALID_DISPLAY while it is on the phone. */
    var effectDisplayId: Int = Display.INVALID_DISPLAY
        private set
    private var control: ComposeView? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controlWindow: PhoneControlWindow? = null
    private val touchBounds = mutableMapOf<String, Rect>()
    private var removed = false
    val capturing = androidx.compose.runtime.mutableStateOf(false)
    val visible = androidx.compose.runtime.mutableStateOf(true)
    /** True while the status strip draws on the background screen rather than the phone. */
    private val stripOnDisplay = androidx.compose.runtime.mutableStateOf(false)

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    private fun composeView(viewContext: Context) = ComposeView(viewContext).apply {
        setViewTreeLifecycleOwner(this@PhoneOverlay)
        setViewTreeSavedStateRegistryOwner(this@PhoneOverlay)
        setViewTreeViewModelStoreOwner(this@PhoneOverlay)
    }

    private fun addEffect(windows: WindowManager, viewContext: Context, keepScreenOn: Boolean, onDisplay: Boolean = false) {
        val effectView = composeView(viewContext)
        effectView.setContent { PhoneOverlayTheme(runtime.settings) {
            PhoneEffect(runtime, this)
            // The strip reports status where the AI acts; Stop lives in the notification and the
            // chat. It vanishes in the same frame a capture starts, so no screenshot shows it.
            if (onDisplay) Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (capturing.value) 0f else 1f }) {
                PhoneControls(runtime, epoch, {}, { _, _ -> }, visible = visible.value,
                    questionsShown = false, interactive = false)
            }
        } }
        val effectParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, effectType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                (if (keepScreenOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0) or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        // InputDispatcher combines both windows' alpha even outside their touchable regions.
        effectParams.alpha = effectAlpha
        if (Build.VERSION.SDK_INT >= 30) effectParams.setFitInsetsTypes(0)
        if (Build.VERSION.SDK_INT >= 28) effectParams.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        try { windows.addView(effectView, effectParams) }
        catch (error: Exception) { effectView.disposeComposition(); throw error }
        effect = effectView
        effectManager = windows
    }

    private fun removeEffect() {
        val view = effect ?: return
        runCatching { (effectManager ?: manager)?.removeViewImmediate(view) }
        view.disposeComposition()
        effect = null
        effectManager = null
    }

    /**
     * Main only. Moves the border and cursor onto [display], the background screen, so they appear
     * where the AI acts. Returns false after falling back to the phone's own screen.
     */
    fun attachEffect(display: Display?): Boolean {
        if (removed) return false
        removeEffect()
        effectDisplayId = Display.INVALID_DISPLAY
        if (display != null) {
            val placed = runCatching {
                val displayContext = context.createDisplayContext(display)
                val windowContext = if (Build.VERSION.SDK_INT >= 30) {
                    displayContext.createWindowContext(effectType, null)
                } else displayContext
                addEffect(windowContext.getSystemService(WindowManager::class.java),
                    windowContext.withAppLocale(runtime.settings.appLanguage.value),
                    keepScreenOn = false, onDisplay = true)
            }.isSuccess
            if (placed) {
                effectDisplayId = display.displayId
                stripOnDisplay.value = true
                return true
            }
        }
        stripOnDisplay.value = false
        val windows = manager ?: return false
        runCatching { addEffect(windows, mainContext ?: context, keepScreenOn = true) }
        return false
    }

    /** @param effectOnPhone false when [attachEffect] will place the effect on the background screen. */
    fun show(mode: String, effectOnPhone: Boolean = true) {
        val windowContext = if (mode == "accessibility") PhoneAccessibilityService.instance
            ?: throw PhoneFailure("permission_lost:accessibility_unavailable") else context
        val windows = windowContext.getSystemService(WindowManager::class.java)
        val localizedContext = windowContext.withAppLocale(runtime.settings.appLanguage.value)
        manager = windows
        mainContext = localizedContext
        val type = if (mode == "accessibility") WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        effectType = type
        val windowAlpha = if (Build.VERSION.SDK_INT >= 31 && mode != "accessibility") {
            phoneOverlayWindowAlpha(context.getSystemService(android.hardware.input.InputManager::class.java)
                .maximumObscuringOpacityForTouch)
        } else 1f
        effectAlpha = windowAlpha
        val controls = composeView(localizedContext).also { control = it }
        controls.setContent { PhoneOverlayTheme(runtime.settings) {
            PhoneControls(runtime, epoch, ::focusable, ::bounds, visible = visible.value,
                strip = !stripOnDisplay.value)
        } }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        params.alpha = 1f
        params.gravity = Gravity.TOP or Gravity.START
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        controlParams = params
        controls.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && touchBounds.any { (key, rect) ->
                    key.startsWith("text-") && rect.contains(event.x.toInt(), event.y.toInt())
                }) focusable(true)
            false
        }
        try {
            if (effectOnPhone) addEffect(windows, localizedContext, keepScreenOn = true)
            windows.addView(controls, params)
            controlWindow = PhoneControlWindow(controls, params, windows, operationAlpha = windowAlpha)
            controlScope.launch {
                combine(runtime.lease.held.map { it?.takeIf { current -> current.epoch == epoch }?.owner }
                    .distinctUntilChanged(), runtime.askUser.requests) { owner, requests ->
                    owner != null && requests.any {
                        it.conversationId == owner.conversationId && it.runId == owner.runId
                    }
                }.collect { waiting -> controlWindow?.showForUser(waiting) }
            }
            installTouchRegion(controls)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        } catch (error: Exception) { remove(); throw error }
    }

    private fun bounds(key: String, value: ComposeRect?) {
        if (value == null) touchBounds.remove(key)
        else touchBounds[key] = Rect(floor(value.left).toInt(), floor(value.top).toInt(),
            ceil(value.right).toInt(), ceil(value.bottom).toInt())
        control?.requestLayout()
    }

    private fun region() = Region().apply {
        if (!capturing.value && visible.value && controlWindow?.touchable == true)
            touchBounds.values.forEach { op(it, Region.Op.UNION) }
    }

    private fun installTouchRegion(view: View) {
        if (Build.VERSION.SDK_INT >= 33) {
            view.viewTreeObserver.addOnPreDrawListener {
                view.rootSurfaceControl?.setTouchableRegion(region())
                true
            }
        } else {
            // Before API 33 this platform window-insets API has no public SDK equivalent.
            // Fail claim if the OEM denies it, rather than silently intercepting other apps.
            val listener = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val info = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
            val setInsets = info.getMethod("setTouchableInsets", Integer.TYPE)
            val field = info.getField("touchableRegion")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(listener.classLoader, arrayOf(listener)) { instance, method, args ->
                when (method.name) {
                    "onComputeInternalInsets" -> {
                        val target = checkNotNull(args?.firstOrNull())
                        setInsets.invoke(target, 3)
                        (field.get(target) as Region).set(region())
                        null
                    }
                    "hashCode" -> System.identityHashCode(instance)
                    "equals" -> instance === args?.firstOrNull()
                    "toString" -> "PhoneControlInsets"
                    else -> null
                }
            }
            view.viewTreeObserver.javaClass.getMethod("addOnComputeInternalInsetsListener", listener)
                .invoke(view.viewTreeObserver, proxy)
        }
    }

    private fun focusable(focused: Boolean) {
        if (!visible.value || removed) return
        val params = controlParams ?: return
        params.flags = if (visible.value && focused && controlWindow?.touchable == true && !capturing.value)
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        control?.let { runCatching { manager?.updateViewLayout(it, params) } }
    }

    suspend fun beforeOperation() = withContext(Dispatchers.Main.immediate) {
        val window = controlWindow ?: throw PhoneFailure("overlay_unavailable")
        do {
            window.beforeOperation()
            // Let WindowManager commit the safe opacity and input region before injection.
            frame()
            frame()
        } while (!window.readyToOperate)
    }
    fun afterOperation() { controlWindow?.afterOperation() }
    suspend fun capture(block: suspend () -> android.graphics.Bitmap): android.graphics.Bitmap =
        withContext(Dispatchers.Main.immediate) {
            val window = controlWindow ?: throw PhoneFailure("overlay_unavailable")
            try {
                window.beforeCapture()
                capturing.value = true
                window.awaitCaptureHidden()
                frame()
                frame()
                if (!visible.value || removed) throw PhoneFailure("overlay_unavailable")
                block()
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    capturing.value = false
                    window.capture(false)
                }
            }
        }

    /**
     * Capture for the background screen while the effect draws on it. The border stays; the cursor and
     * the status strip are hidden, and a newer frame is awaited so the model never sees them.
     */
    suspend fun captureOnDisplay(
        screen: PhoneVirtualScreen,
        cursorShown: Boolean,
        block: suspend () -> android.graphics.Bitmap,
    ): android.graphics.Bitmap = withContext(Dispatchers.Main.immediate) {
        if (removed) throw PhoneFailure("overlay_unavailable")
        try {
            val before = screen.frames.value
            capturing.value = true
            if (cursorShown || stripOnDisplay.value) {
                frame()
                frame()
                screen.awaitFrameAfter(before, CURSOR_FRAME_TIMEOUT_MS)
            }
            block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { capturing.value = false }
        }
    }

    suspend fun hide() {
        visible.value = false
        controlWindow?.close()
        kotlinx.coroutines.delay(280)
        remove()
    }

    fun remove() {
        if (removed) return
        removed = true
        controlWindow?.close()
        controlScope.cancel()
        controlWindow = null
        removeEffect()
        control?.let { view ->
            runCatching { manager?.removeViewImmediate(view) }
            view.disposeComposition()
        }
        control = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }

    private companion object {
        const val CURSOR_FRAME_TIMEOUT_MS = 400L
    }

    private suspend fun frame() = suspendCancellableCoroutine<Unit> { continuation ->
        val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
        Choreographer.getInstance().postFrameCallback(callback)
        continuation.invokeOnCancellation { Choreographer.getInstance().removeFrameCallback(callback) }
    }
}
