package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.automation.SubagentManager
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Delegation is restricted to a currently active root conversation's exact tool identity. */
class SubagentToolProvider(private val manager: SubagentManager) : ToolProvider {
    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.subagentToolsEnabled) return emptyList()
        val models = ctx.subagentModelIds.orEmpty()
        val recommendation = ctx.subagentRecommendedModel?.takeIf { it in models }?.let {
            " Recommended model ID: ${Json.encodeToString(it)} (hint only; model_id is still required and may select any available ID)."
        }.orEmpty()
        return listOfNotNull(
            definition("agent_spawn", "Start an isolated subagent asynchronously. It receives only task, never this conversation's history. Available model IDs: ${Json.encodeToString(models)}$recommendation", mapOf(
                "name" to ToolProperty("string", "Short subagent name."),
                "model_id" to ToolProperty("string", "One exact available model ID; required even when a recommendation is present."),
                "task" to ToolProperty("string", "Complete task and all necessary context."),
            ), listOf("name", "model_id", "task")),
            definition("agent_send_message", "Queue a follow-up for an idle or busy subagent belonging to this conversation. Returns a queued item receipt; does not interrupt its active Provider or tool pass.", mapOf(
                "agent_id" to ToolProperty("string", "Subagent conversation ID."),
                "message" to ToolProperty("string", "Complete follow-up instruction."),
            ), listOf("agent_id", "message")),
            definition("agent_wait", "Wait for selected subagents and return their undelivered terminal results. Returned results are not automatically notified again. Failed results include their persisted error detail separately from generated text when available.", mapOf(
                "agent_ids" to ToolProperty("array", "Subagent IDs; omit to wait for all.", items = ToolProperty("string", "Subagent ID")),
                "timeout_ms" to ToolProperty("integer", "Wait budget, 1 to 300000 milliseconds; default 300000."),
            )),
            definition("agent_list", "List this conversation's subagents and their current status, including available errorText/errorCode for failed agents.", emptyMap()),
            definition("agent_stop", "Cancel a subagent's live generation and await durable Stop settlement.", mapOf(
                "agent_id" to ToolProperty("string", "Subagent conversation ID."),
            ), listOf("agent_id")),
            if (ctx.subagentDeleteEnabled) definition("agent_delete", "Permanently delete an idle subagent belonging to this conversation, freeing its slot in the subagent count limit. Rejected while it is running, has queued messages, or has an undelivered result (call agent_wait first).", mapOf(
                "agent_id" to ToolProperty("string", "Subagent conversation ID."),
            ), listOf("agent_id")) else null,
        )
    }

    override fun handles(name: String) = BuiltInToolNames.canonical(name) in NAMES

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        executeOneShotResult(name, arguments, ctx).text

    override suspend fun executeOneShotResult(name: String, arguments: String, ctx: GenerationContext): ToolExecutionResult = try {
        check(ctx.subagentToolsEnabled && BuiltInToolNames.canonical(name) in NAMES) { "Subagent tools are unavailable" }
        check(BuiltInToolNames.canonical(name) != "agent_delete" || ctx.subagentDeleteEnabled) { "Deleting subagents is disabled in Settings" }
        val args = Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
        fun string(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf(String::isNotBlank) ?: error("$key is required")
        val result = when (BuiltInToolNames.canonical(name)) {
            "agent_spawn" -> {
                val modelId = string("model_id")
                require(modelId in ctx.subagentModelIds.orEmpty()) {
                    "model_id must be an available model ID from this request"
                }
                buildJsonObject {
                    put("agent_id", manager.spawn(string("name"), modelId, string("task"), ctx))
                    put("status", "started")
                }.toString()
            }
            "agent_send_message" -> {
                val id = string("agent_id")
                val queuedId = manager.send(id, string("message"), ctx)
                buildJsonObject {
                    put("agent_id", id)
                    put("status", "queued")
                    put("queued_item_id", queuedId)
                }.toString()
            }
            "agent_wait" -> {
                val ids = (args["agent_ids"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                val timeout = (args["timeout_ms"] as? JsonPrimitive)?.longOrNull ?: 300_000L
                val results = manager.wait(ids, timeout, ctx)
                buildJsonObject {
                    putJsonObject("subagent_receipts") {
                        results.forEach { (id, result) -> put(id, result.id) }
                    }
                    putJsonArray("results") {
                        results.forEach { (id, result) -> add(buildJsonObject {
                            put("agent_id", id)
                            put("status", result.status)
                            put("text", result.text)
                            result.errorText?.let { put("error", it) }
                            result.errorCode?.let { put("error_code", it) }
                        }) }
                    }
                }.toString()
            }
            "agent_list" -> Json.encodeToString(manager.list(ctx))
            "agent_stop" -> {
                manager.stop(string("agent_id"), ctx)
                "Subagent stopped"
            }
            "agent_delete" -> {
                manager.delete(string("agent_id"), ctx)
                "Subagent deleted"
            }
            else -> error("Unknown subagent tool")
        }
        ToolExecutionResult(result)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ToolExecutionResult("Error: ${error.message ?: "Subagent operation failed"}", isError = true)
    }

    private fun definition(
        name: String, description: String, properties: Map<String, ToolProperty>, required: List<String> = emptyList(),
    ) = ToolDefinition(function = ToolFunction(name = name, description = description,
        parameters = ToolParameters(properties = properties, required = required)))

    private companion object {
        val NAMES = setOf("agent_spawn", "agent_send_message", "agent_wait", "agent_list", "agent_stop", "agent_delete")
    }
}
