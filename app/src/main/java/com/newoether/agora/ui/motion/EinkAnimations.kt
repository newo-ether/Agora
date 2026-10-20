@file:OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)

package com.newoether.agora.ui.motion

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat as composeTransitionFloat
import androidx.compose.animation.core.animateFloat as composeInfiniteFloat
import androidx.compose.animation.core.animateDp as composeTransitionDp
import androidx.compose.animation.animateColor as composeTransitionColor
import androidx.compose.animation.AnimatedContent as composeTransitionContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.Crossfade as composeTransitionCrossfade
import androidx.compose.animation.AnimatedVisibility as composeTransitionVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** Existing motion owner supplies the e-ink override; normal and Reduced Motion specs pass through. */
@Composable
fun <T> Crossfade(
    targetState: T,
    modifier: Modifier = Modifier,
    animationSpec: FiniteAnimationSpec<Float> = tween(),
    label: String = "Crossfade",
    content: @Composable (T) -> Unit,
) {
    androidx.compose.animation.Crossfade(targetState, modifier,
        if (LocalAgoraMotionPolicy.current.eInkMode) snap() else animationSpec, label, content)
}

@Composable
fun <T> Transition<T>.Crossfade(
    modifier: Modifier = Modifier,
    animationSpec: FiniteAnimationSpec<Float> = tween(),
    contentKey: (T) -> Any? = { it },
    content: @Composable (T) -> Unit,
) {
    with(this) {
        composeTransitionCrossfade(modifier,
            if (LocalAgoraMotionPolicy.current.eInkMode) snap() else animationSpec,
            contentKey, content)
    }
}

@Composable
fun <T> AnimatedContent(
    targetState: T,
    modifier: Modifier = Modifier,
    transitionSpec: AnimatedContentTransitionScope<T>.() -> ContentTransform = {
        (androidx.compose.animation.fadeIn(tween(220, delayMillis = 90)) +
            androidx.compose.animation.scaleIn(tween(220, delayMillis = 90), initialScale = 0.92f)) togetherWith
            androidx.compose.animation.fadeOut(tween(90))
    },
    contentAlignment: Alignment = Alignment.TopStart,
    label: String = "AnimatedContent",
    contentKey: (T) -> Any? = { it },
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    androidx.compose.animation.AnimatedContent(targetState, modifier,
        transitionSpec = {
            if (eInk) (EnterTransition.None togetherWith ExitTransition.None).using(null)
            else transitionSpec()
        }, contentAlignment = contentAlignment, label = label, contentKey = contentKey, content = content)
}

@Composable
fun AnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandIn(),
    exit: ExitTransition = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkOut(),
    label: String = "AnimatedVisibility",
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    androidx.compose.animation.AnimatedVisibility(visible, modifier,
        if (eInk) EnterTransition.None else enter,
        if (eInk) ExitTransition.None else exit, label, content)
}

@Composable
fun AnimatedVisibility(
    visibleState: MutableTransitionState<Boolean>,
    modifier: Modifier = Modifier,
    enter: EnterTransition = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandIn(),
    exit: ExitTransition = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkOut(),
    label: String = "AnimatedVisibility",
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    androidx.compose.animation.AnimatedVisibility(visibleState, modifier,
        if (eInk) EnterTransition.None else enter,
        if (eInk) ExitTransition.None else exit, label, content)
}

@Composable
fun <T> Transition<T>.AnimatedVisibility(
    visible: (T) -> Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandIn(),
    exit: ExitTransition = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkOut(),
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    composeTransitionVisibility(visible, modifier,
        if (eInk) EnterTransition.None else enter,
        if (eInk) ExitTransition.None else exit, content)
}

@Composable
fun animateFloatAsState(
    targetValue: Float,
    animationSpec: androidx.compose.animation.core.AnimationSpec<Float> = spring(),
    visibilityThreshold: Float = 0.01f,
    label: String = "FloatAnimation",
    finishedListener: ((Float) -> Unit)? = null,
): State<Float> = androidx.compose.animation.core.animateFloatAsState(targetValue,
    if (LocalAgoraMotionPolicy.current.eInkMode) snap() else animationSpec,
    visibilityThreshold, label, finishedListener)

@Composable
fun animateDpAsState(
    targetValue: Dp,
    animationSpec: androidx.compose.animation.core.AnimationSpec<Dp> = spring(),
    label: String = "DpAnimation",
    finishedListener: ((Dp) -> Unit)? = null,
): State<Dp> = androidx.compose.animation.core.animateDpAsState(targetValue,
    if (LocalAgoraMotionPolicy.current.eInkMode) snap() else animationSpec, label, finishedListener)

@Composable
fun animateColorAsState(
    targetValue: Color,
    animationSpec: androidx.compose.animation.core.AnimationSpec<Color> = spring(),
    label: String = "ColorAnimation",
    finishedListener: ((Color) -> Unit)? = null,
): State<Color> = androidx.compose.animation.animateColorAsState(targetValue,
    if (LocalAgoraMotionPolicy.current.eInkMode) snap() else animationSpec, label, finishedListener)

@Composable
fun <S> Transition<S>.animateFloat(
    transitionSpec: @Composable Transition.Segment<S>.() -> FiniteAnimationSpec<Float> = { spring() },
    label: String = "FloatAnimation",
    targetValueByState: @Composable (S) -> Float,
): State<Float> {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    return composeTransitionFloat(transitionSpec = { if (eInk) snap() else transitionSpec() },
        label = label, targetValueByState = targetValueByState)
}

@Composable
fun <S> Transition<S>.animateDp(
    transitionSpec: @Composable Transition.Segment<S>.() -> FiniteAnimationSpec<Dp> = { spring() },
    label: String = "DpAnimation",
    targetValueByState: @Composable (S) -> Dp,
): State<Dp> {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    return composeTransitionDp(transitionSpec = { if (eInk) snap() else transitionSpec() },
        label = label, targetValueByState = targetValueByState)
}

@Composable
fun <S> Transition<S>.animateColor(
    transitionSpec: @Composable Transition.Segment<S>.() -> FiniteAnimationSpec<Color> = { spring() },
    label: String = "ColorAnimation",
    targetValueByState: @Composable (S) -> Color,
): State<Color> {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    return composeTransitionColor(transitionSpec = { if (eInk) snap() else transitionSpec() },
        label = label, targetValueByState = targetValueByState)
}

@Composable
fun androidx.compose.animation.core.InfiniteTransition.animateFloat(
    initialValue: Float,
    targetValue: Float,
    animationSpec: androidx.compose.animation.core.InfiniteRepeatableSpec<Float>,
    label: String = "FloatAnimation",
): State<Float> = if (LocalAgoraMotionPolicy.current.eInkMode) rememberUpdatedState(targetValue)
    else composeInfiniteFloat(initialValue, targetValue, animationSpec, label)

@Composable
fun androidx.compose.foundation.layout.RowScope.AnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandHorizontally(),
    exit: ExitTransition = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkHorizontally(),
    label: String = "AnimatedVisibility",
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    composeTransitionVisibility(visible, modifier, if (eInk) EnterTransition.None else enter,
        if (eInk) ExitTransition.None else exit, label, content)
}

@Composable
fun androidx.compose.foundation.layout.ColumnScope.AnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
    exit: ExitTransition = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
    label: String = "AnimatedVisibility",
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    composeTransitionVisibility(visible, modifier, if (eInk) EnterTransition.None else enter,
        if (eInk) ExitTransition.None else exit, label, content)
}

@Composable
fun <T> Transition<T>.AnimatedContent(
    modifier: Modifier = Modifier,
    transitionSpec: AnimatedContentTransitionScope<T>.() -> ContentTransform = {
        (androidx.compose.animation.fadeIn(tween(220, delayMillis = 90)) +
            androidx.compose.animation.scaleIn(tween(220, delayMillis = 90), initialScale = 0.92f)) togetherWith
            androidx.compose.animation.fadeOut(tween(90))
    },
    contentAlignment: Alignment = Alignment.TopStart,
    contentKey: (T) -> Any? = { it },
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    composeTransitionContent(modifier, transitionSpec = {
        if (eInk) (EnterTransition.None togetherWith ExitTransition.None).using(null)
        else transitionSpec()
    }, contentAlignment = contentAlignment, contentKey = contentKey, content = content)
}
