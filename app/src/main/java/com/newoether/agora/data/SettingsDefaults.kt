package com.newoether.agora.data

import java.util.Locale

internal const val DEFAULT_CONTEXT_COMPACT_ENABLED = true
internal const val DEFAULT_CONTEXT_COMPACT_RETAIN_COUNT = 0
internal const val DEFAULT_CONTEXT_COMPACT_PRESERVE_SYSTEM_PROMPT = true
internal const val DEFAULT_CONTEXT_COMPACT_THRESHOLD_PERCENT = 90
internal val CONTEXT_COMPACT_THRESHOLD_PERCENT_RANGE = 50..100
internal const val DEFAULT_LOCAL_MODEL_IDLE_RETENTION_MINUTES = 5
internal const val DEFAULT_LOCAL_LOW_CONTEXT_MODE_ENABLED = false
internal val LOCAL_MODEL_IDLE_RETENTION_PRESETS = intArrayOf(0, 1, 2, 5, 10, 15, 30)

internal fun normalizeLocalModelIdleRetentionMinutes(value: Int?): Int =
    value?.takeIf { it in LOCAL_MODEL_IDLE_RETENTION_PRESETS }
        ?: DEFAULT_LOCAL_MODEL_IDLE_RETENTION_MINUTES

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
        } else {
            entry
        }
    }
}

