package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.*

/** Pure request projection. Permissions remain owned by the existing providers.
 * Activation is reconstructed from completed selected-path results, never executor-global state.
 */
internal object ToolCapabilityLoading {
    const val NAME = "load_tool_capability"

    private val summaries = linkedMapOf(
        "interactive" to "generate interactive HTML charts, calculators and simulations",
        "templates" to "find and open saved interactive templates",
        "memory" to "read or manage saved and active memory",
        "skills" to "read or manage saved instructions",
        "conversations" to "search and read past chats",
        "web" to "search the web and read pages",
        "questions" to "ask structured questions with selectable answers",
    )

    private fun category(name: String): String? = when (name) {
        "render_interactive_answer" -> "interactive"
        "list_interactive_templates", "open_interactive_template" -> "templates"
        "list_memory_files", "read_memory_file", "create_memory_file", "edit_memory_file",
        "delete_memory_file", "read_active_memory", "update_active_memory" -> "memory"
        "invoke_skill", "complete_skill", "propose_skill", "list_skill_files", "read_skill_file", "create_skill_file", "edit_skill_file",
        "delete_skill_file" -> "skills"
        "search_conversations", "list_conversations", "read_conversation" -> "conversations"
        "web_search", "web_fetch" -> "web"
        "ask_user" -> "questions"
        else -> null // Other providers retain their established visibility policy.
    }

    fun definitions(
        available: List<ToolDefinition>,
        context: GenerationContext,
        messages: List<ChatMessage>,
    ): List<ToolDefinition> {
        val deferred = available.mapNotNull { category(it.function.name) }.toSet()
        if (deferred.isEmpty()) return available
        // A fresh user input resets discovery. Prior turns and sibling branches cannot load tools.
        val anchor = messages.indexOfLast { it.participant == Participant.USER && it.toolCall == null }
        val turn = if (anchor >= 0) messages.drop(anchor) else emptyList()
        val active = mutableSetOf<String>()
        turn.filter { it.participant == Participant.USER && it.status == MessageStatus.SUCCESS }
            .mapNotNull { it.toolCall }
            .filter { it.toolName == NAME }
            .forEach { call ->
                // Match the validated result with the requested category; error results never load.
                val requested = runCatching {
                    Json.parseToJsonElement(call.arguments).jsonObject["capability"]?.jsonPrimitive?.content
                }.getOrNull()
                val loaded = runCatching {
                    Json.parseToJsonElement(call.result).jsonObject["loaded_capability"]?.jsonPrimitive?.content
                }.getOrNull()
                if (requested != null && requested == loaded && requested in deferred) active += requested
            }
        val discovery = ToolDefinition(function = ToolFunction(
            name = NAME,
            description = "Load a capability's tools for this user turn before using them. " +
                "Available: " + summaries.filterKeys { it in deferred }.entries.joinToString("; ") {
                    "${it.key}: ${it.value}"
                } + ". After success, call the newly available tool. Do not ask the user to enable it.",
            parameters = ToolParameters(
                properties = mapOf("capability" to ToolProperty("string", "One available category name, exactly as listed.")),
                required = listOf("capability"),
            ),
        ))
        return listOf(discovery) + available.filter {
            val category = category(it.function.name)
            category == null || category in active
        }
    }

    fun load(arguments: String, available: List<ToolDefinition>): ToolExecutionResult {
        val category = runCatching {
            val input = Json.parseToJsonElement(arguments).jsonObject
            require(input.keys == setOf("capability"))
            input.getValue("capability").jsonPrimitive.content
        }.getOrNull()
        val tools = available.filter { category(it.function.name) == category }
        if (category !in summaries || tools.isEmpty()) return ToolExecutionResult(
            text = "Capability unavailable. Choose an enabled category listed by load_tool_capability.",
            isError = true,
        )
        return ToolExecutionResult(text = buildJsonObject {
            put("loaded_capability", category)
            putJsonArray("tools") { tools.forEach { add(it.function.name) } }
            put("next", "These tools are now available. Call the appropriate tool to complete the request.")
        }.toString())
    }
}
