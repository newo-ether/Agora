package com.newoether.agora.model

/**
 * What a single model can actually do with thinking/reasoning parameters.
 *
 * This replaces model-name guesswork spread across providers. A capability never blocks a request:
 * models without documentation get the permissive superset in [ModelThinkingCapability.Permissive],
 * so every option stays in front of the user.
 *
 * @param canDisableThinking whether the endpoint accepts a "thinking off" request for this model.
 * @param supportedEfforts ordered subset of [ThinkingLevels.effortValues]; empty means the model
 *   has no effort selector at all.
 * @param supportsThinkingBudget whether an explicit thinking token budget is accepted.
 * @param minBudgetTokens lowest accepted budget value when budgets are supported.
 * @param maxBudgetTokens highest accepted budget value, or null when the endpoint does not document
 *   a fixed ceiling (the model's own output limit applies).
 * @param supportsEffortWithBudget whether effort and budget may be sent in the same request.
 * @param supportsSamplingParams whether temperature / top_p are accepted for this model.
 */
data class ModelThinkingCapability(
    val canDisableThinking: Boolean = true,
    val supportedEfforts: List<String> = ThinkingLevels.effortValues,
    val supportsThinkingBudget: Boolean = true,
    val minBudgetTokens: Int = 1,
    val maxBudgetTokens: Int? = null,
    val supportsEffortWithBudget: Boolean = true,
    val supportsSamplingParams: Boolean = true,
) {
    val supportsEffort: Boolean get() = supportedEfforts.isNotEmpty()

    /** Lowest effort this model accepts, or null when it has no effort selector. */
    val lowestEffort: String? get() = supportedEfforts.firstOrNull()

    /**
     * Nearest accepted effort for [effort]. Ties resolve downward, so a value above the model's
     * range lands on its highest accepted level and a value below lands on its lowest.
     * Returns null when the model has no effort selector.
     */
    fun nearestEffort(effort: String): String? {
        if (supportedEfforts.isEmpty()) return null
        val normalized = ThinkingLevels.normalize(effort)
        if (normalized in supportedEfforts) return normalized
        val wanted = ThinkingLevels.effortValues.indexOf(normalized)
        if (wanted < 0) return supportedEfforts.first()
        return supportedEfforts.minByOrNull { candidate ->
            val index = ThinkingLevels.effortValues.indexOf(candidate)
            val distance = kotlin.math.abs(index - wanted)
            // Equal distances resolve upward, so a request the model does not accept lands on at
            // least the amount of thinking it asked for.
            distance * 2 + if (index < wanted) 1 else 0
        }
    }

    fun clampBudget(tokens: Int): Int {
        val ceiling = maxBudgetTokens ?: Int.MAX_VALUE
        return tokens.coerceIn(minBudgetTokens, ceiling)
    }

    companion object {
        /**
         * Used for every model without a documented entry, including custom relays and unknown
         * ids. Nothing is withheld and nothing is blocked.
         */
        val Permissive = ModelThinkingCapability()
    }
}

/**
 * The thinking configuration that will actually be sent, after the model's capability is applied.
 *
 * [effort] is null when the model has no effort selector. [budgetTokens] is null when no budget is
 * sent. When a model cannot disable thinking, [enabled] stays true and [effort] / [budgetTokens]
 * hold the lowest accepted setting, so forced-thinking-off callers (compact, title generation,
 * transcription) still produce a valid request instead of failing.
 */
data class ResolvedThinking(
    val enabled: Boolean,
    val effort: String?,
    val budgetTokens: Int?,
    val capability: ModelThinkingCapability,
) {
    val disabled: Boolean get() = !enabled
}

object ThinkingResolution {
    fun resolve(
        capability: ModelThinkingCapability,
        requestedEnabled: Boolean,
        requestedEffort: String,
        requestedBudgetEnabled: Boolean,
        requestedBudgetTokens: Int,
    ): ResolvedThinking {
        if (!requestedEnabled) {
            if (capability.canDisableThinking) {
                return ResolvedThinking(
                    enabled = false,
                    effort = null,
                    budgetTokens = null,
                    capability = capability,
                )
            }
            // Thinking cannot be turned off for this model: use its cheapest valid setting rather
            // than rejecting the request, and ignore the caller's unrelated effort value.
            // No budget is invented here: a model without an effort selector simply reports
            // thinking as on, which is the smallest valid request it accepts.
            return ResolvedThinking(
                enabled = true,
                effort = capability.lowestEffort,
                budgetTokens = null,
                capability = capability,
            )
        }

        val budgetRequested = requestedBudgetEnabled && capability.supportsThinkingBudget
        val budget = if (budgetRequested) capability.clampBudget(requestedBudgetTokens) else null
        val effort = when {
            budget != null && !capability.supportsEffortWithBudget -> null
            else -> capability.nearestEffort(requestedEffort)
        }
        return ResolvedThinking(
            enabled = true,
            effort = effort,
            budgetTokens = budget,
            capability = capability,
        )
    }
}
