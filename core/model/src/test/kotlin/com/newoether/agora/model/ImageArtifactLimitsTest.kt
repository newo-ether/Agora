package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageArtifactLimitsTest {
    @Test
    fun samplingKeepsEnoughPixelsAtPowerOfTwoBoundariesInEitherOrientation() {
        for ((longest, expected) in listOf(2048 to 1, 4095 to 1, 4096 to 2, 8191 to 2, 8192 to 4)) {
            assertEquals(expected, ImageArtifactLimits.sampleSizeForBounds(longest, 100, 2048))
            assertEquals(expected, ImageArtifactLimits.sampleSizeForBounds(100, longest, 2048))
        }
    }

    @Test
    fun samplingHandlesMaximumBoundsWithoutIntegerOverflow() {
        assertEquals(1 shl 30, ImageArtifactLimits.sampleSizeForBounds(Int.MAX_VALUE, 1, 1))
        assertEquals(1, ImageArtifactLimits.sampleSizeForBounds(1, 1, Int.MAX_VALUE))
    }

    @Test
    fun samplingRejectsNonPositiveDimensionsAndLimit() {
        for ((width, height, limit) in listOf(Triple(0, 1, 1), Triple(1, -1, 1), Triple(1, 1, 0))) {
            assertThrows(IllegalArgumentException::class.java) {
                ImageArtifactLimits.sampleSizeForBounds(width, height, limit)
            }
        }
    }
}
