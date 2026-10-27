package com.newoether.agora.api.util.tokens

/** Pixel rules read measured metadata only. Media owners resolve missing dimensions. */
abstract class PixelImageTokenCost(
    private val basis: ImageEstimateBasis = ImageEstimateBasis.HEURISTIC,
    private val ruleId: String = "unverified-pixel-rule",
) : ImageTokenCost {
    final override fun tokens(image: ImageDescriptor): Long = estimate(image).tokens

    final override fun estimate(image: ImageDescriptor): ImageTokenEstimate {
        val width = image.pixelWidth?.takeIf { it > 0 }
        val height = image.pixelHeight?.takeIf { it > 0 }
        return if (width != null && height != null) {
            ImageTokenEstimate(tokensForPixels(width, height), basis, ruleId)
        } else {
            HeuristicPixelImageCost.estimate(image)
        }
    }

    protected abstract fun tokensForPixels(width: Int, height: Int): Long
}

/** Whole blocks of [divisor] needed to cover [value]. */
internal fun blocksCovering(value: Int, divisor: Int): Long {
    require(divisor > 0)
    if (value <= 0) return 0L
    return (value.toLong() + divisor - 1L) / divisor
}

/** Aspect-preserving companion dimension. Provider-specific rounding belongs in its own rule. */
internal fun proportionalEdge(edge: Int, fromEdge: Int, toEdge: Int): Int {
    if (fromEdge <= 0) return 1
    return Math.round(edge.toDouble() * toEdge / fromEdge).toInt().coerceAtLeast(1)
}
