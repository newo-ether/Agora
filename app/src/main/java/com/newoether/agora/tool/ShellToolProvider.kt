package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.util.Constants
import com.newoether.agora.util.SHELL_FILE_WRITE_MAX_BYTES
import com.newoether.agora.util.SHELL_FILE_GREP_MAX_CONTEXT_LINES
import com.newoether.agora.util.ShellGrepOptions
import com.newoether.agora.util.shellFileSha256
import com.newoether.agora.viewmodel.GenerationContext
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

class ShellToolProvider(
    private val sandboxFactory: SandboxManagerFactory? = null,
    private val imageStore: ToolImageStore? = null,
) : ToolProvider {

    private val sandbox = sandboxFactory?.create()
    private val durableJobs = ShellDurableJobExecutor()

    /**
     * Optional user-confirmation gate for state-changing operations. The isolated local sandbox
     * normally proceeds directly; once shared storage is mounted, local commands/writes are gated
     * too because they can mutate files outside the app sandbox.
     */
    var confirm: (suspend (server: String, summary: String, conversationId: String?) -> Boolean)? = null

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
                        val backend = getConchBackend(acknowledgement.serverName, context)
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
        localSharedStorageExposed: Boolean = false,
    ): Boolean {
        if (device == null && !localSharedStorageExposed) return true
        val target = device?.name?.ifBlank { "${device.type} server" }
            ?: "Local Sandbox · /mnt/shared"
        return confirm?.invoke(target, summary, ctx.conversationId) ?: true
    }

    // ── Helpers ────────────────────────────────────────────

    private fun resolveShellDevice(serverName: String, ctx: GenerationContext): ShellDeviceConfig? {
        if (serverName.equals("Local Sandbox", ignoreCase = true)) return null
        return if (serverName.isNotBlank()) {
            // singleOrNull: a duplicate name is ambiguous -> null -> serverNotFoundMessage() explains it.
            ctx.shellDevices.filter { it.name.equals(serverName, ignoreCase = true) }.singleOrNull()
        } else if (ctx.shellDevices.size == 1) ctx.shellDevices.first() else null
    }

    private fun isAmbiguousServer(serverName: String, ctx: GenerationContext): Boolean =
        serverName.isNotBlank() &&
            ctx.shellDevices.count { it.name.equals(serverName, ignoreCase = true) } > 1

    private fun serverNotFoundMessage(serverName: String, ctx: GenerationContext): String {
        if (isAmbiguousServer(serverName, ctx)) {
            val dupes = ctx.shellDevices.filter { it.name.equals(serverName, ignoreCase = true) }
                .joinToString(", ") { "${it.name} (type: ${it.type})" }
            return "Ambiguous server: \"$serverName\" matches more than one configured device " +
                "[$dupes]. Rename one of them so each shell server has a unique name, then retry."
        }
        val hasSandbox = ctx.sandboxEnabled && sandboxFactory?.isAvailable() == true
        val allNames = buildList {
            if (hasSandbox) add("\"Local Sandbox\"")
            addAll(ctx.shellDevices.map { "\"${it.name}\"" })
        }
        return if (allNames.size == 1) {
            "Unknown server: $serverName. Use ${allNames[0]} or omit the server parameter."
        } else {
            val names = allNames.joinToString(", ")
            if (serverName.isBlank()) "Multiple servers available. Use list_shells to see them, then specify one: $names."
            else "Unknown server: $serverName. Available: $names."
        }
    }

    private suspend fun getBackend(serverName: String, ctx: GenerationContext): Backend? {
        // Local Sandbox
        if (serverName.equals("Local Sandbox", ignoreCase = true) && ctx.sandboxEnabled) {
            if (sandbox?.isAvailable() == true) return SandboxBackend(sandbox)
            if (sandbox != null) return null
        }
        if (serverName.isBlank()) {
            if (ctx.sandboxEnabled && sandbox?.isAvailable() == true) {
                return SandboxBackend(sandbox)
            }
        }
        val device = resolveShellDevice(serverName, ctx) ?: return null
        return when (device.type) {
            "ssh" -> SshBackend(device)
            else -> ConchBackend(device)
        }
    }

    // ── ToolProvider interface ─────────────────────────────

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        ShellToolDefinitions.build(ctx)

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        return when (name) {
            "list_shells" -> listShells(ctx)
            "execute_shell_command" -> executeShellCommand(arguments, ctx)
            "list_shell_jobs" -> durableJobs.listShellJobs(arguments, ctx)
            "get_shell_job" -> durableJobs.getShellJob(arguments, ctx)
            "wait_for_job" -> durableJobs.waitForShellJob(arguments, ctx)
            "stop_shell_job" -> stopShellJob(arguments, ctx)
            "file_read" -> executeFileRead(arguments, ctx)
            "file_write" -> executeFileWrite(arguments, ctx)
            "file_edit" -> executeFileEdit(arguments, ctx, "file_edit")
            "file_multi_edit" -> executeFileEdit(arguments, ctx, "file_multi_edit")
            "file_glob" -> executeFileGlob(arguments, ctx)
            "file_grep" -> executeFileGrep(arguments, ctx)
            "view_image" -> executeViewImage(arguments, ctx).text
            else -> "Unknown tool: $name"
        }
    }

    override suspend fun executeOneShotResult(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): ToolExecutionResult = shellModelFacingResult(name, arguments, ToolExecutionResult(execute(name, arguments, ctx)))

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
        return when (name) {
            "execute_shell_command" -> executeShellCommandEvents(arguments, ctx)
            "wait_for_job" -> waitForShellJobEvents(arguments, ctx)
            "view_image" -> flow {
                emit(ToolExecutionEvent.Completed(executeViewImage(arguments, ctx)))
            }
            else -> super<ToolProvider>.executeEvents(name, arguments, ctx)
        }
    }

    override fun handles(name: String): Boolean = name in setOf(
        "list_shells", "execute_shell_command",
        "list_shell_jobs", "get_shell_job", "wait_for_job", "stop_shell_job",
        "file_read", "file_write", "file_edit", "file_multi_edit", "file_glob", "file_grep", "view_image"
    )

    // ── list_shells ────────────────────────────────────────

    private suspend fun listShells(ctx: GenerationContext): String {
        val items = buildList {
            val sandboxOk = ctx.sandboxEnabled && sandbox?.isAvailable() == true
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
            put("type", "list_shells")
            putJsonArray("devices") { items.forEach { add(it) } }
        }.toString()
    }

    // ── Shell execution ────────────────────────────────────

    private suspend fun executeShellCommand(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val command = arg(args, "command")
        if (command.isBlank()) return jsonError("execute_shell_command", "no_command")
        val serverName = arg(args, "server")
        val workdir = arg(args, "workdir")
        shellCommandValidationError(command, workdir)?.let { message ->
            return jsonError("execute_shell_command", message, server = serverName)
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
            "execute_shell_command", "timeout_ms is required", server = serverName, command = command,
        )
        val timeoutMs = (rawTimeout.toIntOrNull()
            ?: return jsonError(
                "execute_shell_command",
                "timeout_ms must be an integer, got \"$rawTimeout\"",
                server = serverName,
                command = command,
            )).coerceIn(1000, timeoutMax)
        if (background) {
            val backend = getConchBackend(serverName, ctx)
                ?: return jsonError(
                    "execute_shell_command",
                    conchServerNotFoundMessage(serverName, ctx),
                    server = serverName,
                    command = command,
                )
            return try {
                if (!confirmTarget(ctx, backend.device, "start background job: $ $command")) {
                    return jsonError(
                        "execute_shell_command",
                        "denied_by_user: the user declined to run this background command",
                        server = backend.device.name,
                        command = command,
                    )
                }
                backend.startJob(command, workdir, timeoutMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                jsonError(
                    "execute_shell_command",
                    e.message ?: "Failed to start background job",
                    server = backend.device.name,
                    command = command,
                )
            } finally {
                backend.close()
            }
        }

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("execute_shell_command", serverNotFoundMessage(serverName, ctx))
        return try {
            // Gate on the backend's ACTUAL target: with a blank server name the sandbox wins
            // resolution, while resolveShellDevice() would name an unrelated remote device.
            if (!confirmTarget(
                    ctx,
                    backend.device,
                    "$ $command",
                    localSharedStorageExposed =
                        backend.device == null && ctx.sandboxSharedStorageEnabled,
                )
            ) {
                return jsonError("execute_shell_command", "denied_by_user: the user declined to run this command", server = serverName, command = command)
            }
            if (backend is ConchBackend) {
                durableJobs.executeDurableForeground(
                    backend = backend,
                    command = command,
                    workdir = workdir,
                    waitMs = timeoutMs.coerceAtMost(maxWaitMs(ctx)),
                )
            } else {
                backend.executeCommand(command, workdir, timeoutMs)
            }
        } finally {
            backend.close()
        }
    }

    /**
     * Starts a Conch command as a durable job, then treats foreground execution as a bounded wait
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
            emit(ToolExecutionEvent.Completed(jsonError("execute_shell_command", "no_command")))
            return@flow
        }
        val serverName = arg(args, "server")
        val workdir = arg(args, "workdir")
        shellCommandValidationError(command, workdir)?.let { message ->
            emit(
                ToolExecutionEvent.Completed(
                    jsonError("execute_shell_command", message, server = serverName),
                ),
            )
            return@flow
        }
        if (boolArg(args, "background")) {
            val device = resolveShellDevice(serverName, ctx)?.takeIf { it.type != "ssh" }
            if (device == null) {
                emit(
                    ToolExecutionEvent.Completed(
                        jsonError(
                            "execute_shell_command",
                            conchServerNotFoundMessage(serverName, ctx),
                            server = serverName,
                            command = command,
                        ),
                    ),
                )
                return@flow
            }
            emit(ToolExecutionEvent.TargetResolved(device.name))
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
                        "execute_shell_command", "timeout_ms is required",
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
                            "execute_shell_command",
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
                    jsonError("execute_shell_command", serverNotFoundMessage(serverName, ctx)),
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
            if (!confirmTarget(
                    ctx,
                    backend.device,
                    "$ $command",
                    localSharedStorageExposed =
                        backend.device == null && ctx.sandboxSharedStorageEnabled,
                )
            ) {
                emit(
                    ToolExecutionEvent.Completed(
                        jsonError(
                            "execute_shell_command",
                            "denied_by_user: the user declined to run this command",
                            server = serverName,
                            command = command,
                        ),
                    ),
                )
                return@flow
            }
            if (backend is ConchBackend) {
                emit(ToolExecutionEvent.Progress("Running as a durable foreground job"))
                emit(
                    ToolExecutionEvent.Completed(
                        durableJobs.executeDurableForeground(
                            backend = backend,
                            command = command,
                            workdir = workdir,
                            waitMs = timeoutMs.coerceAtMost(maxWaitMs(ctx)),
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
        resolveConchDevice(serverName, ctx)?.let { device ->
            emit(ToolExecutionEvent.TargetResolved(device.name))
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
        if (jobId.isBlank()) return jsonError("stop_shell_job", "job_id is required")
        val serverName = arg(args, "server")
        val backend = getConchBackend(serverName, ctx)
            ?: return jsonError(
                "stop_shell_job",
                conchServerNotFoundMessage(serverName, ctx),
                server = serverName,
            )
        return try {
            if (!confirmTarget(ctx, backend.device, "stop background shell job: $jobId")) {
                return jsonError(
                    "stop_shell_job",
                    "denied_by_user: the user declined to stop this job",
                    server = backend.device.name,
                )
            }
            backend.stopJob(jobId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            jsonError(
                "stop_shell_job",
                e.message ?: "Failed to stop shell job",
                server = backend.device.name,
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
                text = jsonError("view_image", "path is required"),
                isError = true,
            )
        }
        val serverName = arg(args, "server")
        val store = imageStore
            ?: return ToolExecutionResult(
                text = jsonError(
                    "view_image",
                    "Tool image storage is unavailable",
                    server = serverName,
                ),
                isError = true,
            )
        val backend = getConchBackend(serverName, ctx)
            ?: return ToolExecutionResult(
                text = jsonError(
                    "view_image",
                    conchServerNotFoundMessage(serverName, ctx),
                    server = serverName,
                ),
                isError = true,
            )
        return try {
            val remote = backend.viewImage(path)
            if (remote.error != null) {
                return ToolExecutionResult(
                    text = jsonError(
                        "view_image",
                        remote.error,
                        server = backend.device.name,
                    ),
                    isError = true,
                )
            }
            val attachment = withContext(Dispatchers.IO) {
                store.persistBase64(
                    data = remote.data,
                    mimeType = remote.mimeType,
                    filePrefix = "conch",
                )
            }
            ToolExecutionResult(
                text = buildJsonObject {
                    put("type", "view_image")
                    put("server", backend.device.name)
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
        } catch (error: Exception) {
            ToolExecutionResult(
                text = jsonError(
                    "view_image",
                    error.message ?: "Failed to load image",
                    server = backend.device.name,
                ),
                isError = true,
            )
        } finally {
            backend.close()
        }
    }

    private suspend fun executeFileRead(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) return jsonError("file_read", "path is required")
        val serverName = arg(args, "server")
        val (readArgs, argError) = parseFileReadArgs(args)
        if (readArgs == null) return jsonError("file_read", argError ?: "Invalid arguments")
        val offset = readArgs.offset
        val limit = readArgs.limit
        val lineMode = readArgs.lineMode
        val startLine = readArgs.startLine
        val maxLines = readArgs.maxLines

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("file_read", serverNotFoundMessage(serverName, ctx))
        val serverLabel = backend.device?.name ?: "Local Sandbox"
        if (lineMode) {
            try {
                return lineModeFileReadJson(serverLabel, path, offset, startLine, maxLines) { at, size ->
                    backend.fileRead(path, at, size)
                }
            } finally {
                backend.close()
            }
        }
        try {
            val result = try {
                backend.fileRead(path, offset, limit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return jsonError(
                    "file_read",
                    (e.message ?: "Read failed") +
                        storageHint(backend.device, path),
                    server = backend.device?.name ?: "Local Sandbox",
                )
            }
            result.error?.let {
                return jsonError(
                    "file_read",
                    it + storageHint(backend.device, path),
                    server = backend.device?.name ?: "Local Sandbox",
                )
            }
            return boundedFileReadJson(
                server = backend.device?.name ?: "Local Sandbox",
                path = path,
                result = result,
            )
        } finally {
            backend.close()
        }
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
                    localSharedStorageExposed =
                        backend.device == null && targetsSharedStorage(path),
                )
            ) {
                return jsonError("file_write", "denied_by_user: the user declined to write this file", server = serverName)
            }
            val error = backend.fileWrite(path, content, arg(args, "expected_sha256").trim())
            if (error != null) return jsonError(
                "file_write",
                error + storageHint(backend.device, path),
                server = backend.device?.name ?: "Local Sandbox",
            )
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
                    localSharedStorageExposed =
                        backend.device == null && targetsSharedStorage(path),
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
                    (e.message ?: "Edit failed") +
                        storageHint(backend.device, path),
                    server = backend.device?.name ?: "Local Sandbox",
                )
            }
            result.error?.let {
                return jsonError(
                    toolName,
                    it + storageHint(backend.device, path),
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
