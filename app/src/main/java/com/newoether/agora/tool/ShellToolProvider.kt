package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.util.Constants
import com.newoether.agora.util.SHELL_COMMAND_MAX_BYTES
import com.newoether.agora.util.SHELL_WORKDIR_MAX_BYTES
import com.newoether.agora.util.SHELL_FILE_WRITE_MAX_BYTES
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
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

    private fun targetsSharedStorage(path: String): Boolean {
        val normalized = path.trim().replace('\\', '/').replace(Regex("/+"), "/")
        return normalized == "/mnt/shared" || normalized.startsWith("/mnt/shared/")
    }

    /**
     * Explains an inaccessible local path in terms of Android's storage rules so the model can
     * recover instead of retrying blindly. Only meaningful for the local sandbox backend
     * (device == null); remote servers have their own permissions.
     */
    private fun storageHint(device: ShellDeviceConfig?, path: String): String {
        if (device != null) return ""
        val normalized = path.trim().replace('\\', '/').replace(Regex("/+"), "/")
        return when {
            targetsSharedStorage(normalized) ->
                " — /mnt/shared maps the device's shared storage; if access fails, the sandbox" +
                    " may not have shared storage mounted (enable it in Agora's sandbox settings)" +
                    " or Android denies the app All-Files access."
            normalized.startsWith("/sdcard") || normalized.startsWith("/storage/emulated") ->
                " — Android scoped storage blocks direct /sdcard access on Android 10+;" +
                    " use /mnt/shared instead."
            else -> ""
        }
    }

    // ── Helpers ────────────────────────────────────────────

    private fun resolveShellDevice(serverName: String, ctx: GenerationContext): ShellDeviceConfig? {
        if (serverName.equals("Local Sandbox", ignoreCase = true)) return null
        return if (serverName.isNotBlank()) {
            ctx.shellDevices.find { it.name.equals(serverName, ignoreCase = true) }
        } else if (ctx.shellDevices.size == 1) {
            ctx.shellDevices.first()
        } else null
    }

    private fun serverNotFoundMessage(serverName: String, ctx: GenerationContext): String {
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
            "file_edit" -> executeFileEdit(arguments, ctx)
            "file_glob" -> executeFileGlob(arguments, ctx)
            "file_grep" -> executeFileGrep(arguments, ctx)
            "view_image" -> executeViewImage(arguments, ctx).text
            else -> "Unknown tool: $name"
        }
    }

    override fun executeEvents(
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
        "file_read", "file_write", "file_edit", "file_glob", "file_grep", "view_image"
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
        val offset = arg(args, "offset").toLongOrNull() ?: 0L
        val limit = arg(args, "limit").toLongOrNull() ?: 0L

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("file_read", serverNotFoundMessage(serverName, ctx))
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
        if (content.isBlank()) return jsonError("file_write", "content is required")
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
            val error = backend.fileWrite(path, content)
            if (error != null) return jsonError(
                "file_write",
                error + storageHint(backend.device, path),
                server = backend.device?.name ?: "Local Sandbox",
            )
            return buildJsonObject {
                put("type", "file_write"); put("path", path); put("ok", true)
            }.toString()
        } finally {
            backend.close()
        }
    }

    private suspend fun executeFileEdit(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) return jsonError("file_edit", "path is required")
        val oldStr = arg(args, "old_string")
        if (oldStr.isBlank()) return jsonError("file_edit", "old_string is required")
        val newStr = arg(args, "new_string")
        val replaceAll = arg(args, "replace_all").equals("true", ignoreCase = true)
        val serverName = arg(args, "server")

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("file_edit", serverNotFoundMessage(serverName, ctx))
        try {
            if (!confirmTarget(
                    ctx,
                    backend.device,
                    "edit file: $path",
                    localSharedStorageExposed =
                        backend.device == null && targetsSharedStorage(path),
                )
            ) {
                return jsonError("file_edit", "denied_by_user: the user declined to edit this file", server = serverName)
            }
            val result = try {
                backend.fileEdit(path, oldStr, newStr, replaceAll)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return jsonError(
                    "file_edit",
                    (e.message ?: "Edit failed") +
                        storageHint(backend.device, path),
                    server = backend.device?.name ?: "Local Sandbox",
                )
            }
            result.error?.let {
                return jsonError(
                    "file_edit",
                    it + storageHint(backend.device, path),
                    server = backend.device?.name ?: "Local Sandbox",
                )
            }
            return buildJsonObject {
                put("type", "file_edit")
                put("server", backend.device?.name ?: "Local Sandbox")
                put("path", path)
                put("replaced", result.replacements)
                if (result.sha256.isNotBlank()) put("sha256", result.sha256)
            }.toString()
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

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("file_grep", serverNotFoundMessage(serverName, ctx))
        try {
            val result = backend.fileGrep(pattern, basePath, fileGlob)
            return result.fold(
                onSuccess = { (matches, truncated) ->
                    buildJsonObject {
                        put("type", "file_grep"); put("pattern", pattern)
                        putJsonArray("matches") {
                            matches.forEach { m ->
                                add(buildJsonObject {
                                    put("path", m.path); put("line", m.line); put("content", m.content)
                                })
                            }
                        }
                        put("truncated", truncated)
                    }.toString()
                },
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
