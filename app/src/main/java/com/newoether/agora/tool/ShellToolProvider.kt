package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.util.Constants
import com.newoether.agora.util.SHELL_COMMAND_MAX_BYTES
import com.newoether.agora.util.SHELL_WORKDIR_MAX_BYTES
import com.newoether.agora.util.SHELL_FILE_WRITE_MAX_BYTES
import com.newoether.agora.util.SHELL_FILE_GREP_MAX_CONTEXT_LINES
import com.newoether.agora.util.ShellGrepOptions
import com.newoether.agora.util.shellFileSha256
import com.newoether.agora.viewmodel.GenerationContext
import com.newoether.agora.viewmodel.ShellActionDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private fun shellCommandValidationError(command: String, workdir: String): String? = when {
    command.toByteArray(Charsets.UTF_8).size > SHELL_COMMAND_MAX_BYTES ->
        "command exceeds 64KB limit"
    workdir.toByteArray(Charsets.UTF_8).size > SHELL_WORKDIR_MAX_BYTES ->
        "workdir exceeds 32KB limit"
    else -> null
}

class ShellToolProvider(
    private val sandboxFactory: SandboxManagerFactory? = null,
    private val imageStore: ToolImageStore? = null,
) : ToolProvider {

    private val sandbox = sandboxFactory?.create()
    private val durableJobs = ShellDurableJobExecutor(sandbox)

    /**
     * Optional user-confirmation gate for state-changing operations. It covers remote devices and
     * the local sandbox alike; the controller itself decides whether the switch is on.
     */
    var confirm: (suspend (
        server: String,
        summary: String,
        conversationId: String?,
        detail: ShellActionDetail?,
    ) -> Boolean)? = null

    /** Best-effort cleanup after the caller has committed these exact results to Room. */
    internal suspend fun acknowledgeCommittedJobs(
        calls: List<ToolCallData>,
        context: GenerationContext,
    ) {
        val acknowledgements = terminalShellJobAcknowledgements(calls)
        if (acknowledgements.isEmpty()) return
        supervisorScope {
            acknowledgements.map { acknowledgement ->
                async {
                    withTimeoutOrNull(JOB_ACK_TIMEOUT_MS) {
                        val backend = getStandardBackend(acknowledgement.serverName, context)
                            ?: return@withTimeoutOrNull
                        try {
                            backend.acknowledgeJob(acknowledgement.jobId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Retention TTL/count remains the safe fallback when cleanup is offline.
                        } finally {
                            backend.close()
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun confirmTarget(
        ctx: GenerationContext,
        device: ShellDeviceConfig?,
        summary: String,
        detail: ShellActionDetail? = null,
    ): Boolean {
        val target = device?.name?.ifBlank { "${device.type} server" } ?: "Local Sandbox"
        return confirm?.invoke(target, summary, ctx.conversationId, detail) ?: true
    }

    // ── Helpers ────────────────────────────────────────────

    private fun serverNotFoundMessage(serverName: String, ctx: GenerationContext): String =
        shellServerNotFoundMessage(serverName, ctx)

    private fun getBackend(serverName: String, ctx: GenerationContext): Backend? =
        openShellBackend(serverName, ctx, sandbox)

    private fun getStandardBackend(serverName: String, ctx: GenerationContext): Backend? =
        openShellBackend(serverName, ctx, sandbox, standardOnly = true)

    private fun standardServerNotFound(serverName: String, ctx: GenerationContext): String =
        shellServerNotFoundMessage(serverName, ctx, standardOnly = true)

    // ── ToolProvider interface ─────────────────────────────

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        ShellToolDefinitions.build(ctx)

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        return when (BuiltInToolNames.canonical(name)) {
            "shell_list" -> listShells(ctx)
            "shell_exec" -> executeShellCommand(arguments, ctx)
            "shell_job_list" -> durableJobs.listShellJobs(arguments, ctx)
            "shell_job_get" -> durableJobs.getShellJob(arguments, ctx)
            "shell_job_wait" -> durableJobs.waitForShellJob(arguments, ctx)
            "shell_job_stop" -> stopShellJob(arguments, ctx)
            "file_read" -> executeFileRead(arguments, ctx)
            "file_write" -> executeFileWrite(arguments, ctx)
            "file_transfer" -> executeFileTransfer(arguments, ctx)
            "file_edit" -> executeFileEdit(arguments, ctx, "file_edit")
            "file_multi_edit" -> executeFileEdit(arguments, ctx, "file_multi_edit")
            "file_glob" -> executeFileGlob(arguments, ctx)
            "file_grep" -> executeFileGrep(arguments, ctx)
            "file_view_image" -> executeViewImage(arguments, ctx).text
            else -> "Unknown tool: $name"
        }
    }

    override suspend fun executeOneShotResult(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): ToolExecutionResult {
        val result = if (BuiltInToolNames.canonical(name) == "file_view_image") {
            executeViewImage(arguments, ctx)
        } else ToolExecutionResult(execute(name, arguments, ctx))
        return shellModelFacingResult(name, arguments, result)
    }

    override fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = executeRawEvents(name, arguments, ctx).map { event ->
        if (event !is ToolExecutionEvent.Completed) event
        else ToolExecutionEvent.Completed(shellModelFacingResult(name, arguments, event.result))
    }

    private fun executeRawEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> {
        return when (BuiltInToolNames.canonical(name)) {
            "shell_exec" -> executeShellCommandEvents(arguments, ctx)
            "shell_job_wait" -> waitForShellJobEvents(arguments, ctx)
            "file_view_image" -> flow {
                emit(ToolExecutionEvent.Completed(executeViewImage(arguments, ctx)))
            }
            else -> super<ToolProvider>.executeEvents(name, arguments, ctx)
        }
    }

    override fun handles(name: String): Boolean = BuiltInToolNames.canonical(name) in setOf(
        "shell_list", "shell_exec",
        "shell_job_list", "shell_job_get", "shell_job_wait", "shell_job_stop",
        "file_read", "file_write", "file_transfer", "file_edit", "file_multi_edit", "file_glob", "file_grep", "file_view_image"
    )

    // ── shell_list ────────────────────────────────────────

    private suspend fun listShells(ctx: GenerationContext): String {
        val items = buildList {
            val sandboxOk = ctx.sandboxEnabled && sandbox?.isAvailableSync() == true
            if (sandboxOk) {
                add(buildJsonObject {
                    put("name", "Local Sandbox")
                    put("description", "Alpine Linux on-device")
                    put("type", "local")
                })
            }
            ctx.shellDevices.forEach { d ->
                add(buildJsonObject {
                    put("name", d.name.ifBlank { "Untitled" })
                    put("description", d.description)
                    put("type", d.type)
                    when (d.type) {
                        "ssh" -> { put("host", d.sshHost); put("port", d.sshPort) }
                        else -> put("url", d.serverUrl)
                    }
                })
            }
        }
        return buildJsonObject {
            put("type", "shell_list")
            putJsonArray("devices") { items.forEach { add(it) } }
        }.toString()
    }

    // ── Shell execution ────────────────────────────────────

    private suspend fun executeShellCommand(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val command = arg(args, "command")
        if (command.isBlank()) return jsonError("shell_exec", "no_command")
        val serverName = arg(args, "server")
        val workdir = arg(args, "workdir")
        shellCommandValidationError(command, workdir)?.let { message ->
            return jsonError("shell_exec", message, server = serverName)
        }
        val background = boolArg(args, "background")
        val foregroundMaxMs = Constants.TOOL_EXECUTION_TIMEOUT_MS.toInt()
        val timeoutMax = if (background) {
            ShellDurableJobExecutor.BACKGROUND_JOB_MAX_MS
        } else {
            foregroundMaxMs
        }
        val rawTimeout = arg(args, "timeout_ms")
        if (rawTimeout.isBlank()) return jsonError(
            "shell_exec", "timeout_ms is required", server = serverName, command = command,
        )
        val timeoutMs = (rawTimeout.toIntOrNull()
            ?: return jsonError(
                "shell_exec",
                "timeout_ms must be an integer, got \"$rawTimeout\"",
                server = serverName,
                command = command,
            )).coerceIn(1000, timeoutMax)
        if (background) {
            val target = resolveShellServerName(serverName, ctx)
            val backend = target?.let { getStandardBackend(it, ctx) }
                ?: return jsonError(
                    "shell_exec",
                    if (target == null) serverNotFoundMessage(serverName, ctx)
                    else standardServerNotFound(serverName, ctx),
                    server = serverName,
                    command = command,
                )
            return try {
                if (!confirmTarget(ctx, backend.device, "start background job: $ $command")) {
                    return jsonError(
                        "shell_exec",
                        "denied_by_user: the user declined to run this background command",
                        server = backend.serverName,
                        command = command,
                    )
                }
                backend.startJob(command, workdir, timeoutMs, arg(args, "description"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                jsonError(
                    "shell_exec",
                    e.message ?: "Failed to start background job",
                    server = backend.serverName,
                    command = command,
                )
            } finally {
                backend.close()
            }
        }

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("shell_exec", serverNotFoundMessage(serverName, ctx))
        return try {
            // Confirmation follows the resolved target, including the local sandbox.
            if (!confirmTarget(ctx, backend.device, "$ $command")) {
                return jsonError("shell_exec", "denied_by_user: the user declined to run this command", server = serverName, command = command)
            }
            if (backend.supportsStandardTools) {
                durableJobs.executeDurableForeground(
                    backend = backend,
                    command = command,
                    workdir = workdir,
                    waitMs = timeoutMs.coerceAtMost(maxWaitMs(ctx)),
                    description = arg(args, "description"),
                )
            } else {
                backend.executeCommand(command, workdir, timeoutMs)
            }
        } finally {
            backend.close()
        }
    }

    /**
     * Starts a standard-server command as a durable job, then treats foreground execution as a bounded wait
     * on that same process. A wait expiry returns ownership to the model through job_id; it never
     * kills or replays the command.
     */

    private fun executeShellCommandEvents(
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        val args = parseToolArgs(arguments)
        val command = arg(args, "command")
        if (command.isBlank()) {
            emit(ToolExecutionEvent.Completed(jsonError("shell_exec", "no_command")))
            return@flow
        }
        val serverName = arg(args, "server")
        val workdir = arg(args, "workdir")
        shellCommandValidationError(command, workdir)?.let { message ->
            emit(
                ToolExecutionEvent.Completed(
                    jsonError("shell_exec", message, server = serverName),
                ),
            )
            return@flow
        }
        if (boolArg(args, "background")) {
            val target = resolveShellServerName(serverName, ctx)
            if (target == null || target !in shellServerNames(ctx, standardOnly = true)) {
                emit(
                    ToolExecutionEvent.Completed(
                        jsonError(
                            "shell_exec",
                            if (target == null) serverNotFoundMessage(serverName, ctx)
                            else standardServerNotFound(serverName, ctx),
                            server = serverName,
                            command = command,
                        ),
                    ),
                )
                return@flow
            }
            emit(ToolExecutionEvent.TargetResolved(target))
            emit(ToolExecutionEvent.Progress("Starting durable background job"))
            // executeShellCommand owns the one confirmation and backend lifecycle.
            emit(ToolExecutionEvent.Completed(executeShellCommand(arguments, ctx)))
            return@flow
        }
        val foregroundMaxMs = Constants.TOOL_EXECUTION_TIMEOUT_MS.toInt()
        val rawTimeout = arg(args, "timeout_ms")
        if (rawTimeout.isBlank()) {
            emit(
                ToolExecutionEvent.Completed(
                    jsonError(
                        "shell_exec", "timeout_ms is required",
                        server = serverName, command = command,
                    ),
                ),
            )
            return@flow
        }
        val timeoutMs = (rawTimeout.toIntOrNull()
            ?: run {
                emit(
                    ToolExecutionEvent.Completed(
                        jsonError(
                            "shell_exec",
                            "timeout_ms must be an integer, got \"$rawTimeout\"",
                            server = serverName,
                            command = command,
                        ),
                    ),
                )
                return@flow
            }).coerceIn(1000, foregroundMaxMs)
        val backend = getBackend(serverName, ctx)
        if (backend == null) {
            emit(
                ToolExecutionEvent.Completed(
                    jsonError("shell_exec", serverNotFoundMessage(serverName, ctx)),
                ),
            )
            return@flow
        }
        emit(
            ToolExecutionEvent.TargetResolved(
                backend.device?.name?.ifBlank { "Untitled" } ?: "Local Sandbox",
            ),
        )
        try {
            if (!confirmTarget(ctx, backend.device, "$ $command")) {
                emit(
                    ToolExecutionEvent.Completed(
                        jsonError(
                            "shell_exec",
                            "denied_by_user: the user declined to run this command",
                            server = serverName,
                            command = command,
                        ),
                    ),
                )
                return@flow
            }
            if (backend.supportsStandardTools) {
                emit(ToolExecutionEvent.Progress("Running as a durable foreground job"))
                emit(
                    ToolExecutionEvent.Completed(
                        durableJobs.executeDurableForeground(
                            backend = backend,
                            command = command,
                            workdir = workdir,
                            waitMs = timeoutMs.coerceAtMost(maxWaitMs(ctx)),
                            description = arg(args, "description"),
                            onOutput = { delta ->
                                emit(ToolExecutionEvent.OutputDelta(delta))
                            },
                        )
                    )
                )
            } else {
                emit(ToolExecutionEvent.Progress("Running command"))
                backend.executeCommandEvents(command, workdir, timeoutMs).collect { emit(it) }
            }
        } finally {
            backend.close()
        }
    }

    private fun waitForShellJobEvents(
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        val serverName = arg(parseToolArgs(arguments), "server")
        resolveShellServerName(serverName, ctx, standardOnly = true)?.let { target ->
            emit(ToolExecutionEvent.TargetResolved(target))
        }
        emit(ToolExecutionEvent.Progress("Waiting for durable job"))
        emit(
            ToolExecutionEvent.Completed(
                durableJobs.waitForShellJob(arguments, ctx) { snapshot ->
                    emit(ToolExecutionEvent.OutputSnapshot(snapshot))
                },
            ),
        )
    }

    // ── Durable job mutations ──────────────────────────────

    private suspend fun stopShellJob(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val jobId = arg(args, "job_id")
        if (jobId.isBlank()) return jsonError("shell_job_stop", "job_id is required")
        val serverName = arg(args, "server")
        val backend = getStandardBackend(serverName, ctx)
            ?: return jsonError(
                "shell_job_stop",
                standardServerNotFound(serverName, ctx),
                server = serverName,
            )
        return try {
            if (!confirmTarget(ctx, backend.device, "stop background shell job: $jobId")) {
                return jsonError(
                    "shell_job_stop",
                    "denied_by_user: the user declined to stop this job",
                    server = backend.serverName,
                )
            }
            backend.stopJob(jobId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            jsonError(
                "shell_job_stop",
                e.message ?: "Failed to stop shell job",
                server = backend.serverName,
            )
        } finally {
            backend.close()
        }
    }

    // ── File tools ─────────────────────────────────────────

    private suspend fun executeViewImage(
        arguments: String,
        ctx: GenerationContext,
    ): ToolExecutionResult {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) {
            return ToolExecutionResult(
                text = jsonError("file_view_image", "path is required"),
                isError = true,
            )
        }
        val serverName = arg(args, "server")
        val store = imageStore
            ?: return ToolExecutionResult(
                text = jsonError(
                    "file_view_image",
                    "Tool image storage is unavailable",
                    server = serverName,
                ),
                isError = true,
            )
        val backend = getStandardBackend(serverName, ctx)
            ?: return ToolExecutionResult(
                text = jsonError(
                    "file_view_image",
                    standardServerNotFound(serverName, ctx),
                    server = serverName,
                ),
                isError = true,
            )
        var prepared: com.newoether.agora.model.ToolImageAttachment? = null
        return try {
            val attachment = withContext(Dispatchers.IO) {
                backend.loadImage(path, store).also { prepared = it }
            }
            ToolExecutionResult(
                text = buildJsonObject {
                    put("type", "file_view_image")
                    put("server", backend.serverName)
                    put("path", path)
                    put("mime_type", attachment.mimeType)
                    put("size", attachment.sizeBytes)
                    attachment.width?.let { put("width", it) }
                    attachment.height?.let { put("height", it) }
                    put("ok", true)
                }.toString(),
                images = listOf(attachment),
                transcribeImages = true,
            )
        } catch (cancelled: CancellationException) {
            prepared?.let { java.io.File(it.path).delete() }
            throw cancelled
        } catch (error: Exception) {
            ToolExecutionResult(
                text = jsonError(
                    "file_view_image",
                    error.message ?: "Failed to load image",
                    server = backend.serverName,
                ),
                isError = true,
            )
        } finally {
            backend.close()
        }
    }

    private suspend fun executeFileRead(arguments: String, ctx: GenerationContext): String =
        executeDiskFileRead(
            arguments = arguments,
            openBackend = { server -> getBackend(server, ctx) },
            missingServer = { server -> serverNotFoundMessage(server, ctx) },
        )

    private suspend fun executeFileTransfer(arguments: String, ctx: GenerationContext): String =
        executeShellFileTransfer(arguments, ctx, sandbox) { device, summary ->
            confirmTarget(ctx, device, summary)
        }

    private suspend fun executeFileWrite(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) return jsonError("file_write", "path is required")
        val content = arg(args, "content")
        // Whitespace-only content is legitimate; only a missing field is an error.
        if (args["content"] !is JsonPrimitive) return jsonError("file_write", "content is required")
        if (content.toByteArray(Charsets.UTF_8).size > SHELL_FILE_WRITE_MAX_BYTES) {
            return jsonError("file_write", "content exceeds 1MB limit")
        }
        val serverName = arg(args, "server")

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("file_write", serverNotFoundMessage(serverName, ctx))
        try {
            if (!confirmTarget(
                    ctx,
                    backend.device,
                    "write file: $path",
                    ShellActionDetail.Write(path, content),
                )
            ) {
                return jsonError("file_write", "denied_by_user: the user declined to write this file", server = serverName)
            }
            val error = backend.fileWrite(path, content, arg(args, "expected_sha256").trim())
            if (error != null) return error
            return buildJsonObject {
                put("type", "file_write"); put("path", path); put("ok", true)
                put("sha256", shellFileSha256(content.toByteArray(Charsets.UTF_8)))
            }.toString()
        } finally {
            backend.close()
        }
    }

    /**
     * Serves file_edit and file_multi_edit; each tool parses only its own schema. Successful
     * results keep type "file_edit" so both render with the same detail view.
     */
    private suspend fun executeFileEdit(arguments: String, ctx: GenerationContext, toolName: String): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) return jsonError(toolName, "path is required")
        val parsed = if (toolName == "file_multi_edit") parseFileMultiEditArgs(args) else parseFileEditArgs(args)
        val edits = parsed.getOrElse { e ->
            return jsonError(toolName, e.message ?: "invalid edit arguments")
        }
        val serverName = arg(args, "server")

        val backend = getBackend(serverName, ctx)
            ?: return jsonError(toolName, serverNotFoundMessage(serverName, ctx))
        try {
            if (!confirmTarget(
                    ctx,
                    backend.device,
                    "edit file: $path",
                    ShellActionDetail.Edit(path, edits),
                )
            ) {
                return jsonError(toolName, "denied_by_user: the user declined to edit this file", server = serverName)
            }
            val result = try {
                backend.fileEdit(path, edits, arg(args, "expected_sha256").trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return jsonError(
                    toolName,
                    e.message ?: "Edit failed",
                    server = backend.device?.name ?: "Local Sandbox",
                )
            }
            result.error?.let {
                return jsonError(
                    toolName,
                    it,
                    server = backend.device?.name ?: "Local Sandbox",
                )
            }
            return fileEditSuccessJson(backend.device?.name ?: "Local Sandbox", path, result, edits.size)
        } finally {
            backend.close()
        }
    }

    private suspend fun executeFileGlob(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val pattern = arg(args, "pattern")
        if (pattern.isBlank()) return jsonError("file_glob", "pattern is required")
        val serverName = arg(args, "server")
        val basePath = arg(args, "path")
        // Absent/blank → null → backward-compatible default behavior per backend.
        val depth = arg(args, "depth").toIntOrNull()

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("file_glob", serverNotFoundMessage(serverName, ctx))
        try {
            val result = backend.fileGlob(pattern, basePath, depth)
            return result.fold(
                onSuccess = { (files, truncated) ->
                    buildJsonObject {
                        put("type", "file_glob"); put("pattern", pattern)
                        putJsonArray("files") { files.forEach { add(JsonPrimitive(it)) } }
                        put("truncated", truncated)
                    }.toString()
                },
                onFailure = { e -> jsonError("file_glob", e.message ?: "Unknown error") }
            )
        } finally {
            backend.close()
        }
    }

    private suspend fun executeFileGrep(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val pattern = arg(args, "pattern")
        if (pattern.isBlank()) return jsonError("file_grep", "pattern is required")
        val serverName = arg(args, "server")
        val basePath = arg(args, "path")
        val fileGlob = arg(args, "glob")
        val rawContext = arg(args, "context")
        val requestedContext = if (rawContext.isBlank()) 0 else rawContext.toIntOrNull()
            ?: return jsonError("file_grep", "context must be an integer")
        val context = requestedContext.coerceIn(0, SHELL_FILE_GREP_MAX_CONTEXT_LINES)
        val options = ShellGrepOptions(
            caseInsensitive = boolArg(args, "case_insensitive"),
            context = context,
        )

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("file_grep", serverNotFoundMessage(serverName, ctx))
        try {
            val result = backend.fileGrep(pattern, basePath, fileGlob, options)
            return result.fold(
                onSuccess = { grep -> fileGrepJson(pattern, grep, contextRequested = context > 0) },
                onFailure = { e -> jsonError("file_grep", e.message ?: "Unknown error") }
            )
        } finally {
            backend.close()
        }
    }

    companion object {
        private const val JOB_ACK_TIMEOUT_MS = 3_000L

        internal fun maxWaitMs(ctx: GenerationContext): Int =
            ShellDurableJobExecutor.maxWaitMs(ctx)
    }
}
