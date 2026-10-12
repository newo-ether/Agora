package com.newoether.agora.ui.settings

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ── Shared animation specs for settings page transitions ──
//     One source of truth — tune here, every sub-page follows.

private val SpringDamping = Spring.DampingRatioLowBouncy
private val SpringStiff = Spring.StiffnessLow
private const val SpringVisibilityThreshold = 0.001f
private const val FadeDuration = 300
private const val EnterSlideFraction = 0.75f  // fraction of screen width
private const val ExitSlideFraction = 0.75f
private const val ScaleFrom = 0.94f
private const val ScaleTo = 0.94f

/** Spring used for all enter & exit animations (slide + scale + fade). */
internal fun settingsSpring(): SpringSpec<Float> = spring(
    dampingRatio = SpringDamping,
    stiffness = SpringStiff,
    visibilityThreshold = SpringVisibilityThreshold
)

/**
 * Settings transition host that keeps the navigation target interactive and
 * prevents the outgoing page from receiving touches while its exit animation is
 * still visible.
 */
@Composable
internal fun <T> GuardedAnimatedContent(
    targetState: T,
    forward: Boolean,
    content: @Composable (T) -> Unit
) {
    val motion = LocalAgoraMotionPolicy.current
    val allowSpatialTransitions = motion.allowSpatialTransitions
    val pageSlots = remember {
        mutableStateListOf(
            SettingsTransitionSlot(
                id = 0,
                state = targetState,
                initialRole = SettingsTransitionRole.Target,
                enterSlideProgress = Animatable(1f),
                exitSlideProgress = Animatable(0f),
                alpha = Animatable(1f),
                scale = Animatable(1f)
            )
        )
    }
    var nextPageId by remember { mutableIntStateOf(1) }
    var activeForward by remember { mutableStateOf(forward) }

    LaunchedEffect(targetState, allowSpatialTransitions, motion.eInkMode) {
        val currentTarget = pageSlots.lastOrNull { it.role == SettingsTransitionRole.Target }
            ?: return@LaunchedEffect
        if (motion.eInkMode && targetState == currentTarget.state) {
            pageSlots.removeAll { it.role == SettingsTransitionRole.Outgoing }
            currentTarget.enterSlideProgress.snapTo(1f)
            currentTarget.exitSlideProgress.snapTo(0f)
            currentTarget.alpha.snapTo(1f)
            currentTarget.scale.snapTo(1f)
        }
        if (targetState != currentTarget.state) {
            pageSlots.removeAll { it.role == SettingsTransitionRole.Outgoing }
            currentTarget.role = SettingsTransitionRole.Outgoing
            activeForward = forward

            val newTarget = SettingsTransitionSlot(
                id = nextPageId,
                state = targetState,
                initialRole = SettingsTransitionRole.Target,
                enterSlideProgress = Animatable(if (allowSpatialTransitions) 0f else 1f),
                exitSlideProgress = Animatable(0f),
                alpha = Animatable(if (motion.eInkMode) 1f else 0f),
                scale = Animatable(if (allowSpatialTransitions) ScaleFrom else 1f)
            )
            nextPageId += 1
            pageSlots.add(newTarget)

            if (motion.eInkMode) {
                newTarget.enterSlideProgress.snapTo(1f)
                newTarget.scale.snapTo(1f)
                newTarget.alpha.snapTo(1f)
                currentTarget.alpha.snapTo(0f)
            } else if (allowSpatialTransitions) {
                listOf(
                    launch { newTarget.enterSlideProgress.animateTo(1f, animationSpec = settingsSpring()) },
                    launch { newTarget.alpha.animateTo(1f, animationSpec = tween(FadeDuration)) },
                    launch { newTarget.scale.animateTo(1f, animationSpec = settingsSpring()) },
                    launch { currentTarget.exitSlideProgress.animateTo(1f, animationSpec = settingsSpring()) },
                    launch { currentTarget.alpha.animateTo(0f, animationSpec = settingsSpring()) },
                    launch { currentTarget.scale.animateTo(ScaleTo, animationSpec = settingsSpring()) }
                ).joinAll()
            } else {
                newTarget.enterSlideProgress.snapTo(1f)
                newTarget.scale.snapTo(1f)
                currentTarget.exitSlideProgress.snapTo(0f)
                currentTarget.scale.snapTo(1f)
                listOf(
                    launch {
                        newTarget.alpha.animateTo(
                            1f,
                            animationSpec = tween(FadeDuration),
                        )
                    },
                    launch {
                        currentTarget.alpha.animateTo(
                            0f,
                            animationSpec = tween(FadeDuration),
                        )
                    },
                ).joinAll()
            }
            pageSlots.remove(currentTarget)
            newTarget.enterSlideProgress.snapTo(1f)
            newTarget.exitSlideProgress.snapTo(0f)
            newTarget.alpha.snapTo(1f)
            newTarget.scale.snapTo(1f)
        }
    }

    val isTransitioning = pageSlots.any { it.role == SettingsTransitionRole.Outgoing }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }

        pageSlots.forEach { slot ->
            val isOutgoing = slot.role == SettingsTransitionRole.Outgoing
            val offset = if (!allowSpatialTransitions) {
                0
            } else if (isOutgoing) {
                outgoingOffsetPx(activeForward, slot.exitSlideProgress.value, widthPx)
            } else {
                targetOffsetPx(activeForward, slot.enterSlideProgress.value, widthPx)
            }

            key(slot.id) {
                SettingsTransitionPage(
                    offsetX = offset,
                    alpha = if (motion.eInkMode) 1f else slot.alpha.value.coerceIn(0f, 1f),
                    scale = if (allowSpatialTransitions) slot.scale.value else 1f,
                    zIndex = if (isOutgoing) 0f else 1f,
                    consumeInput = isOutgoing
                ) {
                    content(slot.state)
                }
            }
        }

        if (isTransitioning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(0.5f)
                    .consumePointerInput()
            )
        }
    }
}

private enum class SettingsTransitionRole {
    Target,
    Outgoing
}

private class SettingsTransitionSlot<T>(
    val id: Int,
    val state: T,
    initialRole: SettingsTransitionRole,
    val enterSlideProgress: Animatable<Float, AnimationVector1D>,
    val exitSlideProgress: Animatable<Float, AnimationVector1D>,
    val alpha: Animatable<Float, AnimationVector1D>,
    val scale: Animatable<Float, AnimationVector1D>
) {
    var role by mutableStateOf(initialRole)
}

@Composable
private fun SettingsTransitionPage(
    offsetX: Int,
    alpha: Float,
    scale: Float,
    zIndex: Float,
    consumeInput: Boolean,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(zIndex)
            .offset { IntOffset(offsetX, 0) }
            .alpha(alpha)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (consumeInput) Modifier.consumePointerInput() else Modifier)
    ) {
        content()
    }
}

private fun targetOffsetPx(forward: Boolean, progress: Float, widthPx: Int): Int {
    val distance = widthPx * EnterSlideFraction
    val offset = distance * (1f - progress)
    return if (forward) offset.roundToInt() else -offset.roundToInt()
}

private fun outgoingOffsetPx(forward: Boolean, progress: Float, widthPx: Int): Int {
    val distance = widthPx * ExitSlideFraction
    val offset = distance * progress
    return if (forward) -offset.roundToInt() else offset.roundToInt()
}

private fun Modifier.consumePointerInput(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            }
        }
    }
