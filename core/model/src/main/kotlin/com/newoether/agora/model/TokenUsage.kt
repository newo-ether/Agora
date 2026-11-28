package com.newoether.agora.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Provider-reported token usage for one or more completed model requests.
 *
 * Optional categories stay null when a provider did not report enough information to derive them.
 * In particular, an unknown cache split must never be presented as a real zero.
 *
 * [outputTokenCount] includes reasoning tokens when the provider bills/reports them as output;
 * [reasoningTokenCount] is the reported subset available for a later detailed breakdown.
 */
@Immutable
@Serializable
data class TokenUsage(
    val totalTokenCount: Int,
    val inputTokenCount: Int? = null,
    val cachedInputTokenCount: Int? = null,
    val cacheWriteInputTokenCount: Int? = null,
    val uncachedInputTokenCount: Int? = null,
    val outputTokenCount: Int? = null,
    val reasoningTokenCount: Int? = null,
    /** Sum of observed generation intervals; excludes first-content wait and tool execution. */
    val generationDurationMs: Long? = null,
) {
    fun plusRequest(other: TokenUsage): TokenUsage = TokenUsage(
        totalTokenCount = addCounts(totalTokenCount, other.totalTokenCount),
        inputTokenCount = addReported(inputTokenCount, other.inputTokenCount),
        cachedInputTokenCount =
            addReported(cachedInputTokenCount, other.cachedInputTokenCount),
        cacheWriteInputTokenCount =
            addReported(cacheWriteInputTokenCount, other.cacheWriteInputTokenCount),
        uncachedInputTokenCount =
            addReported(uncachedInputTokenCount, other.uncachedInputTokenCount),
        outputTokenCount = addReported(outputTokenCount, other.outputTokenCount),
        reasoningTokenCount =
            addReported(reasoningTokenCount, other.reasoningTokenCount),
        generationDurationMs = if (generationDurationMs != null && other.generationDurationMs != null)
            generationDurationMs.takeIf { it <= Long.MAX_VALUE - other.generationDurationMs }
                ?.plus(other.generationDurationMs) else null,
    )

    companion object {
        fun fromPersisted(
            totalTokenCount: Int,
            inputTokenCount: Int?,
            cachedInputTokenCount: Int?,
            cacheWriteInputTokenCount: Int?,
            uncachedInputTokenCount: Int?,
            outputTokenCount: Int?,
            reasoningTokenCount: Int?,
            generationDurationMs: Long? = null,
        ): TokenUsage? {
            if (
                totalTokenCount <= 0 &&
                inputTokenCount == null &&
                cachedInputTokenCount == null &&
                cacheWriteInputTokenCount == null &&
                uncachedInputTokenCount == null &&
                outputTokenCount == null &&
                reasoningTokenCount == null
            ) {
                return null
            }
            return TokenUsage(
                totalTokenCount = totalTokenCount.coerceAtLeast(0),
                inputTokenCount = inputTokenCount.nonNegativeOrNull(),
                cachedInputTokenCount = cachedInputTokenCount.nonNegativeOrNull(),
                cacheWriteInputTokenCount = cacheWriteInputTokenCount.nonNegativeOrNull(),
                uncachedInputTokenCount = uncachedInputTokenCount.nonNegativeOrNull(),
                outputTokenCount = outputTokenCount.nonNegativeOrNull(),
                reasoningTokenCount = reasoningTokenCount.nonNegativeOrNull(),
                generationDurationMs = generationDurationMs?.takeIf { it > 0 },
            )
        }

        fun addCounts(first: Int, second: Int): Int =
            (first.toLong() + second.toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()

        private fun addReported(first: Int?, second: Int?): Int? =
            if (first == null || second == null) null else addCounts(first, second)

        private fun Int?.nonNegativeOrNull(): Int? = this?.coerceAtLeast(0)
    }
}
