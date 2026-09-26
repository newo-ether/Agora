package com.newoether.agora.model

import kotlinx.serialization.Serializable

/**
 * User-supplied correction for one model's thinking capability. Every field is optional; a null
 * field keeps the documented default. This is the escape hatch for relays and for models Agora has
 * no documentation for, so no model can ever be stuck with a wrong built-in assumption.
 */
@Serializable
data class ModelThinkingCapabilityOverride(
    val canDisableThinking: Boolean? = null,
    val supportedEfforts: List<String>? = null,
    val supportsThinkingBudget: Boolean? = null,
    val minBudgetTokens: Int? = null,
    val maxBudgetTokens: Int? = null,
    val supportsEffortWithBudget: Boolean? = null,
    val supportsSamplingParams: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = canDisableThinking == null && supportedEfforts == null &&
            supportsThinkingBudget == null && minBudgetTokens == null &&
            maxBudgetTokens == null && supportsEffortWithBudget == null &&
            supportsSamplingParams == null

    fun applyTo(base: ModelThinkingCapability): ModelThinkingCapability {
        val efforts = supportedEfforts
            ?.map { ThinkingLevels.normalize(it) }
            ?.distinct()
            ?.sortedBy { ThinkingLevels.effortValues.indexOf(it) }
        return base.copy(
            canDisableThinking = canDisableThinking ?: base.canDisableThinking,
            supportedEfforts = efforts ?: base.supportedEfforts,
            supportsThinkingBudget = supportsThinkingBudget ?: base.supportsThinkingBudget,
            minBudgetTokens = (minBudgetTokens ?: base.minBudgetTokens).coerceAtLeast(1),
            maxBudgetTokens = maxBudgetTokens ?: base.maxBudgetTokens,
            supportsEffortWithBudget = supportsEffortWithBudget ?: base.supportsEffortWithBudget,
            supportsSamplingParams = supportsSamplingParams ?: base.supportsSamplingParams,
        )
    }
}

object ModelThinkingCapabilities {

    /** Stable key for persisting one model's override. */
    fun overrideKey(providerName: String?, modelId: String): String =
        "${providerName.orEmpty().trim()}::${modelId.trim()}"

    /**
     * Resolves the wire family. [customProtocolWireValue] is the persisted protocol of a custom
     * provider (`openai`, `google`, `anthropic`) and wins over the display name, because a relay can
     * be named anything.
     */
    fun familyFor(
        providerName: String?,
        customProtocolWireValue: String? = null,
    ): ThinkingProviderFamily {
        customProtocolWireValue?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { protocol ->
            return when (protocol) {
                "anthropic", "claude" -> ThinkingProviderFamily.ANTHROPIC
                "google", "gemini" -> ThinkingProviderFamily.GEMINI
                "openai" -> ThinkingProviderFamily.OPENAI_COMPATIBLE
                else -> ThinkingProviderFamily.OPENAI_COMPATIBLE
            }
        }
        return when (providerName?.trim()?.lowercase()) {
            "anthropic" -> ThinkingProviderFamily.ANTHROPIC
            "openai" -> ThinkingProviderFamily.OPENAI
            "open router", "openrouter" -> ThinkingProviderFamily.OPENROUTER
            "deepseek" -> ThinkingProviderFamily.DEEPSEEK
            "qwen" -> ThinkingProviderFamily.QWEN
            "groq" -> ThinkingProviderFamily.GROQ
            "google", "gemini" -> ThinkingProviderFamily.GEMINI
            "ollama" -> ThinkingProviderFamily.OLLAMA
            "local" -> ThinkingProviderFamily.LOCAL
            else -> ThinkingProviderFamily.OPENAI_COMPATIBLE
        }
    }

    fun forModel(
        family: ThinkingProviderFamily,
        modelId: String,
        override: ModelThinkingCapabilityOverride? = null,
    ): ModelThinkingCapability {
        val base = ModelThinkingCapabilityDefaults.forModel(family, modelId)
        return override?.takeIf { !it.isEmpty }?.applyTo(base) ?: base
    }

    fun forProvider(
        providerName: String?,
        modelId: String,
        customProtocolWireValue: String? = null,
        override: ModelThinkingCapabilityOverride? = null,
    ): ModelThinkingCapability =
        forModel(familyFor(providerName, customProtocolWireValue), modelId, override)
}
