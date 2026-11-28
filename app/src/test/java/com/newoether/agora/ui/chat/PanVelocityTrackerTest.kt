package com.newoether.agora.ui.chat

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanVelocityTrackerTest {
    @Test
    fun fastFlickKeepsFingerDirection() {
        // Samples recorded on device where Compose's VelocityTracker reported (-3102, 656).
        val tracker = PanVelocityTracker()
        tracker.add(233, Offset.Zero)
        tracker.add(241, Offset(85.7f, -18.1f))
        tracker.add(249, Offset(84.8f, -17.9f))
        tracker.add(251, Offset(29.2f, -6.1f))
        tracker.add(259, Offset.Zero) // lift, no movement
        val v = tracker.velocity()
        assertTrue("x must follow the finger, was ${v.x}", v.x > 0f)
        assertTrue("y must follow the finger, was ${v.y}", v.y < 0f)
        assertEquals(199.7f / 18f * 1000f, v.x, 1f)
    }

    @Test
    fun restBeforeLiftGivesNoFling() {
        val tracker = PanVelocityTracker()
        tracker.add(0, Offset.Zero)
        tracker.add(10, Offset(100f, 0f))
        tracker.add(200, Offset.Zero)
        assertEquals(Offset.Zero, tracker.velocity())
    }

    @Test
    fun resetDropsPinchMotion() {
        val tracker = PanVelocityTracker()
        tracker.add(0, Offset.Zero)
        tracker.add(10, Offset(-500f, 0f))
        tracker.reset(20)
        tracker.add(30, Offset(50f, 0f))
        assertEquals(5000f, tracker.velocity().x, 1f)
    }
}
