@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.newoether.agora.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Velocity
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import kotlin.math.roundToInt
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.Surface

/** Only deliberate user drags change chrome; streaming/IME/programmatic scrolling cannot hide it. */
internal class CollapsibleChatHeader(private val hideDistance: Float, private val revealDistance: Float) {
    private var einkCollapsed by mutableStateOf(false)
    var material: TopAppBarScrollBehavior? = null
        internal set
    var revealHeightPx = 0
        internal set
    val collapsed: Boolean get() = material?.state?.let {
        it.heightOffsetLimit < 0f && it.collapsedFraction >= 0.999f
    } ?: einkCollapsed
    fun contentInset(expandedPx: Float): Float = material?.state?.let {
        (expandedPx + it.heightOffset).coerceAtLeast(revealHeightPx.toFloat())
    } ?: expandedPx
    private var travel = 0f
    fun show() {
        einkCollapsed = false
        travel = 0f
        material?.state?.let { it.heightOffset = 0f; it.contentOffset = 0f }
    }
    fun drag(delta: Float, enabled: Boolean) {
        if (!enabled) { show(); return }
        if (!delta.isFinite() || delta == 0f) return
        if (travel * delta < 0) travel = 0f
        travel += delta
        if (!collapsed && travel <= -hideDistance) { einkCollapsed = true; travel = 0f }
        else if (collapsed && travel >= revealDistance) show()
    }
    var connection: NestedScrollConnection = object : NestedScrollConnection {}
        internal set
}

@Composable
internal fun rememberCollapsibleChatHeader(owner: String?, enabled: Boolean): CollapsibleChatHeader {
    val density = LocalDensity.current
    val motion = LocalAgoraMotionPolicy.current
    val currentEnabled by rememberUpdatedState(enabled)
    // Material owns normal-screen drag consumption, fling decay and settling. The custom
    // capsules remain unchanged; their measured height drives Material's offset limit.
    val topAppBarState = key(owner) { rememberTopAppBarState() }
    val canScroll = remember { { currentEnabled } }
    val standard = when {
        motion.eInkMode -> null
        motion.reduceMotion -> TopAppBarDefaults.enterAlwaysScrollBehavior(
            state = topAppBarState, canScroll = canScroll,
            snapAnimationSpec = null, flingAnimationSpec = null,
        )
        else -> TopAppBarDefaults.enterAlwaysScrollBehavior(state = topAppBarState, canScroll = canScroll)
    }
    val state = remember(owner, density, standard) {
        CollapsibleChatHeader(with(density) { 48.dp.toPx() }, with(density) { 12.dp.toPx() })
    }
    state.material = standard
    state.connection = remember(state, standard) {
        object : NestedScrollConnection {
            private var userScroll = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || !currentEnabled) return Offset.Zero
                userScroll = true
                if (standard != null) return standard.nestedScrollConnection.onPreScroll(available, source)
                state.drag(available.y, true)
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (currentEnabled && source == NestedScrollSource.UserInput && standard != null)
                    standard.nestedScrollConnection.onPostScroll(consumed, available, source) else Offset.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = try {
                if (currentEnabled && userScroll && standard != null)
                    standard.nestedScrollConnection.onPostFling(consumed, available) else Velocity.Zero
            } finally { userScroll = false }
        }
    }
    LaunchedEffect(enabled) { if (!enabled) state.show() }
    return state
}

@Composable
internal fun CollapsibleChatHeader(
    state: CollapsibleChatHeader,
    reading: Boolean,
    onHeightChanged: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val revealHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 48.dp
    state.revealHeightPx = with(density) { revealHeight.roundToPx() }
    Box(Modifier.fillMaxWidth().clipToBounds().onSizeChanged { onHeightChanged(it.height) }) {
        val material = state.material
        val presentation = if (material == null) Modifier.readingChrome(state.collapsed && !reading)
        else Modifier.then(if (state.collapsed) Modifier.clearAndSetSemantics {} else Modifier)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                material.state.heightOffsetLimit = -(placeable.height - state.revealHeightPx).coerceAtLeast(0).toFloat()
                val offset = material.state.heightOffset.roundToInt()
                layout(placeable.width, (placeable.height + offset).coerceAtLeast(0)) {
                    placeable.placeRelative(0, offset)
                }
            }
        Box(presentation) { content() }
        if (state.collapsed && !reading) {
            Surface(modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
                IconButton(onClick = state::show, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.chat_show_toolbar))
                }
            }
        }
    }
}
