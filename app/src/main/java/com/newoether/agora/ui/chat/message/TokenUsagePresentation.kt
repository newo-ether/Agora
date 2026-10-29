package com.newoether.agora.ui.chat.message

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.TokenUsage
import java.util.Locale

internal data class TokenUsagePresentation(
    val input: Int?,
    val cachedInput: Int?,
    val output: Int?,
    val generationTokensPerSecond: Double? = null,
    val timeToFirstTokenMs: Long? = null,
    val totalGenerationMs: Long? = null,
)

/** Sub-second waits read in milliseconds; anything longer reads in seconds. */
internal fun formatLatency(millis: Long, locale: Locale = Locale.getDefault()): String =
    if (millis < 1_000L) "$millis ms"
    else String.format(locale, "%.1f s", millis / 1_000.0)

internal fun tokenUsagePresentation(
    usage: TokenUsage?,
): TokenUsagePresentation {
    if (usage == null) return TokenUsagePresentation(null, null, null)
    val input = usage.inputTokenCount
        ?: if (
            usage.cachedInputTokenCount != null &&
            usage.uncachedInputTokenCount != null
        ) {
            TokenUsage.addCounts(
                usage.cachedInputTokenCount,
                usage.uncachedInputTokenCount,
            )
        } else {
            usage.outputTokenCount
                ?.let { output -> (usage.totalTokenCount - output).takeIf { it >= 0 } }
        }
    val output = usage.outputTokenCount
        ?: input?.let { inputCount ->
            (usage.totalTokenCount - inputCount).takeIf { it >= 0 }
        }
    return TokenUsagePresentation(
        input = input,
        cachedInput = usage.cachedInputTokenCount,
        output = output,
        generationTokensPerSecond = usage.generationDurationMs?.takeIf { it > 0 }
            ?.let { duration -> output?.takeIf { it > 0 }?.toDouble()?.times(1000.0)?.div(duration) },
        timeToFirstTokenMs = usage.timeToFirstTokenMs?.takeIf { it > 0 },
        totalGenerationMs = usage.totalGenerationMs?.takeIf { it > 0 },
    )
}

/** Locale-formatted rate without its unit, so the dialog and the chat row agree on the number. */
internal fun formatTokenRate(tokensPerSecond: Double): String =
    String.format(Locale.getDefault(), "%.1f", tokensPerSecond)

/** Localized rate with its unit, e.g. `12.3 tokens/s`. */
@Composable
internal fun tokenRateText(tokensPerSecond: Double): String =
    stringResource(R.string.token_speed_value, formatTokenRate(tokensPerSecond))

/**
 * Chat-row summary for the replies that measured either figure; unmeasured ones drop out rather
 * than print a dash beside every action icon.
 */
@Composable
internal fun generationStatsText(
    tokensPerSecond: Double?,
    totalGenerationMs: Long?,
): String? {
    val rate = tokensPerSecond?.let { tokenRateText(it) }
    val total = totalGenerationMs?.let {
        stringResource(R.string.total_generation_time_row, formatLatency(it))
    }
    return when {
        rate == null -> total
        total == null -> rate
        else -> stringResource(R.string.generation_stats_value, rate, total)
    }
}
