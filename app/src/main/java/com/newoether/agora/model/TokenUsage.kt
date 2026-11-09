package com.newoether.agora.model

/**
 * Aggregates provider usage without double-counting cumulative stream snapshots.
 *
 * A provider request may report usage multiple times; [observeRequestSnapshot] replaces the
 * current request snapshot. Only [finishRequest] adds that final snapshot to previous tool-loop
 * rounds.
 */
internal class RequestTokenUsageAccumulator(
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var firstContentNanos: Long? = null
    private var lastContentNanos: Long? = null
    private var completedGenerationNanos = 0L

    fun observeGenerationContent() {
        if (!requestActive) return
        val now = nowNanos()
        if (firstContentNanos == null) firstContentNanos = now
        lastContentNanos = now
    }

    fun pauseGeneration() {
        val first = firstContentNanos
        val last = lastContentNanos
        if (first != null && last != null && last > first) completedGenerationNanos += last - first
        firstContentNanos = null
        lastContentNanos = null
    }

    fun resetGenerationTiming() {
        firstContentNanos = null
        lastContentNanos = null
        completedGenerationNanos = 0L
    }

    private var completedUsage: TokenUsage? = null
    private var currentRequestUsage: TokenUsage? = null
    private var requestActive = false

    fun beginRequest() {
        check(!requestActive) { "A token-usage request is already active" }
        currentRequestUsage = null
        resetGenerationTiming()
        requestActive = true
    }

    /** Drops only the failed attempt; completed tool-round usage remains authoritative. */
    fun resetCurrentAttempt() {
        currentRequestUsage = null
        resetGenerationTiming()
    }

    fun observeRequestSnapshot(usage: TokenUsage) {
        check(requestActive) { "Token usage arrived outside a provider request" }
        currentRequestUsage = usage
    }

    fun finishRequest() {
        if (!requestActive) return
        pauseGeneration()
        currentRequestUsage?.let { usage ->
            val requestUsage = usage.copy(
                generationDurationMs = usage.generationDurationMs
                    ?: (completedGenerationNanos / 1_000_000L).takeIf { it > 0 },
            )
            completedUsage = completedUsage?.plusRequest(requestUsage) ?: requestUsage
        }
        currentRequestUsage = null
        requestActive = false
    }

    fun snapshot(): TokenUsage? = when {
        completedUsage == null -> currentRequestUsage
        currentRequestUsage == null -> completedUsage
        else -> completedUsage?.plusRequest(checkNotNull(currentRequestUsage))
    }
}
