package com.newoether.agora.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.chat.message.AssistantMessageHorizontalInset
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.animateFloat

internal val StreamingTailAnchorHeight = 24.dp
internal val StreamingTailVisualLift = 56.dp
internal val GenerationActivityDotSize = 11.dp

/**
 * Bridge between the list-owned tail state machine and controls outside MessageList.
 *
 * The list remains the sole owner of scroll geometry. Callers can only ask it to seek the
 * LazyColumn's physical bottom and observe whether that action is currently useful; the visual
 * dot never participates in attachment or scrolling decisions.
 */
@Stable
internal class StreamingTailController {
    var isAutoFollowing by mutableStateOf(false)
        internal set

    var isAttached by mutableStateOf(false)
        internal set
}

@Composable
internal fun rememberStreamingTailController(ownerKey: Any? = Unit): StreamingTailController =
    remember(ownerKey) { StreamingTailController() }

internal data class StreamingTailAvailability(
    val enabled: Boolean,
    val paused: Boolean,
)

/**
 * A programmatic trip to the physical bottom is a temporary ownership handoff, not detachment.
 * Search/share/switch/Stop are real competitors and disable following instead.
 */
internal fun streamingTailAvailability(
    generationActive: Boolean,
    blocked: Boolean,
    programmaticHandoff: Boolean,
): StreamingTailAvailability = when {
    !generationActive || blocked -> StreamingTailAvailability(
        enabled = false,
        paused = false,
    )
    programmaticHandoff -> StreamingTailAvailability(
        enabled = false,
        paused = true,
    )
    else -> StreamingTailAvailability(
        enabled = true,
        paused = false,
    )
}

/**
 * Explicit auto-follow state.
 *
 * ARMED is generation-active but not following. ATTACHED keeps the physical page bottom
 * stationary as the page extent grows. A real user drag immediately latches DETACHED; once all
 * user/programmatic motion settles, proximity to the physical bottom is the sole attach authority.
 */
internal enum class StreamingTailFollowMode {
    INACTIVE,
    ARMED,
    ATTACHED,
    SETTLING,
    DETACHED,
}

internal sealed interface StreamingTailFollowEvent {
    data class GenerationChanged(
        val active: Boolean,
    ) : StreamingTailFollowEvent

    data object UserDragStarted : StreamingTailFollowEvent

    data class ViewportProximityChanged(
        val withinAttachThreshold: Boolean,
        val scrollInProgress: Boolean,
    ) : StreamingTailFollowEvent

    data object SettlingFinished : StreamingTailFollowEvent

}

internal fun reduceStreamingTailFollow(
    current: StreamingTailFollowMode,
    event: StreamingTailFollowEvent,
): StreamingTailFollowMode = when (event) {
    is StreamingTailFollowEvent.GenerationChanged -> when {
        !event.active && current in setOf(
            StreamingTailFollowMode.ATTACHED,
            StreamingTailFollowMode.SETTLING,
        ) -> StreamingTailFollowMode.SETTLING
        !event.active -> StreamingTailFollowMode.INACTIVE
        current == StreamingTailFollowMode.DETACHED -> StreamingTailFollowMode.DETACHED
        current == StreamingTailFollowMode.ATTACHED ||
            current == StreamingTailFollowMode.SETTLING -> current
        else -> StreamingTailFollowMode.ARMED
    }

    StreamingTailFollowEvent.UserDragStarted ->
        if (current == StreamingTailFollowMode.INACTIVE) {
            current
        } else {
            StreamingTailFollowMode.DETACHED
        }

    is StreamingTailFollowEvent.ViewportProximityChanged -> when {
        current == StreamingTailFollowMode.INACTIVE -> current
        event.scrollInProgress -> current
        current == StreamingTailFollowMode.ATTACHED ||
            current == StreamingTailFollowMode.SETTLING -> current
        event.withinAttachThreshold -> StreamingTailFollowMode.ATTACHED
        else -> current
    }

    StreamingTailFollowEvent.SettlingFinished ->
        if (current == StreamingTailFollowMode.SETTLING) {
            StreamingTailFollowMode.INACTIVE
        } else {
            current
        }
}

/**
 * Resolves generation/availability changes without conflating a temporary programmatic-scroll
 * hand-off with a real user detachment. A paused owner preserves the mode; a blocked owner
 * (Stop, search, switching, and similar competing UI) detaches.
 */
internal fun reduceStreamingTailGenerationAvailability(
    current: StreamingTailFollowMode,
    active: Boolean,
    autoFollowEnabled: Boolean,
    autoFollowPaused: Boolean,
): StreamingTailFollowMode = when {
    !active -> reduceStreamingTailFollow(
        current,
        StreamingTailFollowEvent.GenerationChanged(
            active = false,
        ),
    )

    autoFollowPaused -> current
    !autoFollowEnabled -> StreamingTailFollowMode.DETACHED
    else -> reduceStreamingTailFollow(
        current,
        StreamingTailFollowEvent.GenerationChanged(
            active = true,
        ),
    )
}

internal fun coalescedScrollStep(
    errorPx: Float,
    elapsedSeconds: Float,
    timeConstantSeconds: Float,
    maximumVelocityPxPerSecond: Float,
    minimumStepPx: Float,
): Float {
    if (errorPx == 0f || elapsedSeconds <= 0f) return 0f
    val fraction = 1f - kotlin.math.exp(
        -elapsedSeconds / timeConstantSeconds.coerceAtLeast(0.001f),
    )
    val maximumStep = maxOf(
        minimumStepPx,
        maximumVelocityPxPerSecond * elapsedSeconds,
    )
    return (errorPx * fraction).coerceIn(-maximumStep, maximumStep)
}

/**
 * A fixed-height answer-tail anchor. The direct dot owns draw-only alpha/scale inside this stable
 * layout slot, so its exit cannot clip against a disappearing visibility wrapper or move the turn.
 */
@Composable
internal fun StreamingTailIndicator(
    visible: Boolean,
    retainLayout: Boolean,
    modifier: Modifier = Modifier,
) {
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    val density = LocalDensity.current
    val visualLiftPx = with(density) { StreamingTailVisualLift.toPx() }
    val visibilityTransition = updateTransition(
        targetState = visible,
        label = "StreamingTailDotVisibility",
    )
    val opacity by visibilityTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 400, easing = FastOutSlowInEasing)
            } else {
                snap()
            }
        },
        label = "StreamingTailDotOpacity",
    ) { shown ->
        if (shown) 1f else 0f
    }
    val appearanceScale by visibilityTransition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = if (targetState) 400 else 320,
                easing = FastOutSlowInEasing,
            )
        },
        label = "StreamingTailDotAppearanceScale",
    ) { shown ->
        if (!allowSpatialTransitions || shown) 1f else 0.55f
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (visible || retainLayout) StreamingTailAnchorHeight else 0.dp)
            .padding(start = AssistantMessageHorizontalInset),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (visibilityTransition.currentState || visibilityTransition.targetState) {
            GenerationActivityDot(
                modifier = Modifier.graphicsLayer {
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    translationY = -visualLiftPx
                    alpha = opacity
                    scaleX = appearanceScale
                    scaleY = appearanceScale
                    clip = false
                },
            )
        }
    }
}

/** One breathing-scale sample used by every direct generation-dot source. */
@Composable
internal fun rememberGenerationActivityDotBreathingScale(): Float {
    val allowContinuousMotion = LocalAgoraMotionPolicy.current.allowContinuousMotion
    return if (allowContinuousMotion) {
        val breathing = rememberInfiniteTransition(label = "GenerationActivityBreathing")
        val animatedScale by breathing.animateFloat(
            initialValue = 0.55f,
            targetValue = 1.30f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "GenerationActivityBreathingScale",
        )
        animatedScale
    } else {
        1f
    }
}

/** The one breathing generation dot shared by the answer tail and pre-output activity gap. */
@Composable
internal fun GenerationActivityDot(
    modifier: Modifier = Modifier,
    breathingScale: Float = rememberGenerationActivityDotBreathingScale(),
) {
    Box(
        modifier = modifier
            .size(GenerationActivityDotSize)
            .graphicsLayer {
                scaleX = breathingScale
                scaleY = breathingScale
                clip = false
            }
            .background(
                color = MaterialTheme.colorScheme.onBackground,
                shape = CircleShape,
            ),
    )
}
