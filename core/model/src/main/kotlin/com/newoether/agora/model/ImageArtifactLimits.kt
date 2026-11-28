package com.newoether.agora.model

/** Shared byte limits and decode sizing; file ownership and image processing stay with callers. */
object ImageArtifactLimits {
    const val MAX_ATTACHMENT_BYTES: Long = 100L * 1024L * 1024L
    const val DEFAULT_MAX_LONG_EDGE: Int = 2048

    /** Keep enough decoded pixels for the final resize while using power-of-two sampling. */
    fun sampleSizeForBounds(width: Int, height: Int, maxLongEdge: Int): Int {
        require(width > 0 && height > 0 && maxLongEdge > 0)
        val longest = maxOf(width, height).toLong()
        var sample = 1
        while (longest / (sample.toLong() * 2L) >= maxLongEdge) sample *= 2
        return sample
    }
}
