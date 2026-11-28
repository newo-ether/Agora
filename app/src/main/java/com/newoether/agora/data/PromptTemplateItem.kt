package com.newoether.agora.data

import com.newoether.agora.util.Constants
import kotlinx.serialization.Serializable
import java.util.UUID

enum class PromptItemType { CUSTOM, PREDEFINED }

@Serializable
data class PromptTemplateItem(
    val id: String = UUID.randomUUID().toString(),
    val type: PromptItemType,
    val value: String
)

data class MessageTemplateParts(
    val beforePrompt: List<PromptTemplateItem>,
    val afterPrompt: List<PromptTemplateItem>,
)

object PredefinedVariables {
    const val TIME = "time"
    const val DATE = "date"
    const val SENT_TIME = "sent_time"
    const val SENT_DATE = "sent_date"
    const val SENT_DATE_PATTERN = "yyyy-MM-dd EEE"
    const val ACTIVE_MEMORY = "active_memory"
    const val SKILL_CATALOG = "skill_catalog"
    const val CURRENT_MODEL_ID = "current_model_id"
    const val MESSAGE_MODEL_ID = "message_model_id"
    // Legacy alias retained for saved templates. New templates use CURRENT_MODEL_ID.
    const val MODEL_ID = "model_id"
    const val PROMPT = "prompt"

    // Ordered for the variable picker. Prompt and the legacy model alias are structural-only.
    val ALL = listOf(
        TIME,
        DATE,
        SENT_TIME,
        SENT_DATE,
        ACTIVE_MEMORY,
        SKILL_CATALOG,
        CURRENT_MODEL_ID,
        MESSAGE_MODEL_ID,
    )

    // Placeholders resolved from each durable message rather than once per request.
    val PER_MESSAGE_VARS = setOf(SENT_TIME, SENT_DATE, MESSAGE_MODEL_ID)

    val EXAMPLE_VALUES = mapOf(
        TIME to "14:30:00",
        DATE to "2026-05-10",
        SENT_TIME to "10:05:00",
        SENT_DATE to "2026-05-09 Sat",
        ACTIVE_MEMORY to "[Example memory content]",
        SKILL_CATALOG to "- example.md: Example skill description",
        CURRENT_MODEL_ID to Constants.EXAMPLE_MODEL_ID,
        MESSAGE_MODEL_ID to "gpt-4.1",
        MODEL_ID to Constants.EXAMPLE_MODEL_ID,
        PROMPT to "[Prompt]",
    )

    // Read-only compatibility for items saved by the retired conditional-guide default.
    private val RETIRED_TOOL_GUIDES = setOf(
        "tool_guide_memory", "tool_guide_skills", "tool_guide_past_conversations", "tool_guide_web",
        "tool_guide_devices", "tool_guide_subagents_and_automation", "tool_guide_asking_the_user",
    )

    fun compile(
        items: List<PromptTemplateItem>,
        runtimeValues: Map<String, String>,
        exampleValues: Map<String, String> = EXAMPLE_VALUES
    ): String {
        return items.joinToString("") { item ->
            when (item.type) {
                PromptItemType.CUSTOM -> item.value
                PromptItemType.PREDEFINED -> if (item.value in RETIRED_TOOL_GUIDES) "" else
                    runtimeValues[item.value] ?: exampleValues[item.value] ?: "{${item.value}}"
            }
        }
    }

    fun promptItem(): PromptTemplateItem = PromptTemplateItem(
        type = PromptItemType.PREDEFINED,
        value = PROMPT,
    )

    fun isPromptItem(item: PromptTemplateItem): Boolean =
        item.type == PromptItemType.PREDEFINED && item.value == PROMPT

    fun normalizeMessageTemplate(items: List<PromptTemplateItem>): List<PromptTemplateItem> {
        var promptFound = false
        val normalized = items.filter { item ->
            if (!isPromptItem(item)) {
                true
            } else if (!promptFound) {
                promptFound = true
                true
            } else {
                false
            }
        }
        return if (promptFound) normalized else normalized + promptItem()
    }

    fun splitMessageTemplate(items: List<PromptTemplateItem>): MessageTemplateParts {
        val normalized = normalizeMessageTemplate(items)
        val promptIndex = normalized.indexOfFirst(::isPromptItem)
        return MessageTemplateParts(
            beforePrompt = normalized.take(promptIndex),
            afterPrompt = normalized.drop(promptIndex + 1),
        )
    }
}
