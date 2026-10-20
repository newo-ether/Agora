package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.templates.InteractiveTemplateRepository
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Opens user-owned snapshots; never generates interface code or modifies the library. */
class InteractiveTemplateToolProvider(
    private val templates: InteractiveTemplateRepository,
    private val sandboxFactory: com.newoether.agora.sandbox.SandboxManagerFactory? = null,
) : ToolProvider {
    override fun handles(name: String): Boolean = name == LIST || name == OPEN

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.interactiveAnswersEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = LIST,
                description = "List saved interactive templates (metadata only).",
                parameters = ToolParameters(properties = mapOf(
                    "query" to ToolProperty("string", "Optional name/description filter"),
                    "limit" to ToolProperty("integer", "Maximum rows, default 50; range 1–100"),
                )),
            )),
            ToolDefinition(function = ToolFunction(
                name = OPEN,
                description = "Open a saved interactive template unchanged in chat. " +
                    "Use an ID from list_interactive_templates.",
                parameters = ToolParameters(
                    properties = mapOf("template_id" to ToolProperty("string", "Saved template ID")),
                    required = listOf("template_id"),
                ),
            )),
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        executeOneShotResult(name, arguments, ctx).text

    override suspend fun executeOneShotResult(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        require(ctx.interactiveAnswersEnabled && handles(name)) { "Interactive templates are disabled" }
        val args = Json.parseToJsonElement(arguments).jsonObject
        if (name == LIST) {
            list(args)
        } else {
            val id = args["template_id"]?.jsonPrimitive?.contentOrNull ?: error("Missing template_id")
            val template = com.newoether.agora.templates.TemplateSources(sandboxFactory?.create(), ctx, templates.androidSources).prepare(templates.get(id))
            ToolExecutionResult(
                text = "Opened template: ${template.name}\n${template.answer.fallback}",
                displayText = template.answer.fallback,
                structuredContent = template.encode(),
            )
        }
    }

    private fun list(args: JsonObject): ToolExecutionResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 50
        require(limit in 1..100) { "limit must be 1–100" }
        val matches = templates.list()
            .filter { it.name.contains(query, true) || it.description.contains(query, true) }
            .take(limit)
        return ToolExecutionResult(buildJsonArray {
            matches.forEach { template ->
                add(buildJsonObject {
                    put("id", template.id)
                    put("name", template.name)
                    // Bound metadata so a large library cannot overflow normal tool-result capture.
                    put("description", template.description.take(200))
                    template.shortcut?.let { put("shortcut", "/$it") }
                })
            }
        }.toString())
    }

    companion object {
        const val LIST = "list_interactive_templates"
        const val OPEN = "open_interactive_template"
    }
}
