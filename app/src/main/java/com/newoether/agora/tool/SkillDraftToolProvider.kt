package com.newoether.agora.tool

import com.newoether.agora.api.*
import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.skills.*
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.*

/** A reviewable artifact in the existing viewer, never a library mutation by the model. */
internal class SkillDraftToolProvider(private val label: String = "Draft Skill") : ToolProvider {
    override fun presentationMetadata(name: String): ToolPresentationMetadata? =
        if (handles(name)) ToolPresentationMetadata(label) else null
    override fun handles(name: String) = name == "propose_skill"
    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        if (!ctx.skillModifyAccess) emptyList() else listOf(ToolDefinition(function = ToolFunction(
            name = "propose_skill",
            description = "Draft a reusable Skill from this discussion when requested. First invoke create-skill.md for authoring guidance. " +
                "Shows a review card in the existing interactive viewer; does not save or overwrite anything. " +
                "User expands it, edits if needed, then saves. Put tools, call examples, Alpine paths and child Skill filenames in instructions.",
            parameters = ToolParameters(properties = linkedMapOf(
                "name" to ToolProperty("string", "Concise, content-descriptive Markdown filename."),
                "description" to ToolProperty("string", "Five to ten words describing the workflow."),
                "shortcut" to ToolProperty("string", "Optional slash shortcut without /. Empty if unnecessary."),
                "instructions" to ToolProperty("string", "Complete reusable Markdown instructions."),
            ), required = listOf("name", "description", "instructions")))))

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        executeOneShotResult(name, arguments, ctx).text

    override suspend fun executeOneShotResult(name: String, arguments: String, ctx: GenerationContext): ToolExecutionResult {
        require(handles(name) && ctx.skillModifyAccess) { "Skill modify access is disabled" }
        require(arguments.toByteArray().size <= InteractiveAnswerToolProvider.MAX_PERSISTED_BYTES) { "Skill draft is too large" }
        val args = Json.parseToJsonElement(arguments).jsonObject
        fun arg(key: String) = args[key]?.jsonPrimitive?.content.orEmpty()
        val draft = SkillDocument(arg("name").trim(), arg("instructions"),
            SkillConfiguration(arg("shortcut").trim().removePrefix("/")), arg("description").trim())
        require(draft.name.isNotBlank() && !draft.name.any { it == '/' || it == '\\' } && draft.instructions.isNotBlank()) { "Provide a filename and instructions" }
        draft.encode()
        val fallback = buildString {
            appendLine(draft.name); appendLine(draft.description)
            if (draft.configuration.shortcut.isNotEmpty()) appendLine("/${draft.configuration.shortcut}")
            appendLine(); append(draft.instructions)
        }
        val html = buildString {
            append("<article><h2>"); append(escape(draft.name)); append("</h2><p>")
            append(escape(draft.description)); append("</p>")
            if (draft.configuration.shortcut.isNotEmpty()) append("<p>/" + escape(draft.configuration.shortcut) + "</p>")
            append("<pre style=\"white-space:pre-wrap;overflow-wrap:anywhere\">")
            append(escape(draft.instructions)); append("</pre></article>")
        }
        val answer = InteractiveAnswer(draft.name, draft.description, fallback, html = html,
            eInkAware = ctx.eInkMode, skillDraft = draft).validate()
        require(answer.encode().toByteArray().size <= InteractiveAnswerToolProvider.MAX_PERSISTED_BYTES) { "Skill draft exceeds tool persistence budget" }
        return ToolExecutionResult(text = fallback, displayText = fallback, structuredContent = answer.encode())
    }

    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}
