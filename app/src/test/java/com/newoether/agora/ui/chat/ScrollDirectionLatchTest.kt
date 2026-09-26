package com.newoether.agora.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scroll-to-bottom button follows this latch, so the latch is what keeps the button from
 * blinking during the small scroll corrections a reader makes.
 */
class ScrollDirectionLatchTest {

    @Test
    fun `starts pointing away from the tail`() {
        assertFalse(ScrollDirectionLatch(thresholdPx = 100f).headingToTail)
    }

    @Test
    fun `short scrolls do not flip the latch`() {
        val latch = ScrollDirectionLatch(thresholdPx = 100f)
        repeat(4) { latch.onDelta(20f) }
        assertFalse(latch.headingToTail)
        latch.onDelta(20f)
        assertTrue("five 20px steps cover the threshold", latch.headingToTail)
    }

    @Test
    fun `reversing direction restarts the run instead of unwinding it`() {
        val latch = ScrollDirectionLatch(thresholdPx = 100f)
        latch.onDelta(100f)
        assertTrue(latch.headingToTail)
        latch.onDelta(-40f)
        assertTrue("a short scroll back must not hide the button", latch.headingToTail)
        latch.onDelta(-60f)
        assertFalse("but a full threshold upward does", latch.headingToTail)
        latch.onDelta(40f)
        assertFalse(latch.headingToTail)
        latch.onDelta(60f)
        assertTrue("and the downward run starts from the reversal, not from the old total", latch.headingToTail)
    }

    @Test
    fun `a single long scroll flips the latch once`() {
        val latch = ScrollDirectionLatch(thresholdPx = 100f)
        latch.onDelta(4_000f)
        assertTrue(latch.headingToTail)
        latch.onDelta(0f)
        assertTrue("an idle frame changes nothing", latch.headingToTail)
    }
}
