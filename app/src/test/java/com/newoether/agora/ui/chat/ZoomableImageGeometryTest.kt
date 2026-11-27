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
    fun doubleTapUsesHorizontalMidlineWithTopAndBottomLetterboxing() {
        val container = Size(400f, 800f)
        val portraitImage = Size(400f, 500f)
        val center = Offset(200f, 400f)
        val pivot = doubleTapMediaPivot(Offset(180f, 350f), container, portraitImage)

        assertEquals(Offset(180f, 400f), pivot)
        val zoomIn = transformMediaOffset(Offset.Zero, 3f, pivot, center)
        val zoomOut = transformMediaOffset(zoomIn, 1f / 3f, pivot, center)
        assertEquals(40f, zoomIn.x, 0.0001f)
        assertEquals(0f, zoomIn.y, 0.0001f)
        assertEquals(0f, zoomOut.x, 0.0001f)
        assertEquals(0f, zoomOut.y, 0.0001f)
    }

    @Test
    fun doubleTapUsesVerticalMidlineWithLeftAndRightLetterboxing() {
        val container = Size(1200f, 800f)
        val landscapeImage = Size(600f, 500f)
        val center = Offset(600f, 400f)
        val pivot = doubleTapMediaPivot(Offset(350f, 300f), container, landscapeImage)

        assertEquals(Offset(600f, 300f), pivot)
        val zoomIn = transformMediaOffset(Offset.Zero, 3f, pivot, center)
        val zoomOut = transformMediaOffset(zoomIn, 1f / 3f, pivot, center)
        assertEquals(0f, zoomIn.x, 0.0001f)
        assertEquals(200f, zoomIn.y, 0.0001f)
        assertEquals(0f, zoomOut.x, 0.0001f)
        assertEquals(0f, zoomOut.y, 0.0001f)
    }

    @Test
    fun doubleTapUsesViewportCenterForExactAspectFit() {
        val center = Offset(200f, 400f)
        val pivot = doubleTapMediaPivot(
            tapOffset = Offset(120f, 240f),
            containerSize = Size(400f, 800f),
            imageSize = Size(200f, 400f),
        )

        assertEquals(center, pivot)
        val original = Offset(24f, -18f)
        val zoomIn = transformMediaOffset(original, 3f, pivot, center)
        val zoomOut = transformMediaOffset(zoomIn, 1f / 3f, pivot, center)
        assertEquals(Offset(72f, -54f), zoomIn)
        assertEquals(24f, zoomOut.x, 0.0001f)
        assertEquals(-18f, zoomOut.y, 0.0001f)
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
