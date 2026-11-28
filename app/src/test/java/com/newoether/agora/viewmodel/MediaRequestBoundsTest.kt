package com.newoether.agora.viewmodel

import com.newoether.agora.ui.chat.VideoSliceDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRequestBoundsTest {
    @Test
    fun longScreenshotUsesLongestEdgeForSampling() {
        val sample = imageSampleSizeForBounds(width = 40_000, height = 600)
        assertEquals(16, sample)
        assertTrue(40_000 / sample >= 2_048)
    }

    @Test
    fun ordinaryImageDoesNotUpsample() {
        assertEquals(1, imageSampleSizeForBounds(width = 1_024, height = 768))
    }

    @Test
    fun longVideoDefaultFrameCountIsNotCappedAtTwenty() {
        assertEquals(120, VideoSliceDefaults.defaultFrameCount(durationMs = 600_000L))
    }
}
