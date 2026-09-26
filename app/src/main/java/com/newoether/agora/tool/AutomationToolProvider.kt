package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.automation.CronExpression
import com.newoether.agora.automation.LoopManager
import com.newoether.agora.automation.LoopPolicy
import com.newoether.agora.automation.TaskManager
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.data.local.LoopEntity
import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Model-facing controls for saved Tasks and the current conversation's Loop.
 *
 * These tools mutate persistent automation state, so the feature flag is checked both while
 * publishing definitions and again at execution time. The second check protects against stale
 * tool calls produced before the user disabled automation tools.
 */
class AutomationToolProvider(
    private val taskManager: TaskManager,
    private val loopManager: LoopManager,
    /** Saved system prompts, read per call so a task can pin the prompt the user just added. */
    private val savedSystemPrompts: suspend () -> List<SystemPromptEntry> = { emptyList() },
    private val isCurrentlyEnabled: suspend () -> Boolean = { true },
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.automationToolsEnabled) return emptyList()
        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = CREATE_TASK,
                    description = "Create an enabled background task with a 5-field cron schedule. Use only when the user explicitly asks to create a recurring task.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "name" to ToolProperty("string", "A short, descriptive task name."),
                            "prompt" to ToolProperty("string", "The complete prompt to run on every occurrence."),
                            "cron" to ToolProperty("string", "A valid 5-field cron expression: minute hour day-of-month month day-of-week."),
                            "model" to ToolProperty("string", "Optional provider-prefixed model id. Omit to use the app default model."),
                            "system_prompt" to ToolProperty("string", "Optional saved system prompt, given as its id or its exact title. Omit to use the app default prompt."),
                        ),
                        required = listOf("name", "prompt", "cron"),
                    ),
                ),
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = LIST_TASKS,
                    description = "List all saved background tasks, including ids, schedules, enabled state, and next run times.",
                    parameters = ToolParameters(properties = emptyMap()),
                ),
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = DELETE_TASK,
                    description = "Delete one saved task by exact id or unique task name. This is destructive; use only when the user explicitly asks.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "id_or_name" to ToolProperty("string", "The exact task id or a unique task name."),
                        ),
                        required = listOf("id_or_name"),
                    ),
                ),
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = START_LOOP,
                    description = "Start a Loop in the current conversation. interval_seconds must be ${LoopPolicy.MIN_INTERVAL_SECONDS} to ${LoopPolicy.MAX_INTERVAL_SECONDS}. max_cycles defaults to ${LoopPolicy.DEFAULT_MAX_CYCLES} and must be ${LoopPolicy.MIN_MAX_CYCLES} to ${LoopPolicy.MAX_MAX_CYCLES}.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "interval_seconds" to ToolProperty("integer", "Seconds between cycles, from ${LoopPolicy.MIN_INTERVAL_SECONDS} through ${LoopPolicy.MAX_INTERVAL_SECONDS} (7 days)."),
                            "prompt" to ToolProperty("string", "Optional prompt injected each cycle. Blank or omitted means Continue."),
                            "max_cycles" to ToolProperty("integer", "Optional safety limit from ${LoopPolicy.MIN_MAX_CYCLES} through ${LoopPolicy.MAX_MAX_CYCLES}. Defaults to ${LoopPolicy.DEFAULT_MAX_CYCLES}."),
                        ),
                        required = listOf("interval_seconds"),
                    ),
                ),
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = STOP_LOOP,
                    description = "Stop the Loop attached to the current conversation.",
                    parameters = ToolParameters(properties = emptyMap()),
                ),
            ),
        )
    }

    override fun handles(name: String): Boolean = name in TOOL_NAMES

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.automationToolsEnabled || !isCurrentlyEnabled()) {
            return error("Automation tools are disabled")
        }
        if (name !in TOOL_NAMES) return error("Unknown automation tool: $name")

        return try {
            val args = Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
            when (name) {
                CREATE_TASK -> createTask(args)
                LIST_TASKS -> listTasks()
                DELETE_TASK -> deleteTask(args)
                START_LOOP -> startLoop(args, ctx)
                STOP_LOOP -> stopLoop(ctx)
                else -> error("Unknown automation tool: $name")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error(e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Invalid tool arguments")
        }
    }

    private suspend fun createTask(args: JsonObject): String {
        val name = args.string("name")?.trim().orEmpty()
        if (name.isEmpty()) return error("name is required")
        val prompt = args.string("prompt")?.trim().orEmpty()
        if (prompt.isEmpty()) return error("prompt is required")
        val cron = args.string("cron")?.trim().orEmpty()
        if (cron.isEmpty()) return error("cron is required")
        if (!CronExpression.isValid(cron)) return error("Invalid 5-field cron expression: $cron")
        val model = args.string("model")?.trim()?.takeIf { it.isNotEmpty() }

        val requestedPrompt = args.string("system_prompt")?.trim()?.takeIf { it.isNotEmpty() }
        val systemPromptId = if (requestedPrompt == null) null else {
            // Fail closed: silently falling back to the app default would hide the mismatch until
            // the task runs with the wrong prompt.
            val prompts = savedSystemPrompts()
            val byTitle = prompts.filter { it.title.trim().equals(requestedPrompt, ignoreCase = true) }
            when {
                prompts.any { it.id == requestedPrompt } -> requestedPrompt
                byTitle.size == 1 -> byTitle.first().id
                byTitle.size > 1 -> return error(
                    "Multiple saved system prompts are titled \"$requestedPrompt\"; pass the prompt id instead"
                )
                else -> return error("Saved system prompt not found: $requestedPrompt")
            }
        }

        val task = taskManager.createTask(name, prompt, cron, model, systemPromptId)
        return buildJsonObject {
            put("type", CREATE_TASK)
            put("task", task.toJson())
        }.toString()
    }

    private suspend fun listTasks(): String {
        val tasks = taskManager.listTasksSnapshot()
        return buildJsonObject {
            put("type", LIST_TASKS)
            putJsonArray("tasks") {
                tasks.forEach { add(it.toJson()) }
            }
        }.toString()
    }

    private suspend fun deleteTask(args: JsonObject): String {
        val idOrName = args.string("id_or_name")?.trim().orEmpty()
        if (idOrName.isEmpty()) return error("id_or_name is required")

        return when (val result = taskManager.deleteTaskByIdOrName(idOrName)) {
            is TaskManager.DeleteResult.Deleted -> buildJsonObject {
                put("type", DELETE_TASK)
                put("deleted", true)
                put("task", result.task.toJson())
            }.toString()
            TaskManager.DeleteResult.NotFound -> error("Task not found: $idOrName")
            is TaskManager.DeleteResult.Ambiguous -> {
                val matches = result.matches.joinToString { "${it.name} (${it.id})" }
                error("Multiple tasks match '$idOrName': $matches")
            }
        }
    }

    private suspend fun startLoop(args: JsonObject, ctx: GenerationContext): String {
        val conversationId = ctx.conversationId?.takeIf { it.isNotBlank() }
            ?: return error("start_loop requires a current conversation")
        val intervalSeconds = args.long("interval_seconds")
            ?: return error("interval_seconds must be an integer")
        if (intervalSeconds !in LoopPolicy.MIN_INTERVAL_SECONDS..LoopPolicy.MAX_INTERVAL_SECONDS) {
            return error("interval_seconds must be between ${LoopPolicy.MIN_INTERVAL_SECONDS} and ${LoopPolicy.MAX_INTERVAL_SECONDS}")
        }

        val maxCycles = if ("max_cycles" in args) {
            args.int("max_cycles") ?: return error("max_cycles must be an integer")
        } else {
            LoopPolicy.DEFAULT_MAX_CYCLES
        }
        if (maxCycles !in LoopPolicy.MIN_MAX_CYCLES..LoopPolicy.MAX_MAX_CYCLES) {
            return error("max_cycles must be between ${LoopPolicy.MIN_MAX_CYCLES} and ${LoopPolicy.MAX_MAX_CYCLES}")
        }
        val prompt = args.string("prompt")?.trim()?.takeIf { it.isNotEmpty() }

        return when (val result = loopManager.startLoop(
            conversationId = conversationId,
            intervalMs = intervalSeconds * 1_000L,
            prompt = prompt,
            maxCycles = maxCycles,
        )) {
            is LoopManager.StartResult.Started -> buildJsonObject {
                put("type", START_LOOP)
                put("status", "started")
                put("loop", result.loop.toJson())
            }.toString()
            is LoopManager.StartResult.Conflict -> error(
                "A Loop is already active for this conversation (next fire at ${result.existing.nextFireAt})"
            )
            is LoopManager.StartResult.Invalid -> error(result.reason)
            LoopManager.StartResult.ConversationMissing -> error("Conversation not found: $conversationId")
        }
    }

    private suspend fun stopLoop(ctx: GenerationContext): String {
        val conversationId = ctx.conversationId?.takeIf { it.isNotBlank() }
            ?: return error("stop_loop requires a current conversation")

        return when (loopManager.stopLoop(conversationId)) {
            LoopManager.StopResult.Stopped -> stopLoopJson(conversationId, "stopped")
            LoopManager.StopResult.AlreadyStopped -> stopLoopJson(conversationId, "already_stopped")
            LoopManager.StopResult.NotFound -> error("No Loop exists for conversation: $conversationId")
        }
    }

    private fun stopLoopJson(conversationId: String, status: String): String = buildJsonObject {
        put("type", STOP_LOOP)
        put("status", status)
        put("conversation_id", conversationId)
    }.toString()

    private fun TaskEntity.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("prompt", prompt)
        modelId?.let { put("model", it) }
        systemPromptId?.let { put("system_prompt_id", it) }
        put("cron", cronExpr)
        put("enabled", enabled)
        put("created_at", createdAt)
        lastRunAt?.let { put("last_run_at", it) }
        put("next_run_at", nextRunAt)
    }

    private fun LoopEntity.toJson(): JsonObject = buildJsonObject {
        put("conversation_id", conversationId)
        put("interval_seconds", intervalMs / 1_000L)
        prompt?.let { put("prompt", it) }
        put("next_fire_at", nextFireAt)
        put("cycle_count", cycleCount)
        maxCycles?.let { put("max_cycles", it) }
        put("active", active)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun error(message: String): String = "Error: $message"

    private companion object {
        const val CREATE_TASK = "create_task"
        const val LIST_TASKS = "list_tasks"
        const val DELETE_TASK = "delete_task"
        const val START_LOOP = "start_loop"
        const val STOP_LOOP = "stop_loop"

        val TOOL_NAMES = setOf(CREATE_TASK, LIST_TASKS, DELETE_TASK, START_LOOP, STOP_LOOP)
    }
}
