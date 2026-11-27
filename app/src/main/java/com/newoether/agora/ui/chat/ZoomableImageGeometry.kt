package com.newoether.agora.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/** Size produced by ContentScale.Fit inside the complete media viewport. */
internal fun fittedMediaContentSize(containerSize: Size, imageSize: Size): Size {
    if (containerSize.width <= 0f || containerSize.height <= 0f ||
        imageSize.width <= 0f || imageSize.height <= 0f
    ) return Size.Zero
    val fitScale = minOf(
        containerSize.width / imageSize.width,
        containerSize.height / imageSize.height,
    )
    return Size(imageSize.width * fitScale, imageSize.height * fitScale)
}

/**
 * Single pivot for a complete double-tap gesture, projected from the tap onto the midline between
 * the fitted content edges that do not touch the viewport at 1x. Exact aspect fits use the center.
 */
internal fun doubleTapMediaPivot(
    tapOffset: Offset,
    containerSize: Size,
    imageSize: Size,
): Offset {
    val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
    if (containerSize.width <= 0f || containerSize.height <= 0f ||
        imageSize.width <= 0f || imageSize.height <= 0f
    ) return center
    val imageAspect = imageSize.width / imageSize.height
    val containerAspect = containerSize.width / containerSize.height
    return when {
        imageAspect > containerAspect -> Offset(tapOffset.x, center.y)
        imageAspect < containerAspect -> Offset(center.x, tapOffset.y)
        else -> center
    }
}

/** Maximum center displacement on each axis. A letterboxed axis has a zero bound. */
internal fun mediaPanBounds(containerSize: Size, imageSize: Size, scale: Float): Offset {
    val contentSize = fittedMediaContentSize(containerSize, imageSize)
    if (contentSize == Size.Zero) return Offset.Zero
    return Offset(
        x = (contentSize.width * scale - containerSize.width).coerceAtLeast(0f) / 2f,
        y = (contentSize.height * scale - containerSize.height).coerceAtLeast(0f) / 2f,
    )
}

/**
 * Applies one gesture frame around [pivot], then adds its centroid translation. Keeping [pan]
 * independent of the scale ratio is what lets a pinch frame drag on either available axis.
 */
internal fun transformMediaOffset(
    offset: Offset,
    scaleRatio: Float,
    pivot: Offset,
    viewportCenter: Offset,
    pan: Offset = Offset.Zero,
): Offset = Offset(
    x = offset.x * scaleRatio + (pivot.x - viewportCenter.x) * (1f - scaleRatio) + pan.x,
    y = offset.y * scaleRatio + (pivot.y - viewportCenter.y) * (1f - scaleRatio) + pan.y,
)

internal fun constrainMediaOffset(offset: Offset, bounds: Offset): Offset = Offset(
    x = offset.x.coerceIn(-bounds.x, bounds.x),
    y = offset.y.coerceIn(-bounds.y, bounds.y),
)
