package com.newoether.agora.phone

import android.content.Context
import android.graphics.Rect
import android.view.Choreographer
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.unit.IntOffset
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
    /**
     * The window's screen position, which the content places by. Compose lays out during the
     * first measure, before the window has a frame, so a position read from the view then is 0.
     */
    private val origin: MutableState<IntOffset>,
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
        origin.value = IntOffset(params.x, params.y)
        try {
            windows.addView(view, params)
        } catch (_: Exception) {
            view.disposeComposition()
            return false
        }
        added = true
        view.doOnPreDraw { confirm(onShown) }
        return true
    }

    /** A system may still place the window elsewhere; then the content moves before anything relies on it. */
    private fun confirm(onShown: () -> Unit) {
        if (!added) return
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        val actual = IntOffset(at[0], at[1])
        if (actual != origin.value) {
            origin.value = actual
            afterFrames(1) { confirm(onShown) }
        } else afterFrames(SETTLE_FRAMES) { if (added) onShown() }
    }

    /**
     * Waits up to [HAND_BACK_FRAMES] for [ready], the floating window laid out in its new frame,
     * lets that frame reach the screen, runs [reveal], and once the revealed window is on screen
     * runs [done], which removes this one. Nothing runs after [remove].
     */
    fun handBack(ready: () -> Boolean, reveal: () -> Unit, done: () -> Unit, left: Int = HAND_BACK_FRAMES) {
        afterFrames(1) {
            if (!added) return@afterFrames
            if (!ready() && left > 0) return@afterFrames handBack(ready, reveal, done, left - 1)
            afterFrames(SETTLE_FRAMES) {
                if (!added) return@afterFrames
                reveal()
                afterFrames(SETTLE_FRAMES) { if (added) done() }
            }
        }
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
        /** Longest wait for the floating window's new layout before it shows anyway. */
        const val HAND_BACK_FRAMES = 30
    }
}

/** Runs [block] on the main thread after [count] vsyncs. */
internal fun afterFrames(count: Int, block: () -> Unit) {
    if (count <= 0) return block()
    Choreographer.getInstance().postFrameCallback { afterFrames(count - 1, block) }
}
