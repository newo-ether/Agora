package com.newoether.agora.ui.chat.message

import com.newoether.agora.api.util.MALFORMED_TOOL_CALL_NAME
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal enum class ToolKind {
    MEMORY_LIST,
    MEMORY_READ,
    MEMORY_CREATE,
    MEMORY_EDIT,
    MEMORY_DELETE,
    MEMORY_UPDATE_ACTIVE,
    SKILL_LIST,
    SKILL_READ,
    SKILL_CREATE,
    SKILL_EDIT,
    SKILL_DELETE,
    WEB_SEARCH,
    WEB_FETCH,
    CONVERSATION_SEARCH,
    CONVERSATION_LIST,
    CONVERSATION_READ,
    SHELL_LIST,
    SHELL_EXECUTE,
    SHELL_JOB_LIST,
    SHELL_JOB_GET,
    SHELL_JOB_WAIT,
    SHELL_JOB_STOP,
    FILE_READ,
    FILE_WRITE,
    FILE_EDIT,
    FILE_GLOB,
    FILE_GREP,
    IMAGE_VIEW,
    IMAGE_GENERATE,
    TASK_CREATE,
    TASK_LIST,
    TASK_DELETE,
    LOOP_START,
    LOOP_STOP,
    PHONE,
    ASK_USER,
    TODO_READ,
    TODO_UPDATE,
    SUBAGENT_SPAWN,
    SUBAGENT_SEND,
    SUBAGENT_WAIT,
    SUBAGENT_LIST,
    SUBAGENT_STOP,
    SUBAGENT_DELETE,
    MCP_LIST,
    MCP_SEARCH,
    MCP,
    UNKNOWN,
}

internal enum class ToolPresentationState {
    CALLING,
    RUNNING,
    COMPLETED,
    EMPTY,
    FAILED,
    STOPPED,
    BACKGROUND_RUNNING,
}

internal data class ToolPresentation(
    val toolName: String,
    val kind: ToolKind,
    val state: ToolPresentationState,
    val arguments: JsonObject?,
    val result: JsonElement?,
    val rawArguments: String?,
    val rawResult: String?,
    val rawTextResult: String?,
    val rawStructuredResult: String?,
    val liveOutput: String?,
    val subject: String?,
    val device: String?,
    val count: Int?,
    val errorMessage: String?,
    val exitCode: Int?,
    val jobId: String?,
    val outputLength: Int?,
    val jobState: String? = null,
    val operation: String? = null,
    val destination: String? = null,
    val outcome: String? = null,
    val answeredCount: Int? = null,
) {
    /**
     * Drives the group loading indicator. BACKGROUND_RUNNING is deliberately excluded: a
     * detached background shell job must not occupy the loading bar once its tool round ends —
     * the card shows its own "running in background" status instead.
     */
    val isActive: Boolean
        get() = state == ToolPresentationState.CALLING ||
            state == ToolPresentationState.RUNNING
}

internal object ToolPresentationResolver {
    private val json = Json { ignoreUnknownKeys = true }

    /** What Agora's malformed-call stand-in carried: the tool name and arguments the model sent. */
    internal data class MalformedCallOriginals(val name: String?, val arguments: String?)

    /**
     * Agora replaces a call it cannot execute with a stand-in under [MALFORMED_TOOL_CALL_NAME], and
     * keeps what the model actually sent inside the argument envelope so the round stays pairable.
     * That envelope is transport bookkeeping, not what the user asked to see: the card should show
     * the tool the model named and the arguments it streamed.
     */
    internal fun malformedCallOriginals(
        toolName: String,
        rawArguments: String?,
    ): MalformedCallOriginals? {
        if (toolName != MALFORMED_TOOL_CALL_NAME) return null
        val fields = runCatching { json.parseToJsonElement(rawArguments.orEmpty()) as? JsonObject }
            .getOrNull()
            ?: return null
        fun text(key: String) = (fields[key] as? JsonPrimitive)?.contentOrNull
        return MalformedCallOriginals(
            name = text("original_name")?.takeIf { it.isNotBlank() },
            arguments = text("original_arguments"),
        )
    }

    fun kindForToolName(toolName: String?): ToolKind =
        kindFor(com.newoether.agora.tool.BuiltInToolNames.canonical(toolName.orEmpty()))

    private const val RESOLVED_CACHE_SIZE = 64

    // Composition resolves every visible tool row on each stream snapshot. A finished segment no
    // longer changes, so its presentation is computed once instead of re-parsing its result JSON.
    private val resolvedFinished = object : LinkedHashMap<MessageSegment, ToolPresentation>(
        RESOLVED_CACHE_SIZE, 0.75f, true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MessageSegment, ToolPresentation>) =
            size > RESOLVED_CACHE_SIZE
    }

    fun resolve(segment: MessageSegment): ToolPresentation {
        if (segment.toolResult == null) return compute(segment)
        synchronized(resolvedFinished) { resolvedFinished[segment] }?.let { return it }
        val presentation = compute(segment)
        synchronized(resolvedFinished) { resolvedFinished[segment] = presentation }
        return presentation
    }

    private fun compute(segment: MessageSegment): ToolPresentation {
        val toolName = if (segment.remoteToolSource == null) {
            com.newoether.agora.tool.BuiltInToolNames.canonical(segment.toolName.orEmpty())
        } else segment.toolName.orEmpty()
        val remoteShell = if (segment.remoteToolSource != null && isRemoteShellTool(toolName)) {
            mapRemoteToolDetail(segment.remoteToolSource, toolName, segment.toolArgs,
                segment.toolResult ?: segment.toolProgress, segment.toolState)
        } else null
        val remoteShellBody = remoteShell?.body as? ToolDetailBody.Shell
        val malformed = malformedCallOriginals(toolName, segment.toolArgs)
        val kind = if (remoteShellBody != null) ToolKind.SHELL_EXECUTE else kindForToolName(toolName)
        val displayArguments = malformed?.arguments ?: segment.toolArgs
        val resultElement = parseElement(
            segment.toolStructuredResult ?: segment.toolResult.takeUnless {
                kind == ToolKind.MEMORY_READ || kind == ToolKind.SKILL_READ || kind == ToolKind.MCP
            },
        )
        val resultEnvelope = resultElement as? JsonObject
        val resultObject = effectiveResultObject(kind, resultEnvelope)
        val errorCode = resultObject.string("error") ?: resultEnvelope.string("error")
        val exitCode = remoteShellBody?.status?.toIntOrNull()
            ?: resultObject.int("exit_code") ?: resultEnvelope.int("exit_code")
        val explicitState = stateFromWire(segment.toolState)
        // Until a result arrives, arguments can still be a JSON prefix and can also contain a very
        // large file payload. Never strictly parse that growing buffer on the UI thread. The
        // bounded prefix resolver below extracts only live summary hints; final semantic parsing
        // remains available once the call has produced a result.
        val argumentsAwaitingResult =
            segment.toolResult == null && segment.toolStructuredResult == null
        val args = if (argumentsAwaitingResult) null else parseObject(displayArguments)
        val streamingHints = StreamingToolArgumentHintResolver.resolve(kind, displayArguments)
        val jobState = if (kind in setOf(ToolKind.SHELL_EXECUTE, ToolKind.SHELL_JOB_GET,
                ToolKind.SHELL_JOB_WAIT, ToolKind.SHELL_JOB_STOP)) resultObject.string("state")?.lowercase() else null
        val background = resultEnvelope.boolean("background") == true ||
            jobState in setOf("running", "stopping", "settling") &&
            (resultObject.string("job_id") ?: resultEnvelope.string("job_id")) != null
        val count = semanticCount(kind, resultObject)
            ?: tolerantSemanticCount(kind, segment.toolStructuredResult ?: segment.toolResult)
            ?: if (kind == ToolKind.MEMORY_READ || kind == ToolKind.SKILL_READ) {
                selectedReadNames(kind, args)?.size ?: streamingHints.count
            } else null
        // Failure semantics are authoritative. A Conch error envelope, an explicit `failed` flag, a
        // FAILED wire state must never render as an empty or
        // completed card just because the payload has no readable content field. The one exception
        // is a provider-declared empty result (`no_results`), which is an explicit success even when
        // the protocol row carries a terminal FAILED state.
        val declaredEmpty = errorCode == "no_results"
        val failureCode = errorCode?.takeIf { it.isNotBlank() && !declaredEmpty }
        val failedFlag = resultObject.boolean("failed") == true ||
            resultEnvelope.boolean("failed") == true
        val wireFailure = explicitState == ToolPresentationState.FAILED
        val failed = !declaredEmpty && (failureCode != null || failedFlag || wireFailure)
        val semanticEmpty = !failed && isSemanticEmpty(
            kind = kind,
            rawResult = segment.toolResult.orEmpty(),
            result = resultObject,
            value = resultElement,
            count = count,
            errorCode = errorCode,
        )
        val error = if (!failed) {
            null
        } else {
            failureCode?.let { code ->
                (resultObject.string("message") ?: resultEnvelope.string("message")
                    ?: resultObject.string("detail") ?: resultEnvelope.string("detail"))
                    ?.takeIf { it.isNotBlank() }
                    ?: code.replace('_', ' ')
            } ?: (resultObject.string("message") ?: resultEnvelope.string("message"))
                ?.takeIf { it.isNotBlank() }
                ?: (segment.toolResultText ?: segment.toolResult)
                    ?.takeIf {
                        resultElement !is JsonObject && it.isNotBlank() && parseElement(it) == null
                    }
        }
        val state = when {
            segment.toolResult == null -> explicitState ?: run {
                if (segment.toolProgress.isNullOrEmpty()) ToolPresentationState.CALLING
                else ToolPresentationState.RUNNING
            }
            failed -> ToolPresentationState.FAILED
            jobState == "stopped" || jobState == "interrupted" -> ToolPresentationState.STOPPED
            semanticEmpty -> ToolPresentationState.EMPTY
            background -> ToolPresentationState.BACKGROUND_RUNNING
            explicitState == ToolPresentationState.STOPPED -> ToolPresentationState.STOPPED
            else -> explicitState ?: ToolPresentationState.COMPLETED
        }
        return ToolPresentation(
            toolName = malformed?.name ?: toolName,
            kind = kind,
            state = state,
            arguments = args,
            result = resultObject ?: resultElement,
            rawArguments = displayArguments,
            rawResult = segment.toolResult,
            rawTextResult = segment.toolResultText,
            rawStructuredResult = segment.toolStructuredResult,
            liveOutput = segment.toolProgress,
            subject = normalizeToolSummarySubject(
                remoteShell?.codeArgument?.text ?: subject(kind, args, resultObject)
                    ?: subject(kind, args, resultEnvelope)
                    ?: streamingHints.subject,
            ),
            device = resultEnvelope.string("server")
                ?: resultObject.string("server")
                ?: segment.toolTarget
                ?: args.string("server")
                ?: streamingHints.server,
            count = count,
            errorMessage = error,
            exitCode = exitCode,
            jobId = resultEnvelope.string("job_id") ?: resultObject.string("job_id"),
            outputLength = resultObject.string("output")?.length,
            jobState = jobState,
            operation = (args.string("operation") ?: streamingHints.operation)?.trim()?.lowercase(),
            destination = normalizeToolSummarySubject(args.string("new_name") ?: streamingHints.destination),
            outcome = when (kind) {
                ToolKind.ASK_USER -> when {
                    resultObject.string("delivery") == "queued" -> "queued"
                    resultObject.boolean("answered") == true -> "answered"
                    resultObject.boolean("answered") == false -> "skipped"
                    resultObject.array("answers") != null -> "answers"
                    else -> null
                }
                ToolKind.LOOP_STOP -> resultObject.string("status")
                else -> null
            },
            answeredCount = if (kind == ToolKind.ASK_USER) resultObject.array("answers")?.count {
                (it as? JsonObject).boolean("answered") == true
            } else null,
        )
    }

    /** Durable foreground Shell results wrap the terminal job payload in a `result` envelope. */
    private fun effectiveResultObject(
        kind: ToolKind,
        envelope: JsonObject?,
    ): JsonObject? {
        if (envelope == null) return null
        if (kind != ToolKind.SHELL_EXECUTE && kind != ToolKind.SHELL_JOB_GET &&
            kind != ToolKind.SHELL_JOB_WAIT && kind != ToolKind.SHELL_JOB_STOP) return envelope
        return envelope["result"] as? JsonObject ?: envelope
    }

    private fun kindFor(name: String): ToolKind = when (name) {
        "memory_list" -> ToolKind.MEMORY_LIST
        "memory_read", "active_memory_read" -> ToolKind.MEMORY_READ
        "memory_create" -> ToolKind.MEMORY_CREATE
        "memory_edit" -> ToolKind.MEMORY_EDIT
        "memory_delete" -> ToolKind.MEMORY_DELETE
        "active_memory_update" -> ToolKind.MEMORY_UPDATE_ACTIVE
        "skill_list" -> ToolKind.SKILL_LIST
        "skill_read" -> ToolKind.SKILL_READ
        "skill_create" -> ToolKind.SKILL_CREATE
        "skill_edit" -> ToolKind.SKILL_EDIT
        "skill_delete" -> ToolKind.SKILL_DELETE
        "web_search", "openai_search", "google_search" -> ToolKind.WEB_SEARCH
        "web_fetch" -> ToolKind.WEB_FETCH
        "conversation_search" -> ToolKind.CONVERSATION_SEARCH
        "conversation_list" -> ToolKind.CONVERSATION_LIST
        "conversation_read" -> ToolKind.CONVERSATION_READ
        "shell_list" -> ToolKind.SHELL_LIST
        "shell_exec" -> ToolKind.SHELL_EXECUTE
        "shell_job_list" -> ToolKind.SHELL_JOB_LIST
        "shell_job_get" -> ToolKind.SHELL_JOB_GET
        "shell_job_wait" -> ToolKind.SHELL_JOB_WAIT
        "shell_job_stop" -> ToolKind.SHELL_JOB_STOP
        "file_read" -> ToolKind.FILE_READ
        "file_write" -> ToolKind.FILE_WRITE
        "file_edit", "file_multi_edit" -> ToolKind.FILE_EDIT
        "file_glob" -> ToolKind.FILE_GLOB
        "file_grep" -> ToolKind.FILE_GREP
        "file_view_image" -> ToolKind.IMAGE_VIEW
        "image_generate" -> ToolKind.IMAGE_GENERATE
        "task_create" -> ToolKind.TASK_CREATE
        "task_list" -> ToolKind.TASK_LIST
        "task_delete" -> ToolKind.TASK_DELETE
        "loop_start" -> ToolKind.LOOP_START
        "loop_stop" -> ToolKind.LOOP_STOP
        "phone_claim", "phone_release", "phone_screenshot", "phone_ui_tree", "phone_tap",
        "phone_long_press", "phone_swipe", "phone_type", "phone_key", "phone_app_launch", "phone_app_list" -> ToolKind.PHONE
        "ask_user" -> ToolKind.ASK_USER
        "todo_read" -> ToolKind.TODO_READ
        "todo_update" -> ToolKind.TODO_UPDATE
        "agent_spawn" -> ToolKind.SUBAGENT_SPAWN
        "agent_send_message" -> ToolKind.SUBAGENT_SEND
        "agent_wait" -> ToolKind.SUBAGENT_WAIT
        "agent_list" -> ToolKind.SUBAGENT_LIST
        "agent_stop" -> ToolKind.SUBAGENT_STOP
        "agent_delete" -> ToolKind.SUBAGENT_DELETE
        "mcp_list_servers" -> ToolKind.MCP_LIST
        "mcp_search_tools" -> ToolKind.MCP_SEARCH
        else -> if (name.startsWith("mcp_")) ToolKind.MCP else ToolKind.UNKNOWN
    }

    private fun stateFromWire(value: String?): ToolPresentationState? = when (value) {
        ToolExecutionStates.CALLING -> ToolPresentationState.CALLING
        ToolExecutionStates.RUNNING -> ToolPresentationState.RUNNING
        ToolExecutionStates.SUCCEEDED -> ToolPresentationState.COMPLETED
        ToolExecutionStates.EMPTY -> ToolPresentationState.EMPTY
        ToolExecutionStates.FAILED -> ToolPresentationState.FAILED
        ToolExecutionStates.STOPPED -> ToolPresentationState.STOPPED
        ToolExecutionStates.BACKGROUND_RUNNING -> ToolPresentationState.BACKGROUND_RUNNING
        else -> null
    }

    private fun parseObject(value: String?): JsonObject? =
        parseElement(value) as? JsonObject

    private fun parseElement(value: String?): JsonElement? {
        if (value == null) return null
        return runCatching { json.parseToJsonElement(value) }.getOrNull()
    }

    private fun semanticCount(kind: ToolKind, result: JsonObject?): Int? = when (kind) {
        ToolKind.MCP_LIST -> result.int("returned") ?: result.arraySize("servers")
        ToolKind.MCP_SEARCH -> result.int("returned") ?: result.arraySize("tools")
        ToolKind.MEMORY_LIST,
        ToolKind.SKILL_LIST -> result.arraySize("files")
        ToolKind.WEB_SEARCH -> result.arraySize("results")
        ToolKind.CONVERSATION_SEARCH -> result.arraySize("results")
        ToolKind.CONVERSATION_LIST -> result.arraySize("conversations")
        ToolKind.CONVERSATION_READ -> result.arraySize("messages")
        ToolKind.SHELL_LIST -> result.arraySize("devices")
        ToolKind.SHELL_JOB_LIST -> result.arraySize("jobs")
        ToolKind.FILE_GLOB -> result.arraySize("files")
        ToolKind.FILE_GREP -> result.arraySize("matches")
        ToolKind.TASK_LIST -> result.arraySize("tasks")
        ToolKind.ASK_USER -> result.arraySize("answers") ?: result.int("questions")
            ?: result.boolean("answered")?.let { 1 }
        else -> null
    }

    private fun tolerantSemanticCount(kind: ToolKind, rawResult: String?): Int? = when (kind) {
        ToolKind.CONVERSATION_SEARCH -> tolerantConversationCount(rawResult)
        else -> null
    }

    private fun tolerantConversationCount(rawResult: String?): Int? {
        if (rawResult.isNullOrBlank()) return null
        val document = StreamingJsonParser.parse(rawResult)
        val root = document.root as? StreamingJsonObject ?: return null
        val resultsEntry = root.entries.firstOrNull { it.key == "results" }
        val resultsArray = resultsEntry?.value as? StreamingJsonArray
        return resultsArray?.values?.size
    }

    private fun isSemanticEmpty(
        kind: ToolKind,
        rawResult: String,
        result: JsonObject?,
        value: JsonElement?,
        count: Int?,
        errorCode: String?,
    ): Boolean {
        if (rawResult.isEmpty()) return true
        if (count != null && count == 0) return true
        if (errorCode == "no_results") return true
        return when (kind) {
            ToolKind.TODO_READ, ToolKind.SUBAGENT_LIST -> (value as? JsonArray)?.isEmpty() == true
            ToolKind.SUBAGENT_WAIT -> result.array("results")?.isEmpty() == true
            ToolKind.FILE_READ -> result.string("content").isNullOrEmpty()
            else -> false
        }
    }

    private fun subject(
        kind: ToolKind,
        arguments: JsonObject?,
        result: JsonObject?,
    ): String? = when (kind) {
        ToolKind.MEMORY_READ,
        ToolKind.SKILL_READ -> selectedReadNames(kind, arguments)?.singleOrNull()
            ?: arguments.string("name").takeIf { selectedReadNames(kind, arguments) == null }
        ToolKind.MEMORY_CREATE,
        ToolKind.MEMORY_EDIT,
        ToolKind.MEMORY_DELETE,
        ToolKind.SKILL_CREATE,
        ToolKind.SKILL_EDIT,
        ToolKind.SKILL_DELETE -> arguments.string("name")
        ToolKind.WEB_SEARCH,
        ToolKind.MCP_SEARCH,
        ToolKind.CONVERSATION_SEARCH -> arguments.string("query")
        ToolKind.WEB_FETCH -> arguments.string("url")
        ToolKind.CONVERSATION_READ -> result.string("title")
            ?: arguments.string("conversation_id")
        ToolKind.SHELL_EXECUTE -> arguments.string("command")
            ?: result.string("command")
        ToolKind.SHELL_JOB_GET,
        ToolKind.SHELL_JOB_WAIT,
        ToolKind.SHELL_JOB_STOP -> arguments.string("job_id")
            ?: result.string("job_id")
        ToolKind.FILE_READ,
        ToolKind.FILE_WRITE,
        ToolKind.FILE_EDIT,
        ToolKind.IMAGE_VIEW -> arguments.string("path")
            ?: result.string("path")
        ToolKind.FILE_GLOB,
        ToolKind.FILE_GREP -> arguments.string("pattern")
            ?: result.string("pattern")
        ToolKind.IMAGE_GENERATE -> arguments.string("prompt")
        ToolKind.TASK_CREATE -> arguments.string("name")
        ToolKind.TASK_DELETE -> arguments.string("id_or_name")
            ?: arguments.string("name")
            ?: arguments.string("task_id")
        else -> null
    }

    private fun selectedReadNames(kind: ToolKind, arguments: JsonObject?): List<String>? {
        val array = arguments.array("names") ?: return null
        val names = array.mapNotNull { it.primitiveContent()?.takeIf { name ->
            if (kind == ToolKind.MEMORY_READ) name.isNotEmpty() else name.isNotBlank()
        } }
        return names.takeIf {
            if (kind == ToolKind.MEMORY_READ) array.isNotEmpty() else names.isNotEmpty()
        }
    }

    private fun JsonObject?.string(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.int(key: String): Int? =
        (this?.get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject?.boolean(key: String): Boolean? =
        (this?.get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject?.array(key: String): JsonArray? =
        this?.get(key) as? JsonArray

    private fun JsonObject?.arraySize(key: String): Int? =
        (this?.get(key) as? JsonArray)?.size

    private fun JsonElement.primitiveContent(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}
