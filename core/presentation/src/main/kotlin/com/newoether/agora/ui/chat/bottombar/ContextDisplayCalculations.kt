package com.newoether.agora.ui.chat.bottombar

/**
 * The part of the window the automatic compaction keeps free. Mirrors the threshold arithmetic in
 * [contextUsageExceedsCompactThreshold] so the bar and the warning state flip at the same token.
 */
fun contextReservedTokens(tokenBudget: Int, thresholdPercent: Int): Int {
    val budget = tokenBudget.coerceAtLeast(1)
    val percent = thresholdPercent.coerceIn(50, 100)
    val threshold = ((budget.toLong() * percent + 99L) / 100L)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return (budget - threshold).coerceAtLeast(0)
}

fun contextUsageExceedsCompactThreshold(
    estimatedTokens: Int, tokenBudget: Int, thresholdPercent: Int,
): Boolean {
    val normalizedBudget = tokenBudget.coerceAtLeast(1)
    val normalizedPercent = thresholdPercent.coerceIn(50, 100)
    val threshold = ((normalizedBudget.toLong() * normalizedPercent + 99L) / 100L)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return tokenBudget > 0 && estimatedTokens > threshold
}
