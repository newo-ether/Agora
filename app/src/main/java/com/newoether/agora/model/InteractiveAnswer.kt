package com.newoether.agora.model

import com.newoether.agora.templates.interactiveTemplate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A completed tool artifact stored in the existing structured result, not a second message graph. */
@Serializable
data class InteractiveAnswer(
    val title: String,
    val description: String,
    val fallback: String,
    val css: String = "",
    val html: String,
    val jsFunctions: String = "",
    val jsExpressions: List<String> = emptyList(),
    val kind: String = KIND,
    val version: Int = 1,
    val eInkAware: Boolean = false,
    val skillDraft: com.newoether.agora.skills.SkillDocument? = null,
) {
    fun validate(): InteractiveAnswer {
        require(kind == KIND && version == 1) { "Unsupported interactive answer format" }
        require(title.isNotBlank() && title.length <= 200) { "Title must contain 1–200 characters" }
        require(description.isNotBlank() && description.length <= 2000) { "Description must contain 1–2000 characters" }
        require(fallback.isNotBlank()) { "A readable fallback is required" }
        skillDraft?.encode()
        require(html.isNotBlank()) { "HTML is required" }
        require(jsExpressions.size <= 100) { "Too many JavaScript expressions" }
        require(encode().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Interactive answer exceeds 1 MiB" }
        return this
    }

    fun encode(): String = Json.encodeToString(this)

    companion object {
        const val KIND = "agora.interactive-answer"
        const val TOOL_NAME = "render_interactive_answer"
        const val MAX_BYTES = 1024 * 1024

        fun decode(payload: String): InteractiveAnswer {
            require(payload.length <= MAX_BYTES && payload.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
                "Interactive answer exceeds 1 MiB"
            }
            return Json.decodeFromString<InteractiveAnswer>(payload).validate()
        }
    }
}

/** Only completed interactive tool results or validated local template snapshots are executable. */
fun MessageSegment.interactiveAnswer(): InteractiveAnswer? {
    interactiveTemplate()?.let { return it.answer }
    if (type != "tool" || toolName !in setOf(InteractiveAnswer.TOOL_NAME, "propose_skill") || toolState != ToolExecutionStates.SUCCEEDED) return null
    return toolStructuredResult?.let { runCatching { InteractiveAnswer.decode(it).takeIf { answer ->
        (toolName == "propose_skill") == (answer.skillDraft != null)
    } }.getOrNull() }
}
