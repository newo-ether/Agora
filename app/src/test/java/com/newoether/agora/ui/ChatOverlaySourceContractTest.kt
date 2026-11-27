package com.newoether.agora.ui

import com.newoether.agora.util.bottomOverlayFadeStops
import com.newoether.agora.util.topEdgeFadeEnd
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

    @Test
    fun `top edge fade covers the floating top bar band`() {
        // A 100 dp bar band on a 1000 px layer is fully faded in across the top 10% of the list.
        assertEquals(0.1f, topEdgeFadeEnd(canvasHeightPx = 1_000f, topFadeHeightPx = 100f), 0.0001f)
        // No band keeps the content fully opaque at the top edge.
        assertEquals(0f, topEdgeFadeEnd(canvasHeightPx = 1_000f, topFadeHeightPx = 0f), 0.0001f)
        // A band taller than the layer clamps to the whole layer instead of inverting.
        assertEquals(1f, topEdgeFadeEnd(canvasHeightPx = 100f, topFadeHeightPx = 900f), 0.0001f)
    }
}
