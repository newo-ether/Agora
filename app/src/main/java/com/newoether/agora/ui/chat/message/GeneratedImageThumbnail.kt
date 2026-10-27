package com.newoether.agora.ui.chat.message

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.ui.chat.MEDIA_STATE_CROSSFADE_MILLIS
import com.newoether.agora.ui.chat.MediaLoadPresentation
import com.newoether.agora.ui.chat.toMediaLoadPresentation
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy

@Composable
internal fun GeneratedImageThumbnail(
    segment: MessageSegment,
    messageId: String,
    detailIndex: Int,
    isStreaming: Boolean,
    segmentAppearanceRegistry: SegmentAppearanceRegistry,
    onMediaClick: (List<String>, Int) -> Unit,
) {
    if (!segment.isImageGenerationSegment()) return
    val presentation = ToolPresentationResolver.resolve(segment)
    val appearanceKey = generatedImageAppearanceKey(messageId, detailIndex)
    val animateAppearance = rememberSegmentAppearance(
        registry = segmentAppearanceRegistry,
        animationKey = appearanceKey,
        isStreaming = isStreaming,
    )
    val appearanceModifier = generationLifecycleAppearanceModifier(
        animationKey = appearanceKey,
        animate = animateAppearance,
        durationMillis = SEGMENT_ENTER_DURATION_MS,
        initialScale = SEGMENT_ENTER_INITIAL_SCALE,
    )
    val images = remember(segment.toolImages) {
        segment.toolImages.filter { it.path.isNotBlank() || it.unavailable }
    }
    val paths = remember(images) { projectToolImageOccurrences(images).urls }
    val thumbnail = images.firstOrNull()
    val image = thumbnail?.takeUnless { it.unavailable }
    val thumbnailSize = 300.dp
    val thumbnailSizePx = with(LocalDensity.current) {
        thumbnailSize.roundToPx().coerceAtLeast(1)
    }
    val context = LocalContext.current
    val imageRequest = remember(image?.path, thumbnailSizePx, context) {
        image?.path?.let { path ->
            ImageRequest.Builder(context)
                .data(path)
                .size(thumbnailSizePx, thumbnailSizePx)
                .build()
        }
    }
    val imagePainter = rememberAsyncImagePainter(model = imageRequest)
    val targetState = when {
        thumbnail?.unavailable == true -> MediaLoadPresentation.FAILED
        presentation.isActive -> MediaLoadPresentation.LOADING
        presentation.state != ToolPresentationState.COMPLETED ->
            MediaLoadPresentation.FAILED
        image == null -> MediaLoadPresentation.FAILED
        else -> imagePainter.state.toMediaLoadPresentation()
    }
    var presentedState by remember(appearanceKey, thumbnail?.unavailable) {
        mutableStateOf(
            if (thumbnail?.unavailable == true) MediaLoadPresentation.FAILED
            else MediaLoadPresentation.LOADING,
        )
    }
    LaunchedEffect(targetState) {
        presentedState = targetState
    }
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopStart,
    ) {
        Box(
            modifier = Modifier
                .size(thumbnailSize)
                .then(appearanceModifier)
                .clip(shape)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (imageRequest != null) {
                Image(
                    painter = imagePainter,
                    contentDescription = stringResource(R.string.tool_view_image),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            enabled = presentedState == MediaLoadPresentation.LOADED &&
                                paths.isNotEmpty(),
                            onClick = { onMediaClick(paths, 0) },
                        ),
                )
            }
            Crossfade(
                targetState = presentedState,
                animationSpec = tween(
                    durationMillis = MEDIA_STATE_CROSSFADE_MILLIS,
                    easing = LinearEasing,
                ),
                label = "generatedImageContent:$appearanceKey",
                modifier = Modifier.fillMaxSize(),
            ) { state ->
                when (state) {
                    MediaLoadPresentation.LOADING -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            ),
                    ) {
                        GeneratedImagePendingDots(
                            animationKey = appearanceKey,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    MediaLoadPresentation.LOADED -> Spacer(Modifier.fillMaxSize())
                    MediaLoadPresentation.FAILED -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.BrokenImage,
                            contentDescription = stringResource(
                                R.string.attachment_copy_failed_image,
                            ),
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneratedImagePendingDots(
    animationKey: String,
    modifier: Modifier = Modifier,
) {
    val allowContinuousMotion = LocalAgoraMotionPolicy.current.allowContinuousMotion
    val density = LocalDensity.current
    val progress = remember(animationKey) { Animatable(0f) }
    val random = remember(animationKey) { kotlin.random.Random(animationKey.hashCode()) }
    var anchorStart by remember(animationKey) { mutableStateOf(Offset(0.5f, 0.5f)) }
    var anchorTarget by remember(animationKey) { mutableStateOf(anchorStart) }
    LaunchedEffect(animationKey, allowContinuousMotion) {
        if (!allowContinuousMotion) {
            progress.snapTo(0f)
            anchorStart = Offset(0.5f, 0.5f)
            anchorTarget = anchorStart
            return@LaunchedEffect
        }
        while (true) {
            progress.snapTo(0f)
            anchorTarget = Offset(
                x = random.nextFloat(),
                y = random.nextFloat(),
            )
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 1_300,
                    easing = FastOutSlowInEasing,
                ),
            )
            anchorStart = anchorTarget
        }
    }
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    val dotFieldInsetPx = with(density) { 16.dp.toPx() }
    val anchorInsetPx = with(density) { 32.dp.toPx() }
    val spacingPx = with(density) { 16.dp.toPx() }
    val minRadiusPx = with(density) { 0.7.dp.toPx() }
    val maxRadiusPx = with(density) { 3.9.dp.toPx() }
    val influenceDistancePx = with(density) { 150.dp.toPx() }

    Canvas(modifier = modifier) {
        val dotLeft = dotFieldInsetPx
        val dotTop = dotFieldInsetPx
        val dotRight = (size.width - dotFieldInsetPx).coerceAtLeast(dotLeft)
        val dotBottom = (size.height - dotFieldInsetPx).coerceAtLeast(dotTop)
        val dotCenterLeft = (dotLeft + maxRadiusPx).coerceAtMost(dotRight)
        val dotCenterTop = (dotTop + maxRadiusPx).coerceAtMost(dotBottom)
        val dotCenterRight = (dotRight - maxRadiusPx).coerceAtLeast(dotCenterLeft)
        val dotCenterBottom = (dotBottom - maxRadiusPx).coerceAtLeast(dotCenterTop)
        val anchorLeft = anchorInsetPx
        val anchorTop = anchorInsetPx
        val anchorRight = (size.width - anchorInsetPx).coerceAtLeast(anchorLeft)
        val anchorBottom = (size.height - anchorInsetPx).coerceAtLeast(anchorTop)
        val animatedAnchor = Offset(
            x = anchorStart.x + (anchorTarget.x - anchorStart.x) * progress.value,
            y = anchorStart.y + (anchorTarget.y - anchorStart.y) * progress.value,
        )
        val anchorPx = Offset(
            x = anchorLeft + (anchorRight - anchorLeft) * animatedAnchor.x,
            y = anchorTop + (anchorBottom - anchorTop) * animatedAnchor.y,
        )
        val dotFieldWidth = dotCenterRight - dotCenterLeft
        val dotFieldHeight = dotCenterBottom - dotCenterTop
        val columnCount = ((dotFieldWidth / spacingPx).toInt() + 1).coerceAtLeast(1)
        val rowCount = ((dotFieldHeight / spacingPx).toInt() + 1).coerceAtLeast(1)
        val columnStep = if (columnCount > 1) dotFieldWidth / (columnCount - 1) else 0f
        val rowStep = if (rowCount > 1) dotFieldHeight / (rowCount - 1) else 0f
        repeat(rowCount) { row ->
            val y = dotCenterTop + row * rowStep
            repeat(columnCount) { column ->
                val x = dotCenterLeft + column * columnStep
                val distance = kotlin.math.hypot(x - anchorPx.x, y - anchorPx.y)
                val distanceScale = (1f - distance / influenceDistancePx).coerceIn(0f, 1f)
                val influence = distanceScale * distanceScale
                drawCircle(
                    color = dotColor,
                    radius = minRadiusPx + (maxRadiusPx - minRadiusPx) * influence,
                    center = Offset(x, y),
                )
            }
        }
    }
}
