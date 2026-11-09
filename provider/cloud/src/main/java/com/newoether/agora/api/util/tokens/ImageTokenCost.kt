package com.newoether.agora.api.util.tokens

/** Facts about one transmitted artifact. Encoded length is retained for compatibility, never priced. */
data class ImageDescriptor(
    val byteSize: Long? = null,
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
)

enum class ImageEstimateBasis { DOCUMENTED, HEURISTIC, MISSING_DIMENSIONS }

/** Raw visual cost, before the independent request safety margin. No universal bound is implied. */
data class ImageTokenEstimate(
    val tokens: Long,
    val basis: ImageEstimateBasis,
    val ruleId: String,
    /** Unavailable artifacts are provisional costs and cannot establish a sendable request. */
    val unavailable: Boolean = false,
)

/** Prices one provider-visible image occurrence, before any safety margin. */
fun interface ImageTokenCost {
    fun tokens(image: ImageDescriptor): Long

    fun estimate(image: ImageDescriptor): ImageTokenEstimate = ImageTokenEstimate(
        tokens = tokens(image),
        basis = ImageEstimateBasis.HEURISTIC,
        ruleId = "custom-image-cost",
    )
}

/** Unpublished models use a dimension-only 32 px patch proxy, without a byte divisor or cap. */
object HeuristicPixelImageCost : ImageTokenCost {
    // This is an explicit provisional reservation, not a measured estimate or a provider bound.
    const val MISSING_DIMENSIONS_RESERVATION = 1_024L

    override fun tokens(image: ImageDescriptor): Long = estimate(image).tokens

    override fun estimate(image: ImageDescriptor): ImageTokenEstimate {
        val width = image.pixelWidth?.takeIf { it > 0 }
        val height = image.pixelHeight?.takeIf { it > 0 }
        return if (width == null || height == null) {
            ImageTokenEstimate(
                MISSING_DIMENSIONS_RESERVATION,
                ImageEstimateBasis.MISSING_DIMENSIONS,
                "missing-dimensions-reservation",
            )
        } else {
            ImageTokenEstimate(
                blocksCovering(width, 32) * blocksCovering(height, 32),
                ImageEstimateBasis.HEURISTIC,
                "unpublished-32px-patch-proxy",
            )
        }
    }
}
