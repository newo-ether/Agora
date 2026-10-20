package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.MALFORMED_TOOL_CALL_NAME
import com.newoether.agora.api.util.malformedToolCallResultText
import android.app.Application
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.tool.ImageGenToolProvider
import com.newoether.agora.tool.MemoryToolProvider
import com.newoether.agora.tool.SkillToolProvider
import com.newoether.agora.tool.RagToolProvider
import com.newoether.agora.tool.ShellToolProvider
import com.newoether.agora.tool.ToolExecutionEvent
import com.newoether.agora.tool.ToolExecutionResult
import com.newoether.agora.tool.ToolImageStore
import com.newoether.agora.tool.ToolPresentationMetadata
import com.newoether.agora.tool.ToolProvider
import com.newoether.agora.tool.WebSearchToolProvider
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal data class AuthorizedToolCall(
    val batchIdentity: RunEffectIdentity,
    val callId: String,
    val name: String,
    val arguments: String,
    val context: GenerationContext,
    val authorizedToolNames: Set<String>,
    val skillHistory: List<ToolCallData> = emptyList(),
    /** Per-generation single-image transcription flow; null disables it. */
    val toolImageTranscriber: (suspend (com.newoether.agora.model.ToolImageAttachment, suspend (String) -> Unit) -> String?)? = null,
)

internal data class AuthorizedToolResult(
    val batchIdentity: RunEffectIdentity,
    val callId: String,
    val result: ToolExecutionResult,
)

internal interface GenerationToolPresentationSource {
    fun presentationMetadata(name: String): ToolPresentationMetadata?
}

/**
 * Executes one tool call from an already mailbox-authorized batch.
 *
 * This component owns the ToolProvider instances and their provider-local resources. It never
 * chooses a tool round, advances a Provider pass, persists a result, invokes the reducer, or
 * releases a runtime slot. Progress is presentation-only; the returned result retains the exact
 * accepted batch identity and call id supplied by the caller.
 */
internal class GenerationToolExecutor private constructor(
    private val providers: List<ToolProvider>,
    private val imageGenProvider: ImageGenToolProvider?,
) : GenerationToolDefinitionSource, GenerationToolPresentationSource {
    companion object {
        private val FILE_TOOL_NAMES = setOf(
            "file_read",
            "file_write",
            "file_edit",
            "file_multi_edit",
            "file_glob",
            "file_grep",
            "view_image",
        )

        fun createDefault(
            app: Application,
            conversations: ConversationRepository,
            memoryManager: MemoryManager,
            skillManager: SkillManager,
            sandboxFactory: SandboxManagerFactory?,
            additionalProviders: List<ToolProvider>,
            confirmShellCommand: suspend (server: String, summary: String, conversationId: String?) -> Boolean,
            generatingConversationIds: () -> Set<String> = { emptySet() },
        ): GenerationToolExecutor {
            val imageGenProvider = ImageGenToolProvider(app)
            val shellProvider = ShellToolProvider(
                sandboxFactory = sandboxFactory,
                imageStore = ToolImageStore(app),
            ).also { provider ->
                provider.confirm = confirmShellCommand
            }
            return GenerationToolExecutor(
                providers = listOf(
                    com.newoether.agora.tool.TodoToolProvider(conversations),
                    MemoryToolProvider(memoryManager),
                    SkillToolProvider(skillManager),
                    com.newoether.agora.tool.SkillCompletionToolProvider(com.newoether.agora.templates.InteractiveTemplateRepository(app), sandboxFactory,
                        app.getString(com.newoether.agora.R.string.skill_finish)),
                    com.newoether.agora.tool.SkillDraftToolProvider(app.getString(com.newoether.agora.R.string.skill_create_ai)),
                    com.newoether.agora.tool.SkillInvocationToolProvider(skillManager, app.getString(com.newoether.agora.R.string.skill_invoke)),
                    WebSearchToolProvider(),
                    RagToolProvider(conversations, generatingConversationIds),
                    com.newoether.agora.tool.InteractiveAnswerToolProvider(),
                    imageGenProvider,
                    shellProvider,
                ) + additionalProviders,
                imageGenProvider = imageGenProvider,
            )
        }

        internal fun forTest(providers: List<ToolProvider>): GenerationToolExecutor =
            GenerationToolExecutor(providers, imageGenProvider = null)
    }

    private fun availableDefinitions(context: GenerationContext): List<ToolDefinition> =
        providers.flatMap { it.definitions(context) }

    override fun definitions(context: GenerationContext): List<ToolDefinition> =
        definitions(context, emptyList())

    override fun definitions(
        context: GenerationContext,
        messages: List<com.newoether.agora.model.ChatMessage>,
    ): List<ToolDefinition> = com.newoether.agora.tool.ToolCapabilityLoading.definitions(
        availableDefinitions(context), context, messages,
    )

    fun imageDefinitions(context: GenerationContext): List<ToolDefinition> =
        imageGenProvider?.definitions(context).orEmpty()

    fun memoryDefinitions(context: GenerationContext): List<ToolDefinition> =
        providers.filterIsInstance<MemoryToolProvider>().flatMap { it.definitions(context) }

    fun webSearchDefinitions(context: GenerationContext): List<ToolDefinition> =
        providers.filterIsInstance<WebSearchToolProvider>().flatMap { it.definitions(context) }

    fun ragDefinitions(context: GenerationContext): List<ToolDefinition> =
        providers.filterIsInstance<RagToolProvider>().flatMap { it.definitions(context) }

    fun shellDefinitions(context: GenerationContext): List<ToolDefinition> =
        providers.filterIsInstance<ShellToolProvider>()
            .flatMap { it.definitions(context) }
            .filter { it.function.name !in FILE_TOOL_NAMES }

    fun fileDefinitions(context: GenerationContext): List<ToolDefinition> =
        providers.filterIsInstance<ShellToolProvider>()
            .flatMap { it.definitions(context) }
            .filter { it.function.name in FILE_TOOL_NAMES }

    override fun presentationMetadata(name: String): ToolPresentationMetadata? {
        if (name.isBlank()) return null
        for (provider in providers) {
            provider.presentationMetadata(name)?.let { return it }
        }
        return null
    }

    suspend fun semanticSearch(
        query: String,
        limit: Int,
        context: GenerationContext,
    ): List<Pair<MessageEntity, Float>> = providers.filterIsInstance<RagToolProvider>()
        .first()
        .semanticSearch(query, limit, context)

    /** Cleanup side effect only; it cannot authorize continuation or change runtime state. */
    suspend fun acknowledgeCommittedShellJobs(
        calls: List<ToolCallData>,
        context: GenerationContext,
    ) {
        providers.filterIsInstance<ShellToolProvider>()
            .firstOrNull()
            ?.acknowledgeCommittedJobs(calls, context)
    }

    suspend fun execute(
        call: AuthorizedToolCall,
        onEvent: suspend (ToolExecutionEvent) -> Unit,
    ): AuthorizedToolResult {
        if (call.name == MALFORMED_TOOL_CALL_NAME) {
            // Stand-in for a damaged call: never executed, answered with what was wrong.
            return call.result(
                ToolExecutionResult(text = malformedToolCallResultText(call.arguments), isError = true),
            )
        }
        if (call.name !in call.authorizedToolNames) {
            return call.result(
                ToolExecutionResult(
                    text = "Error executing tool '${call.name}': tool was not authorized for this provider pass",
                    isError = true,
                ),
            )
        }
        val completeArguments = call.arguments.ifBlank { "{}" }
        val argumentsAreCompleteObject = runCatching {
            Json.parseToJsonElement(completeArguments).jsonObject
        }.isSuccess
        if (!argumentsAreCompleteObject) {
            return call.result(
                ToolExecutionResult(
                    text = "Error executing tool '${call.name}': arguments are not a complete JSON object",
                    isError = true,
                ),
            )
        }

        if (call.name == com.newoether.agora.tool.ToolCapabilityLoading.NAME) {
            return call.result(com.newoether.agora.tool.ToolCapabilityLoading.load(
                completeArguments, availableDefinitions(call.context),
            ))
        }

        val executionTimeoutMs = toolExecutionTimeoutMs(
            toolName = call.name,
            defaultTimeoutMs = call.context.toolTimeoutMs,
        )
        val result = try {
            val provider = providers.firstOrNull { it.handles(call.name) }
                ?: return call.result(
                    ToolExecutionResult(text = "Unknown tool: ${call.name}", isError = true),
                )
            // A blocking provider must not pin the stream consumer forever. The detached attempt
            // lets the deadline stop awaiting immediately; cancellation still reaches cooperative
            // provider work and the tool round receives a recoverable error.
            val attemptJob = Job()
            val attempt = CoroutineScope(currentCoroutineContext() + attemptJob).async {
                var completedResult: ToolExecutionResult? = null
                val context = call.context.copy(toolEffectIdentity = call.batchIdentity)
                if (provider is com.newoether.agora.tool.SkillCompletionToolProvider) {
                    return@async provider.complete(completeArguments, context, call.skillHistory)
                }
                if (provider is com.newoether.agora.tool.SkillInvocationToolProvider) {
                    return@async provider.invoke(completeArguments, context, call.callId,
                        call.skillHistory, call.batchIdentity.runId)
                }

                provider.executeEvents(call.name, completeArguments, context).collect { event ->
                    if (event is ToolExecutionEvent.Completed) {
                        completedResult = event.result
                    }
                    onEvent(event)
                }
                completedResult
                    ?: ToolExecutionResult(
                        text = "Error executing tool '${call.name}': provider ended without a result",
                        isError = true,
                    )
            }
            try {
                withTimeout(executionTimeoutMs) { attempt.await() }
            } finally {
                attemptJob.cancel()
            }
        } catch (error: TimeoutCancellationException) {
            ToolExecutionResult(
                text = "Error executing tool '${call.name}': timed out after ${executionTimeoutMs}ms",
                isError = true,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolExecutionResult(
                text = "Error executing tool '${call.name}': ${error.localizedMessage ?: "Unknown error"}",
                isError = true,
            )
        }
        return call.result(result)
    }

    private fun AuthorizedToolCall.result(result: ToolExecutionResult) = AuthorizedToolResult(
        batchIdentity = batchIdentity,
        callId = callId,
        result = result,
    )
}

internal fun toolExecutionTimeoutMs(
    toolName: String,
    defaultTimeoutMs: Long,
): Long = when (toolName) {
    "generate_image" -> Constants.IMAGE_GENERATION_TIMEOUT_MS
    // Ask User owns one configured deadline for its whole question set.
    "ask_user" -> Long.MAX_VALUE
    else -> defaultTimeoutMs
}

internal fun appendBoundedToolOutput(
    current: String?,
    delta: String,
    maxChars: Int = 32 * 1024,
): String {
    if (delta.isEmpty()) return current.orEmpty()
    val combined = current.orEmpty() + delta
    return if (combined.length <= maxChars) combined
    else combined.takeLast(maxChars)
}

internal fun finalToolState(result: ToolExecutionResult, toolName: String): String {
    val protocol = result.structuredContent ?: result.text.takeUnless {
        toolName == "read_memory_file" || toolName == "read_active_memory" ||
            toolName == "read_skill_file" || toolName.startsWith("mcp_")
    }
    val resultObject = runCatching {
        Json.parseToJsonElement(protocol.orEmpty()).jsonObject
    }.getOrNull()
    val jobTool = toolName in setOf("execute_shell_command", "get_shell_job", "wait_for_job", "stop_shell_job")
    val payload = if (jobTool) resultObject?.get("result") as? JsonObject ?: resultObject else resultObject
    val errorCode = (payload?.get("error") as? JsonPrimitive)?.content
        ?: (resultObject?.get("error") as? JsonPrimitive)?.content
    if (errorCode == "no_results") return ToolExecutionStates.EMPTY
    val failedFlag = (payload?.get("failed") as? JsonPrimitive)?.content == "true" ||
        (resultObject?.get("failed") as? JsonPrimitive)?.content == "true"
    if (result.isError || failedFlag || !errorCode.isNullOrBlank()) {
        return ToolExecutionStates.FAILED
    }
    if (result.text.isEmpty() && result.structuredContent == null && result.images.isEmpty()) {
        return ToolExecutionStates.EMPTY
    }
    val jobState = if (jobTool) (payload?.get("state") as? JsonPrimitive)?.content?.lowercase() else null
    if (jobState == "stopped" || jobState == "interrupted") return ToolExecutionStates.STOPPED
    val isBackground = (resultObject?.get("background") as? JsonPrimitive)
        ?.content
        ?.toBooleanStrictOrNull() == true ||
        (
            jobState in setOf("running", "stopping", "settling") &&
                (payload?.get("job_id") ?: resultObject?.get("job_id")) != null
            )
    return if (isBackground) ToolExecutionStates.BACKGROUND_RUNNING
    else ToolExecutionStates.SUCCEEDED
}
