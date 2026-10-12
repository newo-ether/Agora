package com.newoether.agora.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Local presentation only: keep the same message list and composer owners while reading. */
internal class EinkReadingMode(private val listState: LazyListState) {
    var active by mutableStateOf(false)
        private set
    fun toggle() {
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        active = !active
    }
}

/** Measure hidden chrome so remembered drafts survive, but do not place or expose its controls. */
internal fun Modifier.readingChrome(hidden: Boolean): Modifier = if (!hidden) this else
    clearAndSetSemantics {}.layout { measurable, constraints ->
        measurable.measure(constraints)
        layout(0, 0) {}
    }

@Composable
internal fun rememberEinkReadingMode(enabled: Boolean, owner: String?, listState: LazyListState): EinkReadingMode {
    val state = remember(enabled, owner, listState) { EinkReadingMode(listState) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    LaunchedEffect(state.active) { if (state.active) { focus.clearFocus(); keyboard?.hide() } }
    BackHandler(state.active) { state.toggle() }
    val active = state.active
    DisposableEffect(active, view) {
        val activity = view.context.let { context ->
            var current = context
            while (current is android.content.ContextWrapper && current !is android.app.Activity) current = current.baseContext
            current as? android.app.Activity
        }
        val controller = activity?.let { WindowInsetsControllerCompat(it.window, view) }
        val previous = ViewCompat.getRootWindowInsets(view)
        val types = WindowInsetsCompat.Type.systemBars()
        if (active) controller?.hide(types)
        onDispose {
            if (active) {
                if (previous?.isVisible(WindowInsetsCompat.Type.statusBars()) != false) controller?.show(WindowInsetsCompat.Type.statusBars())
                if (previous?.isVisible(WindowInsetsCompat.Type.navigationBars()) != false) controller?.show(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
    return state
}
