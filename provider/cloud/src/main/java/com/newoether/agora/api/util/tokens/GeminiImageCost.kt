package com.newoether.agora.api.util.tokens

/**
 * Native Gemini defaults, verified 2026-10-27:
 * https://ai.google.dev/gemini-api/docs/media-resolution
 * https://ai.google.dev/gemini-api/docs/image-understanding
 * No explicit media_resolution is sent. Gemini 3/4 unspecified defaults to approximately 1120
 * image tokens. Earlier models use the documented rough crop formula. Rasterized PDF pages and
 * extracted video frames are images on the wire, not native PDF/video inputs.
 */
object GeminiImageCost : PixelImageTokenCost(
    basis = ImageEstimateBasis.DOCUMENTED,
    ruleId = "gemini-pre3-unspecified-rough-crops",
) {
    override fun tokensForPixels(width: Int, height: Int): Long {
        if (width <= 384 && height <= 384) return 258L
        val crop = kotlin.math.floor(minOf(width, height) / 1.5).toInt().coerceAtLeast(1)
        return blocksCovering(width, crop) * blocksCovering(height, crop) * 258L
    }

    private object CurrentDefault : PixelImageTokenCost(
        basis = ImageEstimateBasis.DOCUMENTED,
        ruleId = "gemini-3-4-unspecified-image-1120",
    ) {
        override fun tokensForPixels(width: Int, height: Int): Long = 1_120L
    }

    fun forModelName(modelName: String): ImageTokenCost {
        val name = modelName.trim().substringAfterLast('/').lowercase()
        return when {
            Regex("^gemini-[34](?:[.-]|$)").containsMatchIn(name) -> CurrentDefault
            Regex("^gemini-[12](?:[.-]|$)").containsMatchIn(name) -> this
            else -> HeuristicPixelImageCost
        }
    }
}
