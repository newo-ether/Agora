@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.newoether.agora.ui.chat

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val DRAWER_MAX_WIDTH = 360.dp
internal val CHAT_APP_WIDTH_THRESHOLD = 600.dp
internal const val DRAWER_TWEEN_DURATION_MILLIS = 300

internal fun drawerWidthFor(screenWidth: Dp): Dp = minOf(screenWidth, DRAWER_MAX_WIDTH)

internal fun usesSideBySideDrawer(screenWidth: Dp): Boolean =
    screenWidth > DRAWER_MAX_WIDTH + CHAT_APP_WIDTH_THRESHOLD

/**
 * The drawer's horizontal offset while it rests on [open] in a [isRtl] layout.
 *
 * The drawer box is anchored with [Alignment.CenterStart], so the sign of the resting offset is
 * decided by the layout direction: positive pushes it off the right edge in RTL, negative off the
 * left edge in LTR. Anchors and the pre-measurement fallback must agree, otherwise a frame composed
 * before the layout direction lands on the state parks the panel at the opposite edge.
 */
internal fun drawerRestingOffset(drawerWidthPx: Float, isRtl: Boolean, open: Boolean): Float = when {
    open -> 0f
    isRtl -> drawerWidthPx
    else -> -drawerWidthPx
}

internal fun resolveDrawerSettleTarget(
    velocity: Float,
    revealedPosition: Float,
    drawerWidth: Float,
    isRtl: Boolean = false,
): DrawerValue = when {
    // In RTL, dragging toward open is the opposite horizontal direction, so velocity sign is flipped.
    if (isRtl) velocity < 0f else velocity > 0f -> DrawerValue.Open
    if (isRtl) velocity > 0f else velocity < 0f -> DrawerValue.Closed
    revealedPosition >= drawerWidth / 2f -> DrawerValue.Open
    else -> DrawerValue.Closed
}

@Stable
internal class ChatDrawerState internal constructor(
    private val anchoredState: AnchoredDraggableState<DrawerValue>,
    initialIsRtl: Boolean,
) {
    private var drawerWidthPx by mutableFloatStateOf(0f)
    private var drawerEnabled by mutableStateOf(true)
    private var isRtl by mutableStateOf(initialIsRtl)

    var sideBySide by mutableStateOf(false)
        private set

    val offsetPx: Float
        get() = anchoredState.offset.takeUnless(Float::isNaN)
            ?: drawerRestingOffset(drawerWidthPx, isRtl, anchoredState.currentValue == DrawerValue.Open)

    val progress: Float
        get() = if (drawerWidthPx > 0f) {
            if (isRtl) {
                (1f - (offsetPx / drawerWidthPx)).coerceIn(0f, 1f)
            } else {
                ((offsetPx + drawerWidthPx) / drawerWidthPx).coerceIn(0f, 1f)
            }
        } else {
            0f
        }

    val isVisible: Boolean
        get() = progress > 0f

    val shouldHandleBack: Boolean
        get() = !sideBySide && isVisible

    val isAnimationRunning: Boolean
        get() = anchoredState.isAnimationRunning

    fun updateLayout(drawerWidthPx: Float, sideBySide: Boolean, drawerEnabled: Boolean, isRtl: Boolean) {
        if (drawerWidthPx <= 0f) return
        this.drawerWidthPx = drawerWidthPx
        this.sideBySide = sideBySide
        this.drawerEnabled = drawerEnabled
        this.isRtl = isRtl
        anchoredState.updateAnchors(
            DraggableAnchors {
                DrawerValue.Closed at drawerRestingOffset(drawerWidthPx, isRtl, open = false)
                DrawerValue.Open at 0f
            },
            anchoredState.targetValue,
        )
    }

    fun dispatchRawDelta(delta: Float) {
        if (drawerEnabled && !sideBySide) anchoredState.dispatchRawDelta(delta)
    }

    /**
     * Claim the drawer at user-input priority so a touch during a settle animation stops it.
     *
     * The running animation writes the offset from its own coroutine, so raw drag deltas alone are
     * overwritten every frame and the drawer cannot be caught mid-flight. Claiming the drag cancels
     * that animation and leaves the offset exactly where the finger arrived; the drag then steers
     * from there and the usual release settles it.
     */
    suspend fun takeOverAnimation() {
        if (!drawerEnabled || sideBySide) return
        anchoredState.anchoredDrag(MutatePriority.UserInput) { }
    }

    suspend fun settle(velocity: Float, motionPolicy: AgoraMotionPolicy) {
        if (!drawerEnabled || sideBySide || drawerWidthPx <= 0f) return
        val revealedPosition = if (isRtl) (drawerWidthPx - offsetPx) else (offsetPx + drawerWidthPx)
        animateTo(
            resolveDrawerSettleTarget(
                velocity = velocity,
                revealedPosition = revealedPosition,
                drawerWidth = drawerWidthPx,
                isRtl = isRtl,
            ),
            motionPolicy,
        )
    }

    suspend fun toggle(motionPolicy: AgoraMotionPolicy) {
        if (!drawerEnabled) return
        val target = if (anchoredState.targetValue == DrawerValue.Open) {
            DrawerValue.Closed
        } else {
            DrawerValue.Open
        }
        animateTo(target, motionPolicy)
    }

    suspend fun closeFromContent(motionPolicy: AgoraMotionPolicy) {
        if (!sideBySide) animateTo(DrawerValue.Closed, motionPolicy)
    }

    suspend fun closeFromBack(motionPolicy: AgoraMotionPolicy) {
        if (shouldHandleBack) animateTo(DrawerValue.Closed, motionPolicy)
    }

    suspend fun forceClosed(motionPolicy: AgoraMotionPolicy) {
        animateTo(DrawerValue.Closed, motionPolicy)
    }
    suspend fun openImmediately() {
        if (drawerEnabled) anchoredState.snapTo(DrawerValue.Open)
    }

    private suspend fun animateTo(target: DrawerValue, motionPolicy: AgoraMotionPolicy) {
        if (target == DrawerValue.Open && !drawerEnabled) return
        if (motionPolicy.allowSpatialTransitions) {
            anchoredState.animateTo(
                target,
                tween(
                    durationMillis = DRAWER_TWEEN_DURATION_MILLIS,
                    easing = LinearOutSlowInEasing,
                ),
            )
        } else {
            anchoredState.snapTo(target)
        }
    }
}

@Composable
internal fun rememberChatDrawerState(): ChatDrawerState {
    // Seed the direction from the composition instead of defaulting to LTR: the state lives across
    // the first frame, and a LTR default would place an RTL drawer at the left edge for that frame.
    val initialIsRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return remember {
        ChatDrawerState(AnchoredDraggableState(DrawerValue.Closed), initialIsRtl)
    }
}

@Composable
internal fun ChatDrawerHost(
    state: ChatDrawerState,
    drawerEnabled: Boolean,
    motionPolicy: AgoraMotionPolicy,
    onDrawerProgress: (Float) -> Unit,
    drawerContent: @Composable (drawerWidth: Dp, closeFromContent: suspend () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val scrimInteractionSource = remember { MutableInteractionSource() }
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val screenWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val drawerWidth = drawerWidthFor(screenWidth)
    val drawerWidthPx = with(density) { drawerWidth.toPx() }
    val sideBySide = usesSideBySideDrawer(screenWidth)

    SideEffect {
        state.updateLayout(
            drawerWidthPx = drawerWidthPx,
            sideBySide = sideBySide,
            drawerEnabled = drawerEnabled,
            isRtl = isRtl,
        )
    }
    LaunchedEffect(drawerEnabled, motionPolicy.allowSpatialTransitions) {
        if (!drawerEnabled) state.forceClosed(motionPolicy)
    }

    val progress = state.progress
    LaunchedEffect(progress) { onDrawerProgress(progress) }
    val dragState = rememberDraggableState(state::dispatchRawDelta)
    val dragModifier = if (drawerEnabled && !sideBySide) {
        Modifier.draggable(
            state = dragState,
            orientation = Orientation.Horizontal,
            enabled = true,
            startDragImmediately = state.isAnimationRunning,
            onDragStarted = { state.takeOverAnimation() },
            onDragStopped = { velocity -> state.settle(velocity, motionPolicy) },
        )
    } else {
        Modifier
    }

    Box(modifier = Modifier.fillMaxSize().then(dragModifier)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (sideBySide) {
                        if (isRtl) Modifier.padding(end = drawerWidth * progress)
                        else Modifier.padding(start = drawerWidth * progress)
                    } else {
                        Modifier
                    }
                ),
        ) {
            content()
        }

        if (!sideBySide && progress > 0f) {
            val scrimColor = DrawerDefaults.scrimColor
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .zIndex(1f)
                    .background(scrimColor.copy(alpha = scrimColor.alpha * progress))
                    .clickable(
                        interactionSource = scrimInteractionSource,
                        indication = null,
                        onClick = {
                            scope.launch { state.closeFromContent(motionPolicy) }
                        },
                    ),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(drawerWidth)
                // absoluteOffset, not offset: Modifier.offset mirrors its x component in RTL, which
                // would flip the RTL resting offset (+width) back over the content edge and leave a
                // sliver of the panel at the opposite side of the screen.
                .absoluteOffset { IntOffset(state.offsetPx.roundToInt(), 0) }
                .zIndex(2f),
        ) {
            drawerContent(drawerWidth) { state.closeFromContent(motionPolicy) }
        }
    }
}
