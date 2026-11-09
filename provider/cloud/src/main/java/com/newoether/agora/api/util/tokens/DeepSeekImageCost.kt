package com.newoether.agora.api.util.tokens

import kotlin.math.sqrt

/**
 * DeepSeek's current official image calculator, read 2026-10-27.
 * https://api-docs.deepseek.com/quick_start/token_usage
 * https://api-docs.deepseek.com/assets/js/1e0da2c4.24c3fb4b.js
 * Calculator SHA-256: 41c6c9275506c3cf15575df31b1fe59d7eb8c3b5f08ca9eaf48ac903622c25c5.
 * The live calculator selects v41: 14 px patches, 3x downsampling, row separators and two tokens.
 * Auto currently means original in https://api-docs.deepseek.com/guides/vision.
 * This reproduces that calculator, not exact inference usage or the retired v4 N-layout.
 */
object DeepSeekImageCost : PixelImageTokenCost(
    basis = ImageEstimateBasis.DOCUMENTED,
    ruleId = "deepseek-v41-auto-calculator-24c3fb4b",
) {
    private const val PATCH = 14
    private const val DOWNSAMPLE = 3
    private const val CELL = PATCH * DOWNSAMPLE
    private const val MAX_TOKENS = 1_024L
    private const val MIN_PIXELS = 295_936.0

    private data class Resize(
        val width: Int,
        val height: Int,
        val columns: Long,
        val rows: Long,
    ) {
        val tokens: Long get() = rows * (columns + 1L) + 2L
    }

    override fun tokensForPixels(width: Int, height: Int): Long {
        var previous = resize(width, height)
        repeat(9) {
            val next = resize(previous.width, previous.height)
            if (next == previous) return previous.tokens
            previous = next
        }
        error("DeepSeek image calculator did not converge after 10 iterations")
    }

    private fun resize(width: Int, height: Int): Resize {
        val pixels = width.toDouble() * height
        val scale = if (pixels < MIN_PIXELS) sqrt(MIN_PIXELS / pixels) else 1.0
        val inputWidth = (width * scale).toInt().coerceAtLeast(1)
        val inputHeight = (height * scale).toInt().coerceAtLeast(1)
        val columns = blocksCovering(inputWidth, CELL)
        val rows = blocksCovering(inputHeight, CELL)
        if (rows * (columns + 1L) + 2L > MAX_TOKENS) return solve(inputWidth, inputHeight)
        return result(
            (blocksCovering(inputWidth, PATCH) * PATCH).toInt(),
            (blocksCovering(inputHeight, PATCH) * PATCH).toInt(),
        )
    }

    private fun solve(width: Int, height: Int): Resize {
        val aspect = height.toDouble() / width
        val widthUnits = sqrt((MAX_TOKENS - 2L) / aspect + 0.25) - 0.5
        val heightUnits = widthUnits * aspect
        val resized = when {
            widthUnits < 1.0 -> result(CELL, ((MAX_TOKENS - 2L) / 2L * CELL).toInt())
            heightUnits < 1.0 -> result(((MAX_TOKENS - 3L) * CELL).toInt(), CELL)
            else -> {
                val scale = minOf(
                    widthUnits.toInt() * CELL.toDouble() / width,
                    heightUnits.toInt() * CELL.toDouble() / height,
                )
                result(
                    (width * scale / PATCH).toInt() * PATCH,
                    (height * scale / PATCH).toInt() * PATCH,
                )
            }
        }
        check(resized.tokens <= MAX_TOKENS) { "DeepSeek image calculator exceeded its token budget" }
        return resized
    }

    private fun result(width: Int, height: Int): Resize = Resize(
        width = width,
        height = height,
        columns = blocksCovering(width / PATCH, DOWNSAMPLE),
        rows = blocksCovering(height / PATCH, DOWNSAMPLE),
    )

    fun forModelName(modelName: String): ImageTokenCost {
        val name = modelName.trim().substringAfterLast('/').lowercase()
        return if (
            name == "deepseek-flash" || name == "deepseek-v4-flash-vision-exp"
        ) this else HeuristicPixelImageCost
    }
}
