package com.newoether.agora.phone

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.platform.ComposeView
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
    private var effect: ComposeView? = null
    private var control: ComposeView? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controlWindow: PhoneControlWindow? = null
    private val touchBounds = mutableMapOf<String, Rect>()
    private var removed = false
    val capturing = androidx.compose.runtime.mutableStateOf(false)
    val visible = androidx.compose.runtime.mutableStateOf(true)

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun show(mode: String) {
        val windowContext = if (mode == "accessibility") PhoneAccessibilityService.instance
            ?: throw PhoneFailure("permission_lost") else context
        val windows = windowContext.getSystemService(WindowManager::class.java)
        val localizedContext = windowContext.withAppLocale(runtime.settings.appLanguage.value)
        manager = windows
        val type = if (mode == "accessibility") WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        fun view() = ComposeView(localizedContext).apply {
            setViewTreeLifecycleOwner(this@PhoneOverlay)
            setViewTreeSavedStateRegistryOwner(this@PhoneOverlay)
            setViewTreeViewModelStoreOwner(this@PhoneOverlay)
        }
        val effectView = view().also { effect = it }
        effectView.setContent { PhoneOverlayTheme(runtime.settings) { PhoneEffect(runtime, this) } }
        val effectParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        val windowAlpha = if (Build.VERSION.SDK_INT >= 31 && mode != "accessibility") {
            phoneOverlayWindowAlpha(context.getSystemService(android.hardware.input.InputManager::class.java)
                .maximumObscuringOpacityForTouch)
        } else 1f
        // InputDispatcher combines both windows' alpha even outside their touchable regions.
        effectParams.alpha = windowAlpha
        if (Build.VERSION.SDK_INT >= 30) effectParams.setFitInsetsTypes(0)
        if (Build.VERSION.SDK_INT >= 28) effectParams.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        val controls = view().also { control = it }
        controls.setContent { PhoneOverlayTheme(runtime.settings) {
            PhoneControls(runtime, epoch, ::focusable, ::bounds)
        } }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        params.alpha = windowAlpha
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
            windows.addView(effectView, effectParams)
            windows.addView(controls, params)
            controlWindow = PhoneControlWindow(controls, params, windows)
            controlScope.launch {
                runtime.askUser.requests.collect { requests ->
                    val owner = runtime.lease.held.value?.takeIf { it.epoch == epoch }?.owner
                    controlWindow?.showForUser(owner != null && requests.any {
                        it.conversationId == owner.conversationId && it.runId == owner.runId
                    })
                }
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
        val params = controlParams ?: return
        params.flags = if (focused && controlWindow?.touchable == true && !capturing.value)
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        control?.let { runCatching { manager?.updateViewLayout(it, params) } }
    }

    suspend fun beforeOperation() = withContext(Dispatchers.Main.immediate) {
        (controlWindow ?: throw PhoneFailure("overlay_unavailable")).beforeOperation()
    }
    fun afterOperation() { controlWindow?.afterOperation() }
    suspend fun capture(block: suspend () -> android.graphics.Bitmap): android.graphics.Bitmap =
        withContext(Dispatchers.Main.immediate) {
            (controlWindow ?: throw PhoneFailure("overlay_unavailable")).beforeOperation()
            capturing.value = true
            controlWindow?.capture(true)
            control?.visibility = View.INVISIBLE
            try {
                frame()
                frame()
                block()
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    capturing.value = false
                    if (visible.value) control?.visibility = View.VISIBLE
                    controlWindow?.capture(false)
                }
            }
        }

    suspend fun hide() {
        visible.value = false
        control?.visibility = View.GONE
        kotlinx.coroutines.delay(260)
        remove()
    }

    fun remove() {
        if (removed) return
        removed = true
        controlWindow?.close()
        controlScope.cancel()
        controlWindow = null
        listOfNotNull(effect, control).forEach { view ->
            runCatching { manager?.removeViewImmediate(view) }
            view.disposeComposition()
        }
        effect = null
        control = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }

    private suspend fun frame() = suspendCancellableCoroutine<Unit> { continuation ->
        val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
        Choreographer.getInstance().postFrameCallback(callback)
        continuation.invokeOnCancellation { Choreographer.getInstance().removeFrameCallback(callback) }
    }
}
