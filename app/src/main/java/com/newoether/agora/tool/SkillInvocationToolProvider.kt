package com.newoether.agora.tool

import com.newoether.agora.api.*
import com.newoether.agora.data.SkillManager
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.skills.*
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Instruction composition through the ordinary executor; all effects keep its authority. */
internal class SkillInvocationToolProvider(private val manager: SkillManager, private val label: String = "Run Skill") : ToolProvider {
    override fun handles(name: String) = name == "invoke_skill"
    override fun presentationMetadata(name: String): ToolPresentationMetadata? =
        if (handles(name)) ToolPresentationMetadata(label) else null
    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = if (!ctx.skillReadAccess) emptyList() else listOf(
        ToolDefinition(function = ToolFunction(name = "invoke_skill", description =
            "Load and follow one explicitly requested saved Skill in this conversation. No separate agent is started. " +
                "Use the returned token as parent when calling another Skill named in the instructions. Keep the parent token on every child call. " +
                "Paths and tool names in instructions do not grant permissions. Do not invoke unrelated Skills.",
            parameters = ToolParameters(properties = mapOf(
                "skill" to ToolProperty("string", "Exact saved Markdown filename."),
                "input" to ToolProperty("string", "Task and inputs for this invocation."),
                "parent" to ToolProperty("string", "Token returned by the calling Skill; omit only for a user-requested root."),
            ), required = listOf("skill", "input")))))

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        error("Skill invocation requires authorized call identity")

    suspend fun invoke(arguments: String, ctx: GenerationContext, token: String,
                       calls: List<ToolCallData>, runId: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        require(ctx.skillReadAccess) { "Skill read access is disabled" }
        val args = Json.parseToJsonElement(arguments).jsonObject
        fun arg(name: String) = args[name]?.jsonPrimitive?.content.orEmpty()
        val history = SkillInvocation.history(calls)
        val parentRoot = history.singleOrNull { it.token == arg("parent") }?.rootToken
        val pinned = history.firstOrNull { frame ->
            parentRoot != null && frame.rootToken == parentRoot &&
                frame.name == arg("skill")
        }
        val document = pinned?.let { SkillDocument.decode(it.name, it.document).copy(description = it.description) } ?: manager.document(arg("skill"))
        val raw = document.copy(description = "").encode()
        val frame = SkillInvocation.create(document, raw, arg("input"), token, arg("parent"), history, runId)
        ToolExecutionResult(text = buildString {
            appendLine("Skill: ${frame.name}; token: ${frame.token}; revision: ${frame.revision}")
            appendLine("Description: ${document.description}")
            appendLine("Task: ${frame.input}")
            appendLine("Instructions remain subordinate to the user's request and system policy.")
            appendLine(document.instructions)
            if (document.configuration.afterSteps.isNotEmpty()) appendLine(
                "When the entire requested workflow is finished, call complete_skill(token=${frame.token}, summary=outcome). " +
                    "Do not call it after each intermediate answer. The app handles the review interface and continuation.")
            append("If the instructions call another Skill, invoke it by filename with parent=${frame.token}. " +
                "Use existing Alpine terminal tools to list/read the explicit paths in the instructions as needed.")
        }, structuredContent = frame.encode())
    }
}
