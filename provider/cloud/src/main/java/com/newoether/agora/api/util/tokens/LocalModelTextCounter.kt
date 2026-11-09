package com.newoether.agora.api.util.tokens

/**
 * Exact text counting for the embedded model, with the offline heuristic as a fallback.
 *
 * The embedded runtime is the one case where the real vocabulary is on the device, so its counts can
 * be exact instead of estimated. The vocabulary only exists while a model is resident, and the
 * estimate must stay synchronous and must never load a model, so [exactCount] returns null whenever
 * the runtime cannot answer and the heuristic takes over. That keeps the indicator working before
 * the first generation and while the model is being swapped.
 */
class LocalModelTextCounter(
    private val exactCount: (String) -> Int?,
    private val fallback: TextTokenCounter = HeuristicTextTokenCounter,
) : TextTokenCounter {

    override fun count(text: String): Long {
        if (text.isEmpty()) return 0L
        // A negative count is a failure report, not a cost, so it falls back like a missing answer.
        val exact = runCatching { exactCount(text) }.getOrNull()?.takeIf { it >= 0 }
        return exact?.toLong() ?: fallback.count(text)
    }
}
