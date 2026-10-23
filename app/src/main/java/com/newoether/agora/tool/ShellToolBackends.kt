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
import kotlinx.serialization.json.put

internal sealed interface Backend {
    /** The remote device this backend targets, or null for the local sandbox (never gated). */
    val device: ShellDeviceConfig?
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
    // Sanitize: a saved URL can contain a stray newline + extra text; keep only the first
    // whitespace-delimited token so request paths and error messages use a clean base.
    private val url = com.newoether.agora.util.sanitizeConchServerUrl(device.serverUrl)
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
        if (url.isBlank()) return jsonError("execute_shell_command", "Server \"$deviceName\" has no URL configured.")
        if (!client.fetchPublicKey() && apiKey.isNotBlank()) {
            return jsonError(
                "execute_shell_command",
                client.lastError ?: "Conch public-key exchange failed for $url",
                server = deviceName,
            )
        }
        val prepared = client.prepareRequest(cmd, timeoutMs, workdir)
        val handle = try {
            HttpClient.streamPost(
                "${prepared.serverUrl}/execute",
                prepared.body,
                prepared.headers,
                scope = HttpClient.boundStreamScope(),
                callClient = com.newoether.agora.util.ConchNetwork.streamCalls,
                maxLineBytes = com.newoether.agora.util.ConchNetwork.LINE_LIMIT,
                maxErrorBytes = com.newoether.agora.util.ConchNetwork.CONTROL_LIMIT,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return jsonError(
                "execute_shell_command",
                com.newoether.agora.util.describeConchRequestFailure(
                    prepared.serverUrl,
                    "/execute request",
                    e,
                ),
                server = deviceName,
                command = cmd,
            )
        }
        if (handle.code !in 200..299) {
            val detail = handle.errorBody
                ?.take(240)
                ?.ifBlank { "empty response" }
                ?: "empty response"
            handle.close()
            return jsonError(
                "execute_shell_command",
                "Conch at ${prepared.serverUrl} returned HTTP ${handle.code}: $detail",
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
                put("type", "execute_shell_command"); put("server", deviceName); put("command", cmd)
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
            jsonError("execute_shell_command", e.message ?: "Unknown error", server = deviceName, command = cmd)
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

    suspend fun startJob(cmd: String, workdir: String, timeoutMs: Int): String =
        client.startJob(cmd, timeoutMs, workdir)

    suspend fun listJobs(): String = client.listJobs()

    suspend fun getJob(jobId: String): String = client.getJob(jobId)

    suspend fun stopJob(
        jobId: String,
        callTimeoutMillis: Long? = null,
    ): String = client.stopJob(jobId, callTimeoutMillis)

    suspend fun acknowledgeJob(jobId: String): String = client.acknowledgeJob(jobId)

    suspend fun viewImage(path: String): ShellClient.FileImageResult =
        client.fileImage(path)
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
            allowUnknownHostKey = hostKey.isBlank(),
            privateKey = device.sshPrivateKey,
            keyPassphrase = device.sshKeyPassphrase,
        )
    }

    override suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String {
        if (host.isBlank()) return jsonError("execute_shell_command", "SSH device \"$deviceName\" has no host configured.")
        return try {
            val result = client.executeCommand(cmd, workdir, timeoutMs)
            buildJsonObject {
                put("type", "execute_shell_command"); put("server", deviceName); put("command", cmd)
                put("exit_code", result.exitCode)
                put("output", (result.stdout + if (result.stderr.isNotBlank()) "\n${result.stderr}" else "").trimEnd())
            }.toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            jsonError("execute_shell_command", e.message ?: "Unknown error", server = deviceName, command = cmd)
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

    override suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String {
        if (!mgr.isAvailable()) return jsonError("execute_shell_command", "Local Sandbox is not installed.")
        return try {
            val result = mgr.executeCommand(cmd, workdir, timeoutMs)
            buildJsonObject {
                put("type", "execute_shell_command"); put("server", "Local Sandbox"); put("command", cmd)
                put("exit_code", result.exitCode)
                result.warning?.let { put("warning", it) }
                put("output", (result.stdout + if (result.stderr.isNotBlank()) "\n${result.stderr}" else "").trimEnd())
            }.toString()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            jsonError("execute_shell_command", e.message ?: "Unknown error", server = "Local Sandbox", command = cmd)
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
