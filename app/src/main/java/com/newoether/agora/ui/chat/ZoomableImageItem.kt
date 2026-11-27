package com.newoether.agora.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
internal fun ZoomableImageItem(
    url: String,
    onTap: () -> Unit,
    onScaleChanged: (Float) -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    consumeConditionally: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val motionPolicy = LocalAgoraMotionPolicy.current
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current

    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offsetX by remember(url) { mutableFloatStateOf(0f) }
    var offsetY by remember(url) { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(Size.Zero) }
    var imageSize by remember(url) { mutableStateOf(Size.Zero) }
    var loadState by remember(url) { mutableStateOf(MediaLoadPresentation.LOADING) }
    var presentedState by remember(url) { mutableStateOf(MediaLoadPresentation.LOADING) }
    var animationJob by remember(url) { mutableStateOf<Job?>(null) }
    var lastCentroid by remember(url) { mutableStateOf(Offset.Unspecified) }

    fun rubberBandValue(fullDelta: Float, dimension: Float): Float {
        if (dimension <= 0f) return 0f
        val c = 0.4f
        return (fullDelta * c * dimension) / (dimension + c * fullDelta)
    }

    fun visualScale(raw: Float): Float = when {
        raw < 1f -> 1f - rubberBandValue(1f - raw, 1f)
        raw > 10f -> 10f + rubberBandValue(raw - 10f, 5f)
        else -> raw
    }

    fun getMaxOffsets(currentRaw: Float): Pair<Float, Float> {
        val bounds = mediaPanBounds(containerSize, imageSize, visualScale(currentRaw))
        return bounds.x to bounds.y
    }

    LaunchedEffect(Unit) {
        snapshotFlow { visualScale(scale) }.collect { onScaleChanged(it) }
    }
    LaunchedEffect(loadState) {
        presentedState = loadState
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(url, motionPolicy.allowSpatialTransitions) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = onLongPress?.let { cb -> { _ -> cb() } },
                    onDoubleTap = { tapOffset ->
                        animationJob?.cancel()
                        animationJob = scope.launch {
                            val s0 = scale
                            val centerX = containerSize.width / 2f
                            val centerY = containerSize.height / 2f
                            val isZoomIn = s0 <= 1.05f
                            val targetScale = if (isZoomIn) 3f else 1f
                            var prevS = s0
                            var prevOX = offsetX
                            var prevOY = offsetY
                            val center = Offset(centerX, centerY)
                            val pivot = doubleTapMediaPivot(tapOffset, containerSize, imageSize)
                            if (!motionPolicy.allowSpatialTransitions) {
                                val r = visualScale(targetScale) / visualScale(s0)
                                val (maxX, maxY) = getMaxOffsets(targetScale)
                                val transformed = constrainMediaOffset(
                                    transformMediaOffset(Offset(offsetX, offsetY), r, pivot, center),
                                    Offset(maxX, maxY),
                                )
                                offsetX = transformed.x
                                offsetY = transformed.y
                                scale = targetScale
                                return@launch
                            }
                            AnimationState(s0).animateTo(targetScale, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, 0.001f)) {
                                scale = value
                                val r = visualScale(value) / visualScale(prevS)
                                val (maxX, maxY) = getMaxOffsets(value)
                                val transformed = constrainMediaOffset(
                                    transformMediaOffset(Offset(prevOX, prevOY), r, pivot, center),
                                    Offset(maxX, maxY),
                                )
                                offsetX = transformed.x
                                offsetY = transformed.y
                                prevS = value
                                prevOX = offsetX
                                prevOY = offsetY
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        coil.compose.AsyncImage(
            model = url,
            contentDescription = null,
            onLoading = { loadState = MediaLoadPresentation.LOADING },
            onSuccess = { state ->
                imageSize = state.painter.intrinsicSize
                loadState = MediaLoadPresentation.LOADED
            },
            onError = { loadState = MediaLoadPresentation.FAILED },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(url, motionPolicy.allowSpatialTransitions) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        animationJob?.cancel()
                        val panTracker = PanVelocityTracker()
                        panTracker.add(down.uptimeMillis, Offset.Zero)
                        var pastTouchSlop = false
                        val touchSlop = viewConfiguration.touchSlop
                        lastCentroid = Offset.Unspecified
                        var rawScale = scale
                        var logicalOffsetX = offsetX
                        var logicalOffsetY = offsetY
                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (!pastTouchSlop) {
                                val panAmount = panChange.getDistance()
                                if (zoomChange != 1f || panAmount > touchSlop) pastTouchSlop = true
                            }
                            if (pastTouchSlop) {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                if (zoomChange != 1f && centroid != Offset.Unspecified) lastCentroid = centroid
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    val oldVis = visualScale(scale)
                                    rawScale = (rawScale * zoomChange).coerceIn(0.1f, 30f)
                                    val newVis = visualScale(rawScale)
                                    val r = if (oldVis != 0f) newVis / oldVis else 1f
                                    val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                    val transformed = constrainMediaOffset(
                                        transformMediaOffset(
                                            offset = Offset(logicalOffsetX, logicalOffsetY),
                                            scaleRatio = r,
                                            pivot = centroid,
                                            viewportCenter = center,
                                            pan = panChange,
                                        ),
                                        mediaPanBounds(containerSize, imageSize, newVis),
                                    )
                                    scale = rawScale
                                    offsetX = transformed.x
                                    offsetY = transformed.y
                                    logicalOffsetX = offsetX
                                    logicalOffsetY = offsetY
                                    if (consumeConditionally) {
                                        if (newVis > 1.05f || zoomChange != 1f || abs(panChange.y) > abs(panChange.x)) {
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                    } else {
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                }
                            }
                            val eventTime = event.changes.first().uptimeMillis
                            if (event.changes.size == 1) panTracker.add(eventTime, panChange)
                            else panTracker.reset(eventTime)
                        } while (event.changes.any { it.pressed })
                        val rawVelocity = panTracker.velocity()
                        val maxV = with(density) { 2500.dp.toPx() }
                        val velocity = Velocity(
                            x = rawVelocity.x.coerceIn(-maxV, maxV),
                            y = rawVelocity.y.coerceIn(-maxV, maxV)
                        )
                        animationJob = scope.launch {
                            if (!motionPolicy.allowSpatialTransitions) {
                                val targetScale = scale.coerceIn(1f, 10f)
                                val (maxX, maxY) = getMaxOffsets(targetScale)
                                scale = targetScale
                                offsetX = offsetX.coerceIn(-maxX, maxX)
                                offsetY = offsetY.coerceIn(-maxY, maxY)
                                return@launch
                            }
                            if (scale < 0.95f || scale > 10.05f) {
                                val sS = scale; val sX = offsetX; val sY = offsetY
                                val targetS = scale.coerceIn(1f, 10f)
                                val (targetMaxX, targetMaxY) = getMaxOffsets(targetS)
                                val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                val pivot = center
                                val targetR = if (sS != 0f) targetS / sS else 1f
                                val finalPivotX = sX * targetR + (pivot.x - center.x) * (1f - targetR)
                                val finalPivotY = sY * targetR + (pivot.y - center.y) * (1f - targetR)
                                val targetX = finalPivotX.coerceIn(-targetMaxX, targetMaxX)
                                val targetY = finalPivotY.coerceIn(-targetMaxY, targetMaxY)
                                AnimationState(0f).animateTo(1f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessLow)) {
                                    val currentS = sS + (targetS - sS) * value
                                    scale = currentS
                                    val r = if (sS != 0f) visualScale(currentS) / visualScale(sS) else 1f
                                    val pivotOX = sX * r + (pivot.x - center.x) * (1f - r)
                                    val pivotOY = sY * r + (pivot.y - center.y) * (1f - r)
                                    offsetX = pivotOX + (targetX - finalPivotX) * value
                                    offsetY = pivotOY + (targetY - finalPivotY) * value
                                }
                            } else {
                                launch {
                                    val (maxX, _) = getMaxOffsets(scale)
                                    if (offsetX > maxX || offsetX < -maxX) {
                                        offsetX = offsetX.coerceIn(-maxX, maxX)
                                    } else if (velocity.x != 0f) {
                                        val decay = splineBasedDecay<Float>(density)
                                        AnimationState(offsetX, velocity.x).animateDecay(decay) {
                                            val (curMaxX, _) = getMaxOffsets(scale)
                                            if (value > curMaxX || value < -curMaxX) {
                                                offsetX = value.coerceIn(-curMaxX, curMaxX)
                                                cancelAnimation()
                                            } else offsetX = value
                                        }
                                    }
                                }
                                launch {
                                    val (_, maxY) = getMaxOffsets(scale)
                                    if (offsetY > maxY || offsetY < -maxY) {
                                        offsetY = offsetY.coerceIn(-maxY, maxY)
                                    } else if (velocity.y != 0f) {
                                        val decay = splineBasedDecay<Float>(density)
                                        AnimationState(offsetY, velocity.y).animateDecay(decay) {
                                            val (_, curMaxY) = getMaxOffsets(scale)
                                            if (value > curMaxY || value < -curMaxY) {
                                                offsetY = value.coerceIn(-curMaxY, curMaxY)
                                                cancelAnimation()
                                            } else offsetY = value
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .graphicsLayer(
                    scaleX = visualScale(scale),
                    scaleY = visualScale(scale),
                    translationX = offsetX,
                    translationY = offsetY
                ),
            contentScale = ContentScale.Fit
        )
        Crossfade(
            targetState = presentedState,
            animationSpec = tween(MEDIA_STATE_CROSSFADE_MILLIS),
            label = "zoomableImageState",
            modifier = Modifier.fillMaxSize(),
        ) { state ->
            when (state) {
                MediaLoadPresentation.LOADING -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = MEDIA_LOADING_INDICATOR_STROKE_WIDTH,
                    )
                }
                MediaLoadPresentation.LOADED -> Spacer(Modifier.fillMaxSize())
                MediaLoadPresentation.FAILED -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BrokenImage,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
        }
    }
}
