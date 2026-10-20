package com.newoether.agora.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class EinkPageControlsTest {
    @Test
    fun viewportStepExcludesChromeKeyboardAndThreeLines() {
        assertEquals(580, einkPageStep(900, 140, 120, 20))
        assertEquals(280, einkPageStep(900, 140, 420, 20))
        assertEquals(544, einkPageStep(900, 140, 120, 32))
    }

    @Test
    fun largeTextStillAllowsProgressInAShortViewport() {
        assertEquals(50, einkPageStep(200, 50, 50, 32))
        assertEquals(50, einkPageStep(200, 50, 50, Int.MAX_VALUE))
    }

    @Test
    fun shortOrUnmeasuredViewportsCannotReverseTheRequestedDirection() {
        assertEquals(0, einkPageStep(0, 140, 120, 20))
        assertEquals(0, einkPageStep(200, 140, 120, 20))
        assertEquals(900, einkPageStep(900, -1, -1, -1))
    }
}
