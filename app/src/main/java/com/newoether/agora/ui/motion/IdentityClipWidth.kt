package com.newoether.agora.ui.motion

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.Dp

/** The approved duration of an identity clip; see application-ui.md sections 24 and 28. */
internal const val IDENTITY_CLIP_DURATION_MILLIS = 400

/**
 * The visible right edge of a start-anchored clip whose content changed identity.
 *
 * Content is always laid out at its final width; only the clip returned here moves. A change of
 * [identity] moves the edge from where it is now to [targetWidth] over one [durationMillis]
 * deadline with [FastOutSlowInEasing]. While that motion runs, a new [targetWidth] (for example a
 * token subtitle arriving) rebases the remaining motion from the current edge toward the latest
 * target within the same deadline, so the first terminal frame is exactly the latest target and no
 * correction follows. A newer [identity] restarts from the current edge. With no motion running, a
 * target change applies at once, as does every change when [allowSpatialTransitions] is false.
 * The first composition presents the target without an entrance motion.
 */
@Composable
internal fun rememberIdentityClipWidth(
    identity: Any?,
    targetWidth: Dp,
    allowSpatialTransitions: Boolean,
    durationMillis: Int = IDENTITY_CLIP_DURATION_MILLIS,
): Dp {
    val latestTargetWidth by rememberUpdatedState(targetWidth)
    var clipWidth by remember { mutableStateOf(targetWidth) }
    var observedIdentity by remember { mutableStateOf(identity) }
    var motionRunning by remember { mutableStateOf(false) }
    val transitionPending = observedIdentity != identity
    LaunchedEffect(identity, allowSpatialTransitions) {
        val identityChanged = observedIdentity != identity
        if (!allowSpatialTransitions || !identityChanged) {
            clipWidth = latestTargetWidth
            observedIdentity = identity
            return@LaunchedEffect
        }
        // Record the admitted identity before motion so an interrupted return is a new change.
        observedIdentity = identity
        motionRunning = true
        try {
            val clipStartNanos = withFrameNanos { it }
            val clipDeadlineNanos = clipStartNanos + durationMillis * 1_000_000L
            var segmentStartNanos = clipStartNanos
            var segmentStartWidth = clipWidth
            var segmentTargetWidth = latestTargetWidth
            while (true) {
                val frameNanos = withFrameNanos { it }
                val latestTarget = latestTargetWidth
                if (frameNanos >= clipDeadlineNanos) {
                    clipWidth = latestTarget
                    break
                }
                if (latestTarget != segmentTargetWidth) {
                    segmentStartNanos = frameNanos
                    segmentStartWidth = clipWidth
                    segmentTargetWidth = latestTarget
                }
                val segmentDurationNanos = (clipDeadlineNanos - segmentStartNanos).coerceAtLeast(1L)
                val segmentFraction = (
                    (frameNanos - segmentStartNanos).toFloat() / segmentDurationNanos.toFloat()
                    ).coerceIn(0f, 1f)
                val easedFraction = FastOutSlowInEasing.transform(segmentFraction)
                clipWidth = segmentStartWidth + (segmentTargetWidth - segmentStartWidth) * easedFraction
            }
        } finally {
            motionRunning = false
        }
    }
    LaunchedEffect(targetWidth, transitionPending, motionRunning, allowSpatialTransitions) {
        if (!transitionPending && !motionRunning) {
            clipWidth = targetWidth
        }
    }
    return clipWidth
}
