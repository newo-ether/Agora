package com.newoether.agora.model

object OpenAiServiceTiers {
    const val AUTO = "auto"
    const val DEFAULT = "default"
    const val FLEX = "flex"
    const val FAST = "fast"
    const val ULTRAFAST = "ultrafast"

    val values = listOf(AUTO, DEFAULT, FLEX, FAST, ULTRAFAST)

    private val flexModels = setOf(
        "gpt-6-astra", "gpt-6-sol", "gpt-6-luna",
        "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna",
        "gpt-5.5", "gpt-5.5-pro",
        "gpt-5.4", "gpt-5.4-mini", "gpt-5.4-nano", "gpt-5.4-pro",
        "gpt-5.2", "gpt-5.1", "gpt-5", "gpt-5-mini", "gpt-5-nano",
        "o3", "o4-mini",
    )
    private val fastModels = setOf(
        "gpt-6-astra", "gpt-6-sol", "gpt-6-luna",
        "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna",
        "gpt-5.5", "gpt-5.4", "gpt-5.4-mini",
        "gpt-5.2", "gpt-5.1", "gpt-5", "gpt-5-mini",
        "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano",
        "gpt-4o", "gpt-4o-2024-05-13", "gpt-4o-mini",
        "o3", "o4-mini", "gpt-5.3-codex",
    )
    private val standardOnlyModels = setOf(
        "gpt-5.2-pro", "gpt-5-pro", "o1", "o1-pro", "o3-pro", "o3-mini",
        "gpt-4-turbo-2024-04-09", "gpt-4-0613", "gpt-3.5-turbo",
        "gpt-3.5-turbo-0125", "gpt-3.5-turbo-1106", "gpt-3.5-turbo-instruct",
        "davinci-002", "babbage-002",
    )
    private const val ultrafastModel = "gpt-5.6-sol"
    private val knownModels = flexModels + fastModels + standardOnlyModels

    fun normalize(value: String?): String = when (val normalized = value?.trim()?.lowercase()) {
        "scale" -> DEFAULT
        "priority" -> FAST
        else -> normalized?.takeIf { it in values } ?: AUTO
    }

    /** A relay's model id is not proof of official model-tier availability. */
    fun availableTiers(modelId: String?, officialProvider: Boolean = true): List<String> {
        if (!officialProvider) return values
        val model = modelId?.trim()?.lowercase() ?: return values
        if (model !in knownModels) return values
        return values.filter { tier ->
            when (tier) {
                FLEX -> model in flexModels
                FAST -> model in fastModels
                ULTRAFAST -> model == ultrafastModel
                else -> true
            }
        }
    }

    fun mappedTier(value: String?, modelId: String?, officialProvider: Boolean = true): String {
        val tier = normalize(value)
        val supported = availableTiers(modelId, officialProvider)
        if (tier in supported) return tier
        return when (tier) {
            FLEX -> DEFAULT
            ULTRAFAST -> if (FAST in supported) FAST else DEFAULT
            FAST -> if (ULTRAFAST in supported) ULTRAFAST else DEFAULT
            else -> DEFAULT
        }
    }

    fun indexForTier(value: String?): Int = values.indexOf(normalize(value))

    fun tierForIndex(index: Int): String = values[index.coerceIn(values.indices)]

    fun requestValue(
        enabled: Boolean,
        value: String?,
        responsesApiEnabled: Boolean,
        modelId: String? = null,
        officialProvider: Boolean = true,
    ): String? = mappedTier(value, modelId, officialProvider).takeIf { enabled && responsesApiEnabled }
}
