package com.newoether.agora.phone

import android.app.Application
import android.graphics.Rect
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration
import kotlin.math.exp

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PhoneDragFeelTest {
    @Before fun pauseVsync() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(FRAME_MS))
    }

    private fun frame() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS))
    }

    @Test fun heldFollowerKeepsItsFrameClockAfterReachingASmallTarget() {
        val samples = mutableListOf<Float>()
        val follower = DragFollower(1) { samples += it[0] }
        try {
            follower.snap(0f)
            follower.follow(0.25f)
            frame()
            frame()
            assertEquals(0.25f, samples.last(), 0.0001f)

            follower.follow(100f)
            frame()
            val expected = 0.25f + (100f - 0.25f) * (1f - exp(-FRAME_MS.toFloat() / DragFeel.FOLLOW_MS))
            assertEquals("the next target must use the continuing vsync interval", expected, samples.last(), 0.02f)
        } finally {
            follower.stop()
        }
    }

    @Test fun releaseStillSettlesAndRunsItsCallback() {
        val follower = DragFollower(1) { }
        var released = false
        try {
            follower.snap(0f)
            follower.follow(100f)
            frame()
            follower.release(0f) { released = true }
            repeat(100) { frame() }
            assertTrue(released)
        } finally {
            follower.stop()
        }
    }

    @Test fun resizeLimitUsesTheSameRestingEdgeAsTheReleaseSettle() {
        val start = Rect(100, 200, 280, 500)
        val safe = Rect(0, 0, 1000, 1000)
        val (_, limit) = previewResize(1, 10_000f, 10_000f, start, safe,
            edge = 30, aspect = 0.6f, minWidth = 80, maxWidth = 2_000)
        assertEquals(462f, limit, 0.0001f)
        assertEquals(970f, start.top + limit / 0.6f, 0.0001f)
    }

    private companion object {
        const val FRAME_MS = 10L
    }
}
