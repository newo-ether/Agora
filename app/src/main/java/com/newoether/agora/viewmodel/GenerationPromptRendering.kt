package com.newoether.agora.viewmodel

import com.newoether.agora.data.PredefinedVariables
import com.newoether.agora.data.PromptTemplateItem
import com.newoether.agora.model.ModelId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun buildPromptRuntimeValues(
    now: java.util.Date,
    modelId: String,
    activeMemory: String,
    skillCatalog: String,
): Map<String, String> {
    val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    val sentDateFormat = java.text.SimpleDateFormat(
        PredefinedVariables.SENT_DATE_PATTERN,
        java.util.Locale.US,
    )
    return mapOf(
        PredefinedVariables.TIME to timeFormat.format(now),
        PredefinedVariables.DATE to dateFormat.format(now),
        PredefinedVariables.SENT_TIME to timeFormat.format(now),
        PredefinedVariables.SENT_DATE to sentDateFormat.format(now),
        PredefinedVariables.CURRENT_MODEL_ID to modelId,
        PredefinedVariables.MESSAGE_MODEL_ID to "",
        PredefinedVariables.MODEL_ID to modelId,
        PredefinedVariables.ACTIVE_MEMORY to activeMemory,
        PredefinedVariables.SKILL_CATALOG to skillCatalog,
    )
}

/** Shared compilation for dispatch and context preview; never changes stored template items. */
internal suspend fun resolveGenerationPromptTemplate(
    promptTemplate: GenerationPromptTemplate,
    modelId: String,
): GenerationRequestBuilder.ResolvedPrompt = withContext(Dispatchers.Default) {
    val runtimeValues = buildPromptRuntimeValues(
        now = java.util.Date(),
        modelId = ModelId.parse(modelId).modelName,
        activeMemory = promptTemplate.activeMemory,
        skillCatalog = promptTemplate.skillCatalog,
    )
    val perMessageValues = runtimeValues.filterKeys {
        it !in PredefinedVariables.PER_MESSAGE_VARS
    }
    fun resolveMessageTemplate(items: List<PromptTemplateItem>): Pair<String?, String?> {
        val parts = PredefinedVariables.splitMessageTemplate(items)
        val before = PredefinedVariables.compile(
            parts.beforePrompt, perMessageValues, emptyMap(),
        ).takeIf(String::isNotEmpty)
        val after = PredefinedVariables.compile(
            parts.afterPrompt, perMessageValues, emptyMap(),
        ).takeIf(String::isNotEmpty)
        return before to after
    }
    val userTemplate = resolveMessageTemplate(promptTemplate.userItems)
    val assistantTemplate = resolveMessageTemplate(promptTemplate.assistantItems)
    GenerationRequestBuilder.ResolvedPrompt(
        systemPrompt = PredefinedVariables.compile(
            promptTemplate.systemItems, runtimeValues, emptyMap(),
        ).takeIf(String::isNotEmpty),
        userPrepend = userTemplate.first,
        userPostpend = userTemplate.second,
        assistantPrepend = assistantTemplate.first,
        assistantPostpend = assistantTemplate.second,
    )
}
