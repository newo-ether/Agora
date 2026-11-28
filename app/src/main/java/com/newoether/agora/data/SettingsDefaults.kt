package com.newoether.agora.data

import java.util.Locale

internal const val DEFAULT_CONTEXT_COMPACT_ENABLED = true
internal const val DEFAULT_CONTEXT_COMPACT_RETAIN_COUNT = 0
internal const val DEFAULT_CONTEXT_COMPACT_PRESERVE_SYSTEM_PROMPT = true
internal const val DEFAULT_CONTEXT_COMPACT_THRESHOLD_PERCENT = 90
internal val CONTEXT_COMPACT_THRESHOLD_PERCENT_RANGE = 50..100
internal const val DEFAULT_LOCAL_MODEL_IDLE_RETENTION_MINUTES = 5
internal const val DEFAULT_LOCAL_LOW_CONTEXT_MODE_ENABLED = true
internal val LOCAL_MODEL_IDLE_RETENTION_PRESETS = intArrayOf(0, 1, 2, 5, 10, 15, 30)
// Decode is bandwidth-bound and spin-wait contention on heterogeneous SoCs makes 8 threads slower
// than 4, so the default stays conservative; the slider is there for devices that scale further.
internal const val DEFAULT_LOCAL_INFERENCE_THREADS = 4
internal val LOCAL_INFERENCE_THREAD_PRESETS = intArrayOf(1, 2, 4, 6, 8)
internal const val DEFAULT_LOCAL_KV_CACHE_TYPE = com.newoether.agora.util.Constants.KV_CACHE_TYPE_Q8_0
internal val LOCAL_KV_CACHE_TYPES = listOf(
    com.newoether.agora.util.Constants.KV_CACHE_TYPE_F16,
    com.newoether.agora.util.Constants.KV_CACHE_TYPE_Q8_0,
    com.newoether.agora.util.Constants.KV_CACHE_TYPE_Q4_0,
)

internal fun normalizeLocalModelIdleRetentionMinutes(value: Int?): Int =
    value?.takeIf { it in LOCAL_MODEL_IDLE_RETENTION_PRESETS }
        ?: DEFAULT_LOCAL_MODEL_IDLE_RETENTION_MINUTES

internal fun normalizeLocalInferenceThreads(value: Int?): Int =
    value?.takeIf { it in LOCAL_INFERENCE_THREAD_PRESETS } ?: DEFAULT_LOCAL_INFERENCE_THREADS

internal fun normalizeLocalKvCacheType(value: String?): String =
    value?.takeIf { it in LOCAL_KV_CACHE_TYPES } ?: DEFAULT_LOCAL_KV_CACHE_TYPE

internal fun migrateUnmodifiedBuiltInDefault(
    prompts: List<SystemPromptEntry>,
    locale: Locale,
): List<SystemPromptEntry> {
    if (prompts.isEmpty()) return prompts
    val currentDefault = DefaultSystemPrompt.create(locale)
    return prompts.map { entry ->
        if (DefaultSystemPrompt.isUnmodifiedPreviousVersion(entry)) {
            entry.copy(
                content = "",
                systemItems = currentDefault.systemItems,
                userItems = currentDefault.resolvedUserItems,
                assistantItems = currentDefault.resolvedAssistantItems,
                userPrependItems = emptyList(),
                userPostpendItems = emptyList(),
                builtInMigrationVersion = DefaultSystemPrompt.BUILT_IN_MIGRATION_VERSION,
            )
        } else if (DefaultSystemPrompt.isUnmergedDefault(entry)) {
            // Same text, split into more items than the shipped default now uses.
            entry.copy(systemItems = currentDefault.systemItems)
        } else {
            entry
        }
    }
}

