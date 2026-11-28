package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.readConversationTodo
import com.newoether.agora.data.repository.updateConversationTodo
import com.newoether.agora.model.ConversationTodoItem
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

/** Uses the ordinary tool executor; Room fences mutations to the authorized live Run/pass. */
class TodoToolProvider(private val conversations: ConversationRepository) : ToolProvider {
    override fun handles(name: String) = BuiltInToolNames.canonical(name) in setOf("todo_read", "todo_update")

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.todoEnabled || ctx.conversationId.isNullOrBlank()) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "todo_read",
                description = "Read this conversation's current todo list. It is shared across branches and survives restarts.",
                parameters = ToolParameters(properties = emptyMap()),
            )),
            ToolDefinition(function = ToolFunction(
                name = "todo_update",
                description = "Replace this conversation's todo list with the complete updated list. Use pending, in_progress or completed for each status. An empty todos array clears the list. This does not create scheduled tasks.",
                parameters = ToolParameters(
                    properties = mapOf("todos" to ToolProperty(
                        type = "array", description = "The complete todo list in display order.",
                        items = ToolProperty(
                            type = "object", description = "One task and its current status.", properties = mapOf(
                                "content" to ToolProperty("string", "Nonblank task description."),
                                "status" to ToolProperty("string", "One of: pending, in_progress, completed."),
                            ), required = listOf("content", "status"),
                        ),
                    )),
                    required = listOf("todos"),
                ),
            )),
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        executeOneShotResult(name, arguments, ctx).text

    override suspend fun executeOneShotResult(
        name: String, arguments: String, ctx: GenerationContext,
    ): ToolExecutionResult {
        require(handles(name)) { "Unknown todo tool: $name" }
        require(ctx.todoEnabled) { "Todo tools are disabled" }
        val id = requireNotNull(ctx.conversationId?.takeIf(String::isNotBlank)) { "No conversation is open" }
        val items = if (BuiltInToolNames.canonical(name) == "todo_read") {
            conversations.readConversationTodo(id)
        } else {
            val identity = requireNotNull(ctx.toolEffectIdentity) { "Missing authorized tool identity" }
            require(identity.conversationId == id) { "Todo conversation identity mismatch" }
            val values = Json.parseToJsonElement(arguments).jsonObject["todos"] as? JsonArray
                ?: error("todos must be an array")
            val updated = Json.decodeFromString<List<ConversationTodoItem>>(values.toString())
            check(conversations.updateConversationTodo(identity, updated)) { "Todo update rejected: Run/pass is no longer active" }
            updated
        }
        val json = Json.encodeToString(items)
        return ToolExecutionResult(text = json, structuredContent = json)
    }
}
