package com.newoether.agora.ui

import com.newoether.agora.util.bottomOverlayFadeStops
import org.junit.Assert.assertEquals
import org.junit.Test

internal class ChatOverlaySourceContractTest {
    @Test
    fun `bottom overlay fade geometry tracks the live composer cover and clamps safely`() {
        val regular = bottomOverlayFadeStops(
            canvasHeightPx = 1_000f,
            fadeHeightPx = 40f,
            bottomOverlayHeightPx = 200f,
        )
        assertEquals(0.8f, regular.first, 0.0001f)
        assertEquals(0.84f, regular.second, 0.0001f)

        val oversizedOverlay = bottomOverlayFadeStops(
            canvasHeightPx = 1_000f,
            fadeHeightPx = 40f,
            bottomOverlayHeightPx = 1_500f,
        )
        assertEquals(0f, oversizedOverlay.first, 0.0001f)
        assertEquals(0.04f, oversizedOverlay.second, 0.0001f)
    }
}
