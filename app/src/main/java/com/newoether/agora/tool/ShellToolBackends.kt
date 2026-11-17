package com.newoether.agora.tool

import com.newoether.agora.api.HttpClient
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.util.SHELL_FILE_READ_MAX_BYTES
import com.newoether.agora.util.ShellClient
import com.newoether.agora.util.ShellFileEdit
import com.newoether.agora.util.ShellFileEditResult
import com.newoether.agora.util.ShellFileReadResult
import com.newoether.agora.util.ShellGrepOptions
import com.newoether.agora.util.ShellGrepResult
import com.newoether.agora.util.SshClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.put

/** Schema and dispatch share the same eligible target set, including the local server. */
internal fun shellServerNames(ctx: GenerationContext, standardOnly: Boolean = false): List<String> =
    buildList {
        if (ctx.sandboxEnabled) add("Local Sandbox")
        addAll(ctx.shellDevices.filter { !standardOnly || it.type != "ssh" }.map { it.name })
    }

internal fun resolveShellServerName(
    name: String,
    ctx: GenerationContext,
    standardOnly: Boolean = false,
): String? {
    val names = shellServerNames(ctx, standardOnly)
    return if (name.isBlank()) names.singleOrNull()
    else names.singleOrNull { it.equals(name, ignoreCase = true) }
}

internal fun openShellBackend(
    name: String,
    ctx: GenerationContext,
    sandbox: SandboxManager?,
    standardOnly: Boolean = false,
): Backend? {
    val resolved = resolveShellServerName(name, ctx, standardOnly) ?: return null
    if (resolved == "Local Sandbox") {
        // Availability discovery must not wait for a running command's rootfs mutation lock.
        return sandbox?.takeIf { it.isAvailableSync() }?.let(::SandboxBackend)
    }
    val device = ctx.shellDevices.singleOrNull { it.name == resolved } ?: return null
    return if (device.type == "ssh") SshBackend(device) else ConchBackend(device)
}

internal fun shellServerNotFoundMessage(
    name: String,
    ctx: GenerationContext,
    standardOnly: Boolean = false,
): String {
    val names = shellServerNames(ctx, standardOnly)
    return when {
        names.isEmpty() -> "No supported shell server is enabled."
        name.isBlank() && names.size > 1 ->
            "Multiple servers available. Specify one: ${names.joinToString(", ")}."
        name.isBlank() || names.any { it.equals(name, ignoreCase = true) } ->
            "Shell server ${name.ifBlank { names.single() }} is unavailable."
        else -> "Unknown or unsupported server: $name. Available: ${names.joinToString(", ")}."
    }
}

internal sealed interface Backend {
    /** Null denotes the local target; shared-storage mutations still require confirmation. */
    val device: ShellDeviceConfig?
    val serverName: String get() = device?.name ?: "Local Sandbox"
    val supportsStandardTools: Boolean get() = device?.type != "ssh"
    suspend fun startJob(cmd: String, workdir: String, timeoutMs: Int, description: String = ""): String =
        error("Durable jobs are not supported on $serverName")
    suspend fun listJobs(offset: Int = 0, limit: Int = 100): String =
        error("Durable jobs are not supported on $serverName")
    suspend fun getJob(jobId: String): String = error("Durable jobs are not supported on $serverName")
    suspend fun stopJob(jobId: String, callTimeoutMillis: Long? = null): String =
        error("Durable jobs are not supported on $serverName")
    suspend fun acknowledgeJob(jobId: String): String = error("Durable jobs are not supported on $serverName")
    suspend fun loadImage(path: String, store: ToolImageStore): ToolImageAttachment =
        error("Image reads are not supported on $serverName")
    suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String
    fun executeCommandEvents(
        cmd: String,
        workdir: String,
        timeoutMs: Int,
    ): Flow<ToolExecutionEvent> = flow {
        emit(ToolExecutionEvent.Completed(executeCommand(cmd, workdir, timeoutMs)))
    }
    suspend fun fileRead(path: String, offset: Long, limit: Long): ShellFileReadResult
    /** A non-blank [expectedSha256] must match the current file or nothing is written. */
    suspend fun fileWrite(path: String, content: String, expectedSha256: String = ""): String?
    /** Applies ordered edits atomically: all succeed or nothing is written. */
    suspend fun fileEdit(
        path: String,
        edits: List<ShellFileEdit>,
        expectedSha256: String = "",
    ): ShellFileEditResult
    suspend fun fileGlob(
        pattern: String,
        basePath: String,
        depth: Int?,
    ): Result<Pair<List<String>, Boolean>>
    suspend fun fileGrep(
        pattern: String,
        basePath: String,
        fileGlob: String,
        options: ShellGrepOptions,
    ): Result<ShellGrepResult>
    fun close()
}

internal class ConchBackend(override val device: ShellDeviceConfig) : Backend {
    private val url = device.serverUrl.trimEnd('/')
    private val apiKey = device.apiKey
    private val pubKey = device.conchPublicKey
    private val deviceName = device.name

    private val client: ShellClient by lazy { ShellClient(url, apiKey, pubKey) }

    override suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String =
        executeCommandInternal(cmd, workdir, timeoutMs) { }

    override fun executeCommandEvents(
        cmd: String,
        workdir: String,
        timeoutMs: Int,
    ): Flow<ToolExecutionEvent> = flow {
        val result = executeCommandInternal(cmd, workdir, timeoutMs) { delta ->
            emit(ToolExecutionEvent.OutputDelta(delta))
        }
        emit(ToolExecutionEvent.Completed(result))
    }

    private suspend fun executeCommandInternal(
        cmd: String,
        workdir: String,
        timeoutMs: Int,
        onOutput: suspend (String) -> Unit,
    ): String {
        if (url.isBlank()) return jsonError("shell_exec", "Server \"$deviceName\" has no URL configured.")
        if (!client.fetchPublicKey() && apiKey.isNotBlank()) {
            return jsonError(
                "shell_exec",
                client.lastError ?: "Conch public-key exchange failed for $url",
                server = deviceName,
            )
        }
        lateinit var prepared: ShellClient.PreparedRequest
        val progress = com.newoether.agora.util.NetworkRequestProgress()
        val handle = try {
            com.newoether.agora.util.NetworkRetryPolicy.retry(
                canRetry = { failure ->
                    !progress.started && com.newoether.agora.util.NetworkRetryPolicy.isBeforeSendFailure(failure)
                },
            ) {
                prepared = client.prepareRequest(cmd, timeoutMs, workdir)
                progress.started = false
                HttpClient.streamPost(
                    "${prepared.serverUrl}/execute",
                    prepared.body,
                    prepared.headers,
                    scope = HttpClient.boundStreamScope(),
                    callClient = okhttp3.Call.Factory { request ->
                        com.newoether.agora.util.ConchNetwork.client.newCall(
                            request.newBuilder()
                                .tag(com.newoether.agora.util.NetworkRequestProgress::class.java, progress).build(),
                        )
                    },
                    maxLineBytes = com.newoether.agora.util.ConchNetwork.LINE_LIMIT,
                    maxErrorBytes = com.newoether.agora.util.ConchNetwork.CONTROL_LIMIT,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return jsonError(
                "shell_exec",
                com.newoether.agora.util.describeConchRequestFailure(
                    url,
                    "/execute request",
                    e,
                ),
                server = deviceName,
                command = cmd,
            )
        }
        if (handle.code !in 200..299) {
            val statusCode = handle.code
            val detail = handle.errorBody
                ?.take(240)
                ?.ifBlank { "empty response" }
                ?: "empty response"
            handle.close()
            return jsonError(
                "shell_exec",
                "Conch at ${prepared.serverUrl} returned HTTP $statusCode: $detail",
                server = deviceName,
                command = cmd,
            )
        }
        return try {
            val result = parseConchSseLines(
                encrypted = prepared.isEncrypted,
                readLine = handle::readLine,
                decrypt = client::decryptSseData,
                onOutput = onOutput,
            )
            buildJsonObject {
                put("type", "shell_exec"); put("server", deviceName); put("command", cmd)
                if (result.errorMessage != null) {
                    put("error", "execution_error")
                    put("message", result.errorMessage)
                    if (result.timedOut) put("timed_out", true)
                } else {
                    put("exit_code", result.exitCode ?: -1)
                }
                result.warningMessage?.let { put("warning", it) }
                put("output", result.output)
            }.toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            jsonError("shell_exec", e.message ?: "Unknown error", server = deviceName, command = cmd)
        } finally { handle.close() }
    }

    override suspend fun fileRead(path: String, offset: Long, limit: Long): ShellFileReadResult =
        client.fileRead(path, offset, limit)

    override suspend fun fileWrite(path: String, content: String, expectedSha256: String): String? =
        client.fileWrite(path, content, expectedSha256)?.let { jsonError("file_write", it, server = deviceName) }

    override suspend fun fileEdit(
        path: String,
        edits: List<ShellFileEdit>,
        expectedSha256: String,
    ): ShellFileEditResult = client.fileEdit(path, edits, expectedSha256)

    override suspend fun fileGlob(
        pattern: String,
        basePath: String,
        depth: Int?,
    ): Result<Pair<List<String>, Boolean>> =
        client.fileGlob(pattern, basePath, depth)

    override suspend fun fileGrep(
        pattern: String,
        basePath: String,
        fileGlob: String,
        options: ShellGrepOptions,
    ): Result<ShellGrepResult> =
        client.fileGrep(pattern, basePath, fileGlob, options)

    override fun close() {}

    override suspend fun startJob(cmd: String, workdir: String, timeoutMs: Int, description: String): String =
        client.startJob(cmd, timeoutMs, workdir, description)

    override suspend fun listJobs(offset: Int, limit: Int): String = client.listJobs(offset, limit)

    override suspend fun getJob(jobId: String): String = client.getJob(jobId)

    override suspend fun stopJob(
        jobId: String,
        callTimeoutMillis: Long?,
    ): String = client.stopJob(jobId, callTimeoutMillis)

    override suspend fun acknowledgeJob(jobId: String): String = client.acknowledgeJob(jobId)

    suspend fun viewImage(path: String): ShellClient.FileImageResult = client.fileImage(path)

    override suspend fun loadImage(path: String, store: ToolImageStore): ToolImageAttachment {
        val remote = viewImage(path)
        remote.error?.let { error(it) }
        currentCoroutineContext().ensureActive()
        return store.persistBase64(remote.data, remote.mimeType, "conch")
    }
}

internal class SshBackend(override val device: ShellDeviceConfig) : Backend {
    private val host = device.sshHost
    private val deviceName = device.name
    private val hostKey = device.sshHostKey

    private val client: SshClient by lazy {
        SshClient(
            device = device,
            pinnedHostKey = hostKey,
            // Un-pinned devices stay usable (capture-only); once a key is pinned it is enforced.
            allowUnknownHostKey = hostKey.isBlank()
        )
    }

    override suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String {
        if (host.isBlank()) return jsonError("shell_exec", "SSH device \"$deviceName\" has no host configured.")
        return try {
            val result = client.executeCommand(cmd, workdir, timeoutMs)
            buildJsonObject {
                put("type", "shell_exec"); put("server", deviceName); put("command", cmd)
                put("exit_code", result.exitCode)
                put("output", (result.stdout + if (result.stderr.isNotBlank()) "\n${result.stderr}" else "").trimEnd())
            }.toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            jsonError("shell_exec", e.message ?: "Unknown error", server = deviceName, command = cmd)
        }
    }

    override suspend fun fileRead(path: String, offset: Long, limit: Long): ShellFileReadResult =
        try {
            client.fileRead(path, offset, limit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            failedFileRead(offset, limit, "SFTP read failed: ${e.message}")
        }

    override suspend fun fileWrite(path: String, content: String, expectedSha256: String): String? =
        client.fileWrite(path, content, expectedSha256)?.let { jsonError("file_write", it, server = deviceName) }

    override suspend fun fileEdit(
        path: String,
        edits: List<ShellFileEdit>,
        expectedSha256: String,
    ): ShellFileEditResult = client.fileEdit(path, edits, expectedSha256)

    override suspend fun fileGlob(
        pattern: String,
        basePath: String,
        depth: Int?,
    ): Result<Pair<List<String>, Boolean>> = try {
        Result.success(client.fileGlob(pattern, basePath, depth))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun fileGrep(
        pattern: String,
        basePath: String,
        fileGlob: String,
        options: ShellGrepOptions,
    ): Result<ShellGrepResult> = client.fileGrep(pattern, basePath, fileGlob, options)

    override fun close() { client.close() }
}

internal class SandboxBackend(sandbox: SandboxManager?) : Backend {
    override val device: ShellDeviceConfig? get() = null
    private val mgr = sandbox ?: throw IllegalStateException("Sandbox not available")

    override suspend fun startJob(cmd: String, workdir: String, timeoutMs: Int, description: String): String =
        mgr.startJob(cmd, workdir, timeoutMs, description)
    override suspend fun listJobs(offset: Int, limit: Int): String = mgr.listJobs(offset, limit)
    override suspend fun getJob(jobId: String): String = mgr.getJob(jobId)
    override suspend fun stopJob(jobId: String, callTimeoutMillis: Long?): String =
        if (callTimeoutMillis == null) mgr.stopJob(jobId)
        else withTimeout(callTimeoutMillis) { mgr.stopJob(jobId) }
    override suspend fun acknowledgeJob(jobId: String): String = mgr.acknowledgeJob(jobId)

    override suspend fun loadImage(path: String, store: ToolImageStore): ToolImageAttachment {
        require(path.startsWith('/')) { "Local Sandbox image path must be absolute" }
        val file = mgr.resolveHostFile(path) ?: error("Sandbox path is unavailable")
        require(file.isFile && file.canRead()) { "Image file is missing or unreadable: $path" }
        require(file.length() <= ToolImageStore.MAX_IMAGE_BYTES) { "Tool image exceeds its byte bound" }
        currentCoroutineContext().ensureActive()
        return file.inputStream().use { store.persistStream(it, "image/unknown", "sandbox") }
    }

    override suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String {
        if (!mgr.isAvailable()) return jsonError("shell_exec", "Local Sandbox is not installed.")
        return try {
            val result = mgr.executeCommand(cmd, workdir, timeoutMs)
            buildJsonObject {
                put("type", "shell_exec"); put("server", "Local Sandbox"); put("command", cmd)
                put("exit_code", result.exitCode)
                result.warning?.let { put("warning", it) }
                put("output", (result.stdout + if (result.stderr.isNotBlank()) "\n${result.stderr}" else "").trimEnd())
            }.toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            jsonError("shell_exec", e.message ?: "Unknown error", server = "Local Sandbox", command = cmd)
        }
    }

    override suspend fun fileRead(path: String, offset: Long, limit: Long): ShellFileReadResult =
        try {
            mgr.fileRead(path, offset, limit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            failedFileRead(offset, limit, e.message ?: "Read failed")
        }

    override suspend fun fileWrite(path: String, content: String, expectedSha256: String): String? =
        mgr.fileWrite(path, content, expectedSha256)?.let { jsonError("file_write", it, server = "Local Sandbox") }

    override suspend fun fileEdit(
        path: String,
        edits: List<ShellFileEdit>,
        expectedSha256: String,
    ): ShellFileEditResult = mgr.fileEdit(path, edits, expectedSha256)

    override suspend fun fileGlob(
        pattern: String,
        basePath: String,
        depth: Int?,
    ): Result<Pair<List<String>, Boolean>> = try {
        Result.success(mgr.fileGlob(pattern, basePath, depth))
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun fileGrep(
        pattern: String,
        basePath: String,
        fileGlob: String,
        options: ShellGrepOptions,
    ): Result<ShellGrepResult> = mgr.fileGrep(pattern, basePath, fileGlob, options)

    override fun close() {}
}

private fun failedFileRead(
    offset: Long,
    limit: Long,
    error: String,
): ShellFileReadResult = ShellFileReadResult(
    content = "",
    lines = 0,
    totalLines = 0,
    totalBytes = 0,
    returnedBytes = 0,
    offset = offset.coerceAtLeast(0),
    limit = if (limit in 1..SHELL_FILE_READ_MAX_BYTES) limit else SHELL_FILE_READ_MAX_BYTES,
    truncated = false,
    error = error,
    bytesRead = 0,
)
