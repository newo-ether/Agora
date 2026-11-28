package com.newoether.agora.api.util.tokens

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Auto detail rules read 2026-10-27:
 * https://developers.openai.com/api/docs/guides/images-vision
 * 32 px patches after aspect-preserving edge fitting and the published budget correction.
 * The 30,000-patch rejection is not a resizing budget and must never reduce the estimate.
 * Billing floating-point rounding may differ by one token. These are image inputs, not generation.
 */
class OpenAiPatchImageCost(
    private val maxEdgePx: Int,
    private val patchBudget: Int?,
    private val multiplier: Double,
    ruleId: String = "openai-32px-patch-auto",
    basis: ImageEstimateBasis = ImageEstimateBasis.DOCUMENTED,
) : PixelImageTokenCost(basis, ruleId) {
    override fun tokensForPixels(width: Int, height: Int): Long {
        val longEdge = maxOf(width, height)
        var fittedWidth = width
        var fittedHeight = height
        if (longEdge > maxEdgePx) {
            if (width >= height) {
                fittedWidth = maxEdgePx
                fittedHeight = proportionalEdge(height, width, maxEdgePx)
            } else {
                fittedHeight = maxEdgePx
                fittedWidth = proportionalEdge(width, height, maxEdgePx)
            }
        }
        var patches = patchesCovering(fittedWidth, fittedHeight)
        val budget = patchBudget
        if (budget != null && patches > budget) {
            val byArea = sqrt(32.0 * 32.0 * budget / (fittedWidth.toDouble() * fittedHeight))
            val widthPatches = fittedWidth * byArea / 32
            val heightPatches = fittedHeight * byArea / 32
            val correction = minOf(
                floor(widthPatches) / widthPatches,
                floor(heightPatches) / heightPatches,
            )
            val shrink = byArea * correction
            val scaledWidth = floor(fittedWidth * shrink).toInt().coerceAtLeast(1)
            val scaledHeight = floor(fittedHeight * shrink).toInt().coerceAtLeast(1)
            patches = patchesCovering(scaledWidth, scaledHeight)
        }
        return ceil(patches * multiplier).toLong()
    }

    private fun patchesCovering(width: Int, height: Int): Long =
        blocksCovering(width, 32) * blocksCovering(height, 32)
}

/** Published high/auto tile sizing, which never enlarges small images. */
class OpenAiTileImageCost(
    private val baseTokens: Int,
    private val tileTokens: Int,
    ruleId: String = "openai-512px-tile-auto",
) : PixelImageTokenCost(ImageEstimateBasis.DOCUMENTED, ruleId) {
    override fun tokensForPixels(width: Int, height: Int): Long {
        var fittedWidth = width
        var fittedHeight = height
        if (maxOf(width, height) > 2_048) {
            if (width >= height) {
                fittedWidth = 2_048
                fittedHeight = proportionalEdge(height, width, 2_048)
            } else {
                fittedHeight = 2_048
                fittedWidth = proportionalEdge(width, height, 2_048)
            }
        }
        val shortEdge = minOf(fittedWidth, fittedHeight)
        if (shortEdge > 768) {
            val ratio = 768.0 / shortEdge
            if (fittedWidth <= fittedHeight) {
                fittedHeight = floor(fittedHeight * ratio).toInt().coerceAtLeast(1)
                fittedWidth = 768
            } else {
                fittedWidth = floor(fittedWidth * ratio).toInt().coerceAtLeast(1)
                fittedHeight = 768
            }
        }
        return baseTokens + blocksCovering(fittedWidth, 512) *
            blocksCovering(fittedHeight, 512) * tileTokens
    }
}

/** Only listed model ids and their dated snapshots inherit a documented auto rule. */
object OpenAiImageCosts {
    private fun String.isModel(model: String): Boolean =
        this == model || (
            startsWith("$model-") &&
                removePrefix("$model-").matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
            )

    fun forModelName(modelName: String): ImageTokenCost {
        val name = modelName.trim().substringAfterLast('/').lowercase()
        fun patch(edge: Int, budget: Int?, multiplier: Double, rule: String) =
            OpenAiPatchImageCost(edge, budget, multiplier, rule)
        return when {
            name.isModel("gpt-6-astra") ||
                listOf("sol", "terra", "luna").any { name.isModel("gpt-5.6-$it") } ->
                patch(65_535, null, 1.2, "openai-original-auto-65535-30000-reject")
            name.isModel("gpt-5.5") -> patch(6_000, 10_000, 1.2, "openai-5.5-original-auto")
            listOf("gpt-5.4", "gpt-5.4-mini", "gpt-5.4-nano").any { name.isModel(it) } ->
                patch(2_048, 2_500, 1.2, "openai-5.4-high-auto")
            name.isModel("gpt-5.2") -> patch(2_048, 6_144, 1.2, "openai-5.2-auto")
            name.isModel("gpt-4.1-mini") -> patch(2_048, 6_144, 1.62, "openai-4.1-mini-auto")
            name.isModel("gpt-4o-mini") -> OpenAiTileImageCost(2_833, 5_667, "openai-4o-mini-auto")
            name.isModel("gpt-4o") || name.isModel("gpt-4.1") ->
                OpenAiTileImageCost(85, 170, "openai-4o-4.1-auto")
            name.isModel("gpt-5.1") || name.isModel("gpt-5") ->
                OpenAiTileImageCost(70, 140, "openai-5-5.1-auto")
            listOf("o1", "o1-pro", "o3").any { name.isModel(it) } ->
                OpenAiTileImageCost(75, 150, "openai-o1-o3-auto")
            // A multiplier alone cannot establish resizing for deprecated or specialized variants.
            else -> HeuristicPixelImageCost
        }
    }
}
