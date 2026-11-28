package com.newoether.agora.ui.chat

import com.newoether.agora.ui.chat.bottombar.contextUsageExceedsCompactThreshold
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactMessagePresentationTest {
    @Test
    fun contextProgressUsesConfiguredCompactThresholdBoundaries() {
        assertFalse(contextUsageExceedsCompactThreshold(50, 100, 50))
        assertTrue(contextUsageExceedsCompactThreshold(51, 100, 50))
        assertFalse(contextUsageExceedsCompactThreshold(90, 100, 90))
        assertTrue(contextUsageExceedsCompactThreshold(91, 100, 90))
        assertFalse(contextUsageExceedsCompactThreshold(100, 100, 100))
        assertFalse(contextUsageExceedsCompactThreshold(1, 0, 90))
    }
}
