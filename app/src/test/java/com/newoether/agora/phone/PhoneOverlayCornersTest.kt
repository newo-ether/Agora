package com.newoether.agora.phone

import android.app.Application
import android.graphics.Region
import android.view.RoundedCorner
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidPath
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneOverlayCornersTest {
    private val size = Size(200f, 400f)
    private fun region(corners: List<RoundedCorner?>?, inset: Float = 1f): Region = Region().apply {
        setPath(phoneBorderPath(size, corners, inset, 28f).asAndroidPath(), Region(0, 0, 200, 400))
    }

    @Test
    @Config(sdk = [26])
    fun olderAndroidUsesTheInsetFallbackWithoutPlatformCornerCalls() {
        val bounds = phoneBorderPath(size, null, 1f, 28f).getBounds()
        assertEquals(1f, bounds.left, 0.01f)
        assertEquals(1f, bounds.top, 0.01f)
        assertEquals(199f, bounds.right, 0.01f)
        assertEquals(399f, bounds.bottom, 0.01f)
    }

    @Test fun fourIndependentCentersAndRadiiDetermineTheVisibleMask() {
        val corners = listOf(
            RoundedCorner(0, 35, 45, 50), RoundedCorner(1, 20, 170, 30),
            RoundedCorner(2, 50, 140, 340), RoundedCorner(3, 15, 25, 375),
        )
        val mask = region(corners)
        assertFalse(mask.contains(12, 17))
        assertTrue(mask.contains(45, 17))
        assertTrue(mask.contains(187, 30))
        assertFalse(mask.contains(187, 12))
        assertTrue(mask.contains(140, 387))
        assertFalse(mask.contains(187, 387))
        assertTrue(mask.contains(12, 375))
        assertFalse(mask.contains(12, 387))
        assertEquals(11f, phoneBorderPath(size, corners, 1f, 28f).getBounds().left, 0.01f)
        assertEquals(389f, phoneBorderPath(size, corners, 1f, 28f).getBounds().bottom, 0.01f)
    }

    @Test fun reportedSquareCornersNeverRetainTheFallbackRadius() {
        val square = region(List(4) { null })
        val fallback = region(null)
        assertTrue(square.contains(2, 2))
        assertFalse(fallback.contains(2, 2))
        assertTrue(fallback.contains(30, 2))
    }

    @Test fun strokeBandsRemainInsideTheSamePlatformCorner() {
        val corners = listOf(
            RoundedCorner(0, 40, 40, 40), RoundedCorner(1, 40, 160, 40),
            RoundedCorner(2, 40, 160, 360), RoundedCorner(3, 40, 40, 360),
        )
        val outer = region(corners, 1f)
        val inner = region(corners, 9f)
        assertTrue(outer.contains(40, 2))
        assertFalse(inner.contains(40, 2))
        assertTrue(inner.contains(40, 10))
        assertTrue(outer.contains(15, 15))
        assertFalse(inner.contains(15, 15))
    }
}
