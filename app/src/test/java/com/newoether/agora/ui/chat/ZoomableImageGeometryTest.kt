package com.newoether.agora.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomableImageGeometryTest {
    @Test
    fun fitUsesCompleteViewportWithoutSystemBarAllowance() {
        val fitted = fittedMediaContentSize(
            containerSize = Size(1080f, 2400f),
            imageSize = Size(1080f, 1920f),
        )

        assertEquals(Size(1080f, 1920f), fitted)
        assertEquals(Offset.Zero, mediaPanBounds(Size(1080f, 2400f), Size(1080f, 1920f), 1f))
    }

    @Test
    fun doubleTapTransformUsesViewportCenterForBothDirections() {
        val center = Offset(200f, 400f)
        val original = Offset(24f, -18f)

        val zoomIn = transformMediaOffset(original, 3f, center, center)
        val zoomOut = transformMediaOffset(original, 1f / 3f, center, center)

        assertEquals(Offset(72f, -54f), zoomIn)
        assertEquals(Offset(8f, -6f), zoomOut)
    }

    @Test
    fun pinchFrameKeepsHorizontalPanWithTopAndBottomLetterboxing() {
        val container = Size(400f, 800f)
        val image = Size(400f, 200f)
        val center = Offset(container.width / 2f, container.height / 2f)
        val transformed = transformMediaOffset(
            offset = Offset(10f, 0f),
            scaleRatio = 1.1f,
            pivot = Offset(180f, 350f),
            viewportCenter = center,
            pan = Offset(36f, 20f),
        )
        val constrained = constrainMediaOffset(
            transformed,
            mediaPanBounds(container, image, scale = 2.2f),
        )

        assertEquals(49f, transformed.x, 0.0001f)
        assertEquals(25f, transformed.y, 0.0001f)
        assertEquals(49f, constrained.x, 0.0001f)
        assertEquals(0f, constrained.y, 0.0001f)
    }

    @Test
    fun pinchFrameKeepsVerticalPanWithLeftAndRightLetterboxing() {
        val container = Size(600f, 800f)
        val image = Size(400f, 800f)
        val center = Offset(container.width / 2f, container.height / 2f)
        val transformed = transformMediaOffset(
            offset = Offset(0f, 10f),
            scaleRatio = 1.1f,
            pivot = Offset(350f, 300f),
            viewportCenter = center,
            pan = Offset(20f, 36f),
        )
        val constrained = constrainMediaOffset(
            transformed,
            mediaPanBounds(container, image, scale = 1.32f),
        )

        assertEquals(15f, transformed.x, 0.0001f)
        assertEquals(57f, transformed.y, 0.0001f)
        assertEquals(0f, constrained.x, 0.0001f)
        assertEquals(57f, constrained.y, 0.0001f)
    }

    @Test
    fun unscaledFrameIsPurePan() {
        val result = transformMediaOffset(
            offset = Offset(12f, -8f),
            scaleRatio = 1f,
            pivot = Offset(30f, 70f),
            viewportCenter = Offset(200f, 400f),
            pan = Offset(9f, 14f),
        )

        assertEquals(Offset(21f, 6f), result)
    }
}
