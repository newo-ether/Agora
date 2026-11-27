package com.newoether.agora.phone

import android.content.Context
import android.graphics.Rect
import android.view.Choreographer
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.doOnPreDraw
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

/** Lifecycle, saved state and view models for Compose content in windows added outside an activity. */
internal abstract class PreviewWindowOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    protected val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    protected fun ownedComposeView(context: Context, content: @Composable () -> Unit) = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@PreviewWindowOwner)
        setViewTreeSavedStateRegistryOwner(this@PreviewWindowOwner)
        setViewTreeViewModelStoreOwner(this@PreviewWindowOwner)
        setContent(content)
    }
}

/**
 * A window over the safe area that never moves and takes no touches. The window manager moves a
 * window's surface and shows the buffer drawn for its new frame at independent times, so a window
 * that changes its origin while visible draws one frame shifted by the change. While the floating
 * card is resized or morphs to or from the capsule, this window draws it instead and the floating
 * window stays hidden, changing its frame only while hidden. Both draw the same pixels at every
 * hand-over, so whichever of the two reaches the screen first, nothing moves.
 */
internal class PreviewStage(
    private val windows: WindowManager,
    private val view: ComposeView,
    area: Rect,
) {
    private val params = previewWindowParams().apply {
        flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        x = area.left
        y = area.top
        width = area.width()
        height = area.height()
    }
    private var added = false

    /** Adds the window; [onShown] runs once its first frame has had time to reach the screen. */
    fun show(onShown: () -> Unit): Boolean {
        try {
            windows.addView(view, params)
        } catch (_: Exception) {
            view.disposeComposition()
            return false
        }
        added = true
        view.doOnPreDraw { afterFrames(SETTLE_FRAMES) { if (added) onShown() } }
        return true
    }

    fun remove() {
        if (!added) return
        added = false
        runCatching { windows.removeViewImmediate(view) }
        view.disposeComposition()
    }

    companion object {
        /** Vsyncs between drawing a frame and relying on it being on screen. */
        const val SETTLE_FRAMES = 3
    }
}

/** Runs [block] on the main thread after [count] vsyncs. */
internal fun afterFrames(count: Int, block: () -> Unit) {
    if (count <= 0) return block()
    Choreographer.getInstance().postFrameCallback { afterFrames(count - 1, block) }
}
