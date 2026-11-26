package com.newoether.agora.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal const val SMOOTH_SHEET_HIDDEN_FRACTION = 0f
internal const val SMOOTH_SHEET_PARTIAL_FRACTION = 0.45f
internal const val SMOOTH_SHEET_EXPANDED_FRACTION = 0.94f
internal const val SMOOTH_SHEET_SPRING_DAMPING_RATIO = 0.9f
internal const val SMOOTH_SHEET_SPRING_STIFFNESS = 350f

internal enum class SmoothBottomSheetValue(
    val fraction: Float,
) {
    Hidden(SMOOTH_SHEET_HIDDEN_FRACTION),
    Partial(SMOOTH_SHEET_PARTIAL_FRACTION),
    Expanded(SMOOTH_SHEET_EXPANDED_FRACTION),
}

internal fun smoothBottomSheetSnapTarget(
    position: Float,
    velocityDirection: Float,
): SmoothBottomSheetValue {
    val goingUp = velocityDirection >= 0f
    return when {
        position > 0.5f && goingUp -> SmoothBottomSheetValue.Expanded
        position > 0.5f -> SmoothBottomSheetValue.Partial
        goingUp -> SmoothBottomSheetValue.Partial
        else -> SmoothBottomSheetValue.Hidden
    }
}

@Stable
internal class SmoothBottomSheetState internal constructor() {
    internal var value by mutableStateOf(SmoothBottomSheetValue.Partial)
    internal var rawFraction by mutableFloatStateOf(SMOOTH_SHEET_HIDDEN_FRACTION)
    internal val visualFraction = Animatable(SMOOTH_SHEET_HIDDEN_FRACTION)
    internal var snapJob by mutableStateOf<Job?>(null)
    internal var dismissing by mutableStateOf(false)
    internal var dismissRequestVersion by mutableIntStateOf(0)
        private set

    internal fun requestDismiss() {
        dismissRequestVersion += 1
    }
}

@Composable
internal fun rememberSmoothBottomSheetState(): SmoothBottomSheetState =
    remember { SmoothBottomSheetState() }

/**
 * Agora's interruptible spring sheet shell.
 *
 * The caller owns only navigation, title, and content. This shell owns the dialog, dimming,
 * established anchors, drag interruption, nested-scroll handoff, and reduced-motion behavior.
 */
@Composable
internal fun SmoothBottomSheet(
    state: SmoothBottomSheetState = rememberSmoothBottomSheetState(),
    onDismissRequest: () -> Unit,
    onBackRequest: () -> Boolean = { false },
    contentAtTop: () -> Boolean,
    header: @Composable ColumnScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val screenHeightPx =
        LocalWindowInfo.current.containerSize.height.toFloat().coerceAtLeast(1f)
    val coroutineScope = rememberCoroutineScope()
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val currentOnBackRequest by rememberUpdatedState(onBackRequest)
    val currentContentAtTop by rememberUpdatedState(contentAtTop)
    val snapSpring = remember {
        spring<Float>(
            dampingRatio = SMOOTH_SHEET_SPRING_DAMPING_RATIO,
            stiffness = SMOOTH_SHEET_SPRING_STIFFNESS,
            visibilityThreshold = 0.001f,
        )
    }

    fun animateTo(target: SmoothBottomSheetValue) {
        state.snapJob?.cancel()
        state.snapJob = coroutineScope.launch {
            if (motionPolicy.allowSpatialTransitions) {
                state.visualFraction.animateTo(target.fraction, snapSpring)
            } else {
                state.visualFraction.snapTo(target.fraction)
            }
            state.rawFraction = state.visualFraction.value
            state.value = target
            if (target == SmoothBottomSheetValue.Hidden) currentOnDismissRequest()
        }
    }

    fun dismiss() {
        if (state.dismissing) return
        state.dismissing = true
        animateTo(SmoothBottomSheetValue.Hidden)
    }

    fun grabSheet() {
        if (state.dismissing) return
        if (state.snapJob?.isActive == true) {
            state.snapJob?.cancel()
            state.rawFraction = state.visualFraction.value
        }
    }

    LaunchedEffect(state) {
        animateTo(SmoothBottomSheetValue.Partial)
        state.snapJob?.join()
        state.rawFraction = SMOOTH_SHEET_PARTIAL_FRACTION
    }

    LaunchedEffect(state.dismissRequestVersion) {
        if (state.dismissRequestVersion > 0) dismiss()
    }

    LaunchedEffect(state.rawFraction) {
        if (state.dismissing || state.snapJob?.isActive == true) return@LaunchedEffect
        val position = state.rawFraction
        delay(80)
        if (
            state.dismissing ||
            position != state.rawFraction ||
            state.snapJob?.isActive == true
        ) {
            return@LaunchedEffect
        }
        val target = smoothBottomSheetSnapTarget(position, 0f)
        if (abs(target.fraction - position) > 0.01f) animateTo(target)
    }

    val sheetScrollConnection = remember(
        state,
        screenHeightPx,
        motionPolicy.allowSpatialTransitions,
    ) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!state.dismissing && state.value != SmoothBottomSheetValue.Expanded) {
                    grabSheet()
                    val delta = -available.y / screenHeightPx
                    state.rawFraction = (state.rawFraction + delta)
                        .coerceIn(SMOOTH_SHEET_HIDDEN_FRACTION, SMOOTH_SHEET_EXPANDED_FRACTION)
                    coroutineScope.launch {
                        state.visualFraction.snapTo(state.rawFraction)
                    }
                    if (
                        state.rawFraction >= SMOOTH_SHEET_EXPANDED_FRACTION &&
                        available.y < 0f
                    ) {
                        state.value = SmoothBottomSheetValue.Expanded
                    }
                    return available.copy(x = 0f)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (
                    !state.dismissing &&
                    state.value == SmoothBottomSheetValue.Expanded &&
                    available.y > 0f &&
                    currentContentAtTop() &&
                    source == NestedScrollSource.UserInput
                ) {
                    state.value = SmoothBottomSheetValue.Partial
                    val delta = -available.y / screenHeightPx
                    state.rawFraction = (SMOOTH_SHEET_EXPANDED_FRACTION + delta)
                        .coerceIn(SMOOTH_SHEET_HIDDEN_FRACTION, SMOOTH_SHEET_EXPANDED_FRACTION)
                    coroutineScope.launch {
                        state.visualFraction.snapTo(state.rawFraction)
                    }
                    return available.copy(x = 0f)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (
                    state.value != SmoothBottomSheetValue.Expanded &&
                    available.y != 0f
                ) {
                    val velocityDirection = if (available.y < 0f) 1f else -1f
                    animateTo(
                        smoothBottomSheetSnapTarget(
                            position = state.rawFraction,
                            velocityDirection = velocityDirection,
                        ),
                    )
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    val dialogWindowRef = remember { mutableStateOf<android.view.Window?>(null) }
    LaunchedEffect(dialogWindowRef.value) {
        val window = dialogWindowRef.value ?: return@LaunchedEffect
        snapshotFlow { state.visualFraction.value }
            .map { fraction -> (0.32f * fraction).coerceIn(0f, 1f) }
            .distinctUntilChanged()
            .collect { dimAmount ->
                val attributes = window.attributes
                if (attributes.dimAmount != dimAmount) {
                    attributes.dimAmount = dimAmount
                    window.attributes = attributes
                }
            }
    }

    Dialog(
        onDismissRequest = {
            if (!state.dismissing && !currentOnBackRequest()) dismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        DialogWindowEdgeToEdge()
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindowRef.value = dialogWindow }

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(state) {
                        detectTapGestures(
                            onTap = {
                                if (state.visualFraction.value > 0.02f) dismiss()
                            },
                        )
                    },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .widthIn(max = BottomSheetMaxWidth)
                    .layout { measurable, constraints ->
                        val height = (screenHeightPx * state.visualFraction.value)
                            .roundToInt()
                            .coerceAtLeast(0)
                        val placeable = measurable.measure(
                            constraints.copy(minHeight = height, maxHeight = height),
                        )
                        layout(placeable.width, height) {
                            placeable.placeRelative(0, 0)
                        }
                    },
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = BOTTOM_SHEET_SHAPE,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(
                                    state,
                                    screenHeightPx,
                                    motionPolicy.allowSpatialTransitions,
                                ) {
                                    var velocityEma = 0f
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            if (state.dismissing) {
                                                return@detectVerticalDragGestures
                                            }
                                            velocityEma = 0f
                                            grabSheet()
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            if (state.dismissing) {
                                                return@detectVerticalDragGestures
                                            }
                                            change.consume()
                                            velocityEma =
                                                velocityEma * 0.5f +
                                                    (-dragAmount).coerceIn(-1f, 1f) * 0.5f
                                            state.rawFraction =
                                                (state.rawFraction - dragAmount / screenHeightPx)
                                                    .coerceIn(
                                                        SMOOTH_SHEET_HIDDEN_FRACTION,
                                                        SMOOTH_SHEET_EXPANDED_FRACTION,
                                                    )
                                            coroutineScope.launch {
                                                state.visualFraction.snapTo(state.rawFraction)
                                            }
                                            if (
                                                state.rawFraction >=
                                                    SMOOTH_SHEET_EXPANDED_FRACTION &&
                                                dragAmount < 0f
                                            ) {
                                                state.value =
                                                    SmoothBottomSheetValue.Expanded
                                            }
                                        },
                                        onDragEnd = {
                                            if (state.dismissing) {
                                                return@detectVerticalDragGestures
                                            }
                                            animateTo(
                                                smoothBottomSheetSnapTarget(
                                                    position = state.rawFraction,
                                                    velocityDirection = velocityEma,
                                                ),
                                            )
                                        },
                                    )
                                },
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(36.dp)
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.3f,
                                            ),
                                        ),
                                )
                            }
                            header()
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .nestedScroll(sheetScrollConnection),
                            content = content,
                        )
                    }
                }
            }
        }
    }
}
