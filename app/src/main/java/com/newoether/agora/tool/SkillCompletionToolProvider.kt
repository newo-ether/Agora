package com.newoether.agora.tool

import com.newoether.agora.api.*
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.skills.*
import com.newoether.agora.templates.*
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Explicit completion through the ordinary authorized tool batch, not a Run-settlement hook. */
internal class SkillCompletionToolProvider(
    private val templates: InteractiveTemplateRepository,
    private val sandboxFactory: SandboxManagerFactory?,
    private val label: String = "Finish Skill",
) : ToolProvider {
    override fun handles(name: String) = name == "complete_skill"
    override fun presentationMetadata(name: String) = if (handles(name)) ToolPresentationMetadata(label) else null
    override fun definitions(ctx: GenerationContext) = if (!ctx.skillReadAccess) emptyList() else listOf(ToolDefinition(function = ToolFunction(
        name = "complete_skill", description = "Signal that an invoked Skill's entire workflow is finished, not merely one reply. " +
            "Use its invocation token. The app presents its configured review interface. Wait for the user's Continue; never start the next Skill yourself.",
        parameters = ToolParameters(properties = mapOf(
            "token" to ToolProperty("string", "The actual completed invoke_skill token."),
            "summary" to ToolProperty("string", "Concise outcome and data for the next workflow step."),
        ), required = listOf("token", "summary")))))
    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String = error("Authorized history required")
    suspend fun complete(arguments: String, ctx: GenerationContext, calls: List<ToolCallData>): ToolExecutionResult = withContext(Dispatchers.IO) {
        require(ctx.skillReadAccess) { "Skill access is disabled" }
        val args = Json.parseToJsonElement(arguments).jsonObject
        val token = args["token"]?.jsonPrimitive?.content.orEmpty()
        val summary = args["summary"]?.jsonPrimitive?.content.orEmpty()
        require(summary.isNotBlank() && summary.length <= 3000) { "Provide a bounded completion summary" }
        val frame = SkillInvocation.history(calls).singleOrNull { it.token == token } ?: error("Unknown Skill invocation")
        require(calls.none { it.toolName == "complete_skill" && SkillCompletionReceipt.token(it.structuredResult) == token }) { "Skill completion already presented" }
        val document = SkillDocument.decode(frame.name, frame.document)
        val id = document.configuration.afterSteps.firstOrNull() ?: error("This Skill has no After template")
        val flow = SkillTemplateFlow(frame.name, "after", summary, document.configuration.nextSkill, frame.token, frame.chain,
            remainingTemplates = document.configuration.afterSteps.drop(1))
        flow.nextSkill?.let(flow::checkTarget)
        val template = bindSkillTemplate(TemplateSources(sandboxFactory?.create(), ctx, templates.androidSources).prepare(templates.get(id)), flow)
        val payload = template.encode()
        require(payload.toByteArray().size <= InteractiveAnswerToolProvider.MAX_PERSISTED_BYTES) { "Review template exceeds tool persistence budget" }
        ToolExecutionResult(text = "Skill finished: ${frame.name}. Review interface ready; wait for user input.",
            displayText = summary, structuredContent = payload)
    }
}
