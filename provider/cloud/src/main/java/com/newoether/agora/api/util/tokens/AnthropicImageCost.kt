package com.newoether.agora.api.util.tokens

import kotlin.math.round

/**
 * Native automatic resizing, verified 2026-10-27:
 * https://platform.claude.com/docs/en/build-with-claude/vision
 * https://platform.claude.com/docs/en/build-with-claude/vision-coordinates
 * One token per padded 28 px patch, with the largest aspect-preserving size that fits both limits.
 * Uses the reference implementation's ties-to-even rounding and checks the padded edge length.
 */
class AnthropicImageCost(private val tier: Tier) : PixelImageTokenCost(
    basis = ImageEstimateBasis.DOCUMENTED,
    ruleId = "claude-${tier.name.lowercase()}-automatic-28px-patch",
) {
    enum class Tier(val maxLongEdgePx: Int, val maxVisualTokens: Long) {
        STANDARD(maxLongEdgePx = 1_568, maxVisualTokens = 1_568L),
        HIGH_RESOLUTION(maxLongEdgePx = 2_576, maxVisualTokens = 4_784L),
    }

    override fun tokensForPixels(width: Int, height: Int): Long {
        val longEdge = maxOf(width, height)
        val shortEdge = minOf(width, height)
        fun shortAt(edge: Int): Int =
            round(edge.toDouble() * shortEdge / longEdge).toInt().coerceAtLeast(1)
        fun fits(edge: Int): Boolean {
            val short = shortAt(edge)
            return blocksCovering(edge, 28) * 28 <= tier.maxLongEdgePx &&
                blocksCovering(short, 28) * 28 <= tier.maxLongEdgePx &&
                visualTokens(edge, short) <= tier.maxVisualTokens
        }
        if (fits(longEdge)) return visualTokens(width, height)
        var low = 1
        var high = longEdge
        while (low + 1 < high) {
            val mid = low + (high - low) / 2
            if (fits(mid)) low = mid else high = mid
        }
        return visualTokens(low, shortAt(low))
    }

    private fun visualTokens(width: Int, height: Int): Long =
        blocksCovering(width, 28) * blocksCovering(height, 28)

    companion object {
        fun tierForModelName(modelName: String): Tier =
            if (AnthropicModelId.parse(modelName).versionAtLeast(4, 7)) {
                Tier.HIGH_RESOLUTION
            } else {
                Tier.STANDARD
            }

        fun forModelName(modelName: String): ImageTokenCost =
            if (AnthropicModelId.parse(modelName).major == null) HeuristicPixelImageCost
            else AnthropicImageCost(tierForModelName(modelName))
    }
}
