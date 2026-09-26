package com.newoether.agora.model

/**
 * Wire families that need different thinking parameters. A family is decided by the endpoint
 * protocol, never by the provider's display name.
 */
enum class ThinkingProviderFamily {
    ANTHROPIC,
    OPENAI,
    OPENAI_COMPATIBLE,
    OPENROUTER,
    DEEPSEEK,
    QWEN,
    GROQ,
    GEMINI,
    OLLAMA,
    LOCAL,
}

/**
 * Built-in capability defaults.
 *
 * Every entry below is taken from the provider's official API documentation; see
 * `.harness/runtime-logs/2026-09-26/provider-field-audit.md` for the source URL of each value. A
 * model id without an entry uses its family default, and an unknown family uses
 * [ModelThinkingCapability.Permissive]. Nothing here can reject a request.
 */
object ModelThinkingCapabilityDefaults {

    private val allEfforts = ThinkingLevels.effortValues

    // Anthropic: output_config.effort accepts low/medium/high/xhigh/max (no "minimal"), thinking
    // accepts enabled{budget_tokens >= 1024} / disabled / adaptive, and temperature/top_k/top_p are
    // deprecated and rejected by models released after Claude Opus 4.6.
    private val anthropic = ModelThinkingCapability(
        canDisableThinking = true,
        supportedEfforts = listOf("low", "medium", "high", "xhigh", "max"),
        supportsThinkingBudget = true,
        minBudgetTokens = 1024,
        supportsEffortWithBudget = false,
        supportsSamplingParams = false,
    )

    // An Anthropic-protocol id with no public documentation: every option stays open. Only the
    // protocol-wide budget floor (budget_tokens >= 1024) is kept.
    private val anthropicUndocumented = ModelThinkingCapability(minBudgetTokens = 1024)

    // OpenAI: ReasoningEffort = none/minimal/low/medium/high/xhigh/max. "none" is expressed by
    // turning thinking off. No token budget parameter exists.
    private val openAi = ModelThinkingCapability(
        canDisableThinking = true,
        supportedEfforts = allEfforts,
        supportsThinkingBudget = false,
    )

    // OpenRouter: reasoning.effort accepts minimal..max plus none; reasoning.max_tokens is the
    // budget form. Per-model limits are published by GET /api/v1/models.
    private val openRouter = ModelThinkingCapability(
        canDisableThinking = true,
        supportedEfforts = allEfforts,
        supportsThinkingBudget = true,
        supportsEffortWithBudget = false,
    )

    // DeepSeek: reasoning_effort accepts none/low/high/max; thinking.type toggles thinking.
    private val deepSeek = ModelThinkingCapability(
        canDisableThinking = true,
        supportedEfforts = listOf("low", "high", "max"),
        supportsThinkingBudget = false,
    )

    // Qwen/DashScope hybrid models: enable_thinking toggles thinking and thinking_budget caps the
    // chain; no effort selector.
    private val qwenHybrid = ModelThinkingCapability(
        canDisableThinking = true,
        supportedEfforts = emptyList(),
        supportsThinkingBudget = true,
        minBudgetTokens = 1,
        maxBudgetTokens = 32768,
    )

    // Qwen3.8 effort group: reasoning_effort accepts low/medium/xhigh, "none" disables thinking, and
    // reasoning_effort with thinking_budget in the same request is rejected.
    private val qwen38Effort = ModelThinkingCapability(
        canDisableThinking = true,
        supportedEfforts = listOf("low", "medium", "xhigh"),
        supportsThinkingBudget = true,
        minBudgetTokens = 1,
        maxBudgetTokens = 32768,
        supportsEffortWithBudget = false,
    )

    // Groq: reasoning_effort accepts low/medium/high for GPT-OSS and Qwen 3.8 27B; none/default are
    // Qwen-only. No token budget parameter exists.
    private val groq = ModelThinkingCapability(
        canDisableThinking = true,
        supportedEfforts = listOf("low", "medium", "high"),
        supportsThinkingBudget = false,
    )

    // Gemini: thinkingLevel is MINIMAL/LOW/MEDIUM/HIGH only; thinkingBudget is the budget form and 0
    // disables thinking.
    private val gemini = ModelThinkingCapability(
        canDisableThinking = true,
        supportedEfforts = listOf("minimal", "low", "medium", "high"),
        supportsThinkingBudget = true,
        minBudgetTokens = 1,
        supportsEffortWithBudget = false,
    )

    // Ollama: think is a boolean for every served model. Thinking levels are documented for the
    // GPT-OSS family only, which takes low/medium/high.
    private val ollama = ModelThinkingCapability(
        canDisableThinking = true,
        supportedEfforts = emptyList(),
        supportsThinkingBudget = false,
    )

    private val ollamaLevels = ollama.copy(supportedEfforts = listOf("low", "medium", "high"))

    // On-device inference exposes no reasoning parameters.
    private val local = ModelThinkingCapability(
        canDisableThinking = true,
        supportedEfforts = emptyList(),
        supportsThinkingBudget = false,
    )

    fun familyDefault(family: ThinkingProviderFamily): ModelThinkingCapability = when (family) {
        ThinkingProviderFamily.ANTHROPIC -> anthropic
        ThinkingProviderFamily.OPENAI -> openAi
        ThinkingProviderFamily.OPENAI_COMPATIBLE -> ModelThinkingCapability.Permissive
        ThinkingProviderFamily.OPENROUTER -> openRouter
        ThinkingProviderFamily.DEEPSEEK -> deepSeek
        ThinkingProviderFamily.QWEN -> qwenHybrid
        ThinkingProviderFamily.GROQ -> groq
        ThinkingProviderFamily.GEMINI -> gemini
        ThinkingProviderFamily.OLLAMA -> ollama
        ThinkingProviderFamily.LOCAL -> local
    }

    /**
     * Documented per-model refinements. Only ids whose behaviour is stated by the provider's own
     * documentation appear here; everything else keeps the family default.
     */
    fun forModel(family: ThinkingProviderFamily, modelId: String): ModelThinkingCapability {
        val model = modelId.trim().lowercase()
        return when (family) {
            ThinkingProviderFamily.ANTHROPIC -> anthropicCapability(model)
            ThinkingProviderFamily.OLLAMA -> ollamaCapability(model)
            ThinkingProviderFamily.QWEN -> qwenCapability(model)
            ThinkingProviderFamily.GROQ -> groqCapability(model)
            else -> familyDefault(family)
        }
    }

    /**
     * Claude generations differ in which thinking parameters exist at all:
     *  - 3.0/3.5 predate extended thinking and still accept temperature/top_p;
     *  - 3.7 through 4.5 take `thinking.enabled` with `budget_tokens` and no effort selector;
     *  - 4.6 adds `output_config.effort` while still accepting the sampling parameters;
     *  - later models take effort and reject temperature/top_k/top_p.
     * An id Agora has no documentation for gets every option.
     */
    private fun anthropicCapability(model: String): ModelThinkingCapability = when {
        !model.startsWith("claude") -> anthropicUndocumented

        listOf("claude-3-opus", "claude-3-sonnet", "claude-3-haiku", "claude-3-5-")
            .any { model.startsWith(it) } -> ModelThinkingCapability(
            canDisableThinking = true,
            supportedEfforts = emptyList(),
            supportsThinkingBudget = false,
            supportsSamplingParams = true,
        )

        // Checked before the dated 4.x markers so a dated 4.6 id cannot fall into the budget list.
        model.contains("4-6") || model.contains("4.6") ->
            anthropic.copy(supportsSamplingParams = true)

        listOf("claude-3-7", "-4-0", "-4-1", "-4-5", "4.0", "4.1", "4.5", "-4-2025")
            .any { model.contains(it) } -> ModelThinkingCapability(
            canDisableThinking = true,
            supportedEfforts = emptyList(),
            supportsThinkingBudget = true,
            minBudgetTokens = 1024,
            supportsEffortWithBudget = false,
            supportsSamplingParams = true,
        )

        // No public reference documents this id (for example claude-opus-5 or claude-mythos-5), so
        // every option stays available and the user decides what the endpoint accepts.
        else -> anthropicUndocumented
    }

    private fun qwenCapability(model: String): ModelThinkingCapability = when {
        // Effort group documented together in the compatible-mode reference.
        model == "qwen3.8-max" || model.startsWith("qwen3.8-max-") ||
            model == "qwen3.8-flash" || model.startsWith("qwen3.8-flash-") ||
            model == "qwen3.8-27b" || model == "qwen3.8-2.4t-a95b" -> qwen38Effort

        // glm-5.3 always thinks (enable_thinking must be true) and ignores thinking_budget;
        // reasoning_effort accepts low/high/max with max as the default.
        model.startsWith("glm-5.3") || model.startsWith("zhipu/glm-5.3") ->
            ModelThinkingCapability(
                canDisableThinking = false,
                supportedEfforts = listOf("low", "high", "max"),
                supportsThinkingBudget = false,
            )

        // DeepSeek and Kimi models served through DashScope expose the DeepSeek effort set.
        model.startsWith("deepseek-v4") || model.startsWith("kimi/kimi-k3") ||
            model == "kimi-k3" ->
            deepSeek.copy(supportsThinkingBudget = true, maxBudgetTokens = 32768)

        // Documented thinking-only Qwen snapshots: enable_thinking cannot be false.
        model in qwenThinkingOnlyModels || model.startsWith("qwq-plus-") ->
            qwenHybrid.copy(canDisableThinking = false)

        // Qwen hybrid series (qwen-plus, qwen3.6-plus, ...): documented enable_thinking toggle
        // plus thinking_budget.
        model.startsWith("qwen") -> qwenHybrid

        // A third-party id served through DashScope with no documentation: offer every option
        // and let the user decide.
        else -> ModelThinkingCapability.Permissive
    }

    private val qwenThinkingOnlyModels = setOf(
        "qwen3.7-max-preview", "qwen3.7-max-2026-05-17", "qwen3-next-80b-a3b-thinking",
        "qwen3-235b-a22b-thinking-2507", "qwen3-30b-a3b-thinking-2507", "qwq-plus",
    )

    /** GPT-OSS is the documented Ollama family with thinking levels; every other model is boolean. */
    private fun ollamaCapability(model: String): ModelThinkingCapability {
        val bare = model.substringAfterLast('/').substringBefore(':')
        return if (bare == "gpt-oss") ollamaLevels else ollama
    }

    private fun groqCapability(model: String): ModelThinkingCapability = when {
        // GPT-OSS does not accept reasoning_effort "none"; reasoning is hidden with
        // include_reasoning=false instead of being turned off.
        model.startsWith("openai/gpt-oss") -> groq.copy(canDisableThinking = false)

        // Qwen 3.6 27B documents exactly one enabled value, "default", plus "none" to turn
        // reasoning off.
        model == "qwen/qwen3.6-27b" -> groq.copy(supportedEfforts = listOf("default"))

        // Qwen 3.8 27B documents low/medium/high plus "none".
        model == "qwen/qwen3.8-27b" -> groq

        // Undocumented id: every effort level and the off switch stay available. Groq has no
        // token-budget parameter at all, so a budget control would send nothing.
        else -> ModelThinkingCapability.Permissive.copy(supportsThinkingBudget = false)
    }
}
