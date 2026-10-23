package com.newoether.agora.tool

import com.newoether.agora.viewmodel.GenerationContext
import com.newoether.agora.model.ToolCallData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal fun resolveConchDevice(
    serverName: String,
    ctx: GenerationContext,
) = ctx.shellDevices.filter { it.type != "ssh" }.let { conchDevices ->
    if (serverName.isNotBlank()) {
        // singleOrNull: a name shared by two Conch devices is ambiguous and resolves to null so
        // the caller surfaces conchServerNotFoundMessage() instead of silently picking the first.
        conchDevices.filter { it.name.equals(serverName, ignoreCase = true) }.singleOrNull()
    } else {
        conchDevices.singleOrNull()
    }
}

internal fun getConchBackend(
    serverName: String,
    ctx: GenerationContext,
): ConchBackend? = resolveConchDevice(serverName, ctx)?.let(::ConchBackend)

internal fun conchServerNotFoundMessage(serverName: String, ctx: GenerationContext): String {
    val conch = ctx.shellDevices.filter { it.type != "ssh" }
    val names = conch.map { it.name.ifBlank { "Untitled" } }
    val duplicateCount = if (serverName.isNotBlank()) {
        conch.count { it.name.equals(serverName, ignoreCase = true) }
    } else 0
    return when {
        names.isEmpty() -> "No Conch server is configured. Background jobs require Conch."
        duplicateCount > 1 ->
            "Ambiguous Conch server: \"$serverName\" matches $duplicateCount configured Conch " +
                "devices. Rename one so each has a unique name, then retry."
        serverName.isNotBlank() ->
            "Unknown Conch server \"$serverName\". Available: ${names.joinToString(", ")}."
        names.size > 1 ->
            "Multiple Conch servers are available. Specify one: ${names.joinToString(", ")}."
        else -> "Conch server is unavailable."
    }
}

internal fun interface ShellJobPoller {
    suspend fun getJob(): String
}

internal const val DURABLE_JOB_CANCELLATION_STOP_TIMEOUT_MS = 5_000L
internal class ShellDurableJobExecutor {
    suspend fun executeDurableForeground(
        backend: ConchBackend,
        command: String,
        workdir: String,
        waitMs: Int,
        onOutput: suspend (String) -> Unit = {},
    ): String {
        val startResult = try {
            backend.startJob(command, workdir, BACKGROUND_JOB_MAX_MS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return jsonError(
                "execute_shell_command",
                e.message ?: "Failed to start durable foreground job",
                server = backend.device.name,
                command = command,
            )
        }
        val startObj = try {
            Json.parseToJsonElement(startResult).jsonObject
        } catch (_: Exception) {
            return startResult
        }
        if (startObj["error"] != null) return startResult
        val jobId = (startObj["job_id"] as? JsonPrimitive)?.content
            ?.takeIf(String::isNotBlank)
            ?: return jsonError(
                "execute_shell_command",
                "Conch started a job without returning job_id",
                server = backend.device.name,
                command = command,
            )

        val start = System.currentTimeMillis()
        var pollIntervalMs = INITIAL_WAIT_POLL_MS
        var consecutiveFailures = 0
        var lastFailure: String? = null
        val outputCursor = ConchJobOutputCursor()
        try {
        while (currentCoroutineContext().isActive) {
            val raw = try {
                backend.getJob(jobId).also { consecutiveFailures = 0 }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                consecutiveFailures++
                lastFailure = e.message ?: e.javaClass.simpleName
                if (consecutiveFailures >= MAX_WAIT_POLL_FAILURES) {
                    return buildJsonObject {
                        put("type", "execute_shell_command")
                        put("error", "poll_failed")
                        put(
                            "message",
                            "Durable job could not be polled $consecutiveFailures times: " +
                                lastFailure,
                        )
                        put("server", backend.device.name)
                        put("command", command)
                        put("job_id", jobId)
                        put("durable", true)
                        put("state", "unknown")
                        put(
                            "note",
                            "The command may still be running. Keep this job_id and retry with " +
                                "wait_for_job or get_shell_job; it was not killed or restarted.",
                        )
                    }.toString()
                }
                null
            }
            if (raw != null) {
                val outputUpdate = outputCursor.consume(raw)
                if (outputUpdate.lostBytes > 0) {
                    onOutput(
                        "[Conch output gap: ${outputUpdate.lostBytes} earlier UTF-8 bytes " +
                            "were evicted before Agora could read them.]\n",
                    )
                }
                if (outputUpdate.delta.isNotEmpty()) {
                    onOutput(outputUpdate.delta)
                }
                if (isTerminalJobPayload(raw)) {
                    val result = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
                    return buildJsonObject {
                        put("type", "execute_shell_command")
                        put("server", backend.device.name)
                        put("command", command)
                        put("job_id", jobId)
                        put("durable", true)
                        if (result != null) put("result", result) else put("result_raw", raw)
                    }.toString()
                }
            }
            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= waitMs) {
                return buildJsonObject {
                    put("type", "execute_shell_command")
                    put("server", backend.device.name)
                    put("command", command)
                    put("job_id", jobId)
                    put("background", true)
                    put("state", "running")
                    put("waited_ms", elapsed)
                    put(
                        "note",
                        "Foreground wait expired; the same durable job is still running. Use " +
                            "wait_for_job to await it. The command was not killed or restarted.",
                    )
                }.toString()
            }
            val remaining = (waitMs - elapsed).toInt()
            kotlinx.coroutines.delay(pollIntervalMs.coerceAtMost(remaining).toLong())
        }
        } catch (cancelled: CancellationException) {
            // A wait expiry intentionally leaves the durable job running. An explicit generation
            // Stop is different: it revokes this tool execution and stops the remote process tree.
            withContext(NonCancellable) {
                runCatching {
                    backend.stopJob(jobId, DURABLE_JOB_CANCELLATION_STOP_TIMEOUT_MS)
                }
            }
            throw cancelled
        }
        return jsonError(
            "execute_shell_command",
            "cancelled while durable job $jobId continues on ${backend.device.name}",
            server = backend.device.name,
            command = command,
        )
    }

    suspend fun listShellJobs(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val serverName = arg(args, "server")
        val backend = getConchBackend(serverName, ctx)
            ?: return jsonError(
                "list_shell_jobs",
                conchServerNotFoundMessage(serverName, ctx),
                server = serverName,
            )
        return try {
            backend.listJobs()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            jsonError(
                "list_shell_jobs",
                e.message ?: "Failed to list shell jobs",
                server = backend.device.name,
            )
        } finally {
            backend.close()
        }
    }

    suspend fun getShellJob(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val jobId = arg(args, "job_id")
        if (jobId.isBlank()) return jsonError("get_shell_job", "job_id is required")
        val serverName = arg(args, "server")
        val backend = getConchBackend(serverName, ctx)
            ?: return jsonError(
                "get_shell_job",
                conchServerNotFoundMessage(serverName, ctx),
                server = serverName,
            )
        return try {
            backend.getJob(jobId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            jsonError(
                "get_shell_job",
                e.message ?: "Failed to get shell job",
                server = backend.device.name,
            )
        } finally {
            backend.close()
        }
    }

    suspend fun waitForShellJob(
        arguments: String,
        ctx: GenerationContext,
        onOutputSnapshot: suspend (String) -> Unit = {},
    ): String {
        val args = parseToolArgs(arguments)
        val jobId = arg(args, "job_id")
        if (jobId.isBlank()) return jsonError("wait_for_job", "job_id is required")
        val serverName = arg(args, "server")
        val rawTimeout = arg(args, "timeout_ms")
        if (rawTimeout.isBlank()) return jsonError(
            "wait_for_job", "timeout_ms is required", server = serverName,
        )
        val requestedMs = rawTimeout.toIntOrNull()
            ?: return jsonError(
                "wait_for_job",
                "timeout_ms must be an integer, got \"$rawTimeout\"",
                server = serverName,
            )
        val backend = getConchBackend(serverName, ctx)
            ?: return jsonError(
                "wait_for_job",
                conchServerNotFoundMessage(serverName, ctx),
                server = serverName,
            )
        return try {
            // The whole tool call runs under GenerationManager's withTimeout(ctx.toolTimeoutMs). A
            // wait that reaches that outer ceiling is killed as a generic tool timeout, so its
            // graceful "still running" note never fires. Leave enough margin for the result.
            val ceilingMs = maxWaitMs(ctx)
            val timeoutMs = requestedMs.coerceIn(MIN_WAIT_JOB_MS, ceilingMs)
            // Report silent clamping so the model does not infer it waited for the requested time.
            val clampedFrom = requestedMs.takeIf { it > ceilingMs }
            waitForShellJobPolling(
                jobId = jobId,
                serverName = backend.device.name,
                timeoutMs = timeoutMs,
                clampedFrom = clampedFrom,
                ceilingMs = ceilingMs,
                poller = ShellJobPoller { backend.getJob(jobId) },
                onOutputSnapshot = onOutputSnapshot,
            )
        } finally {
            backend.close()
        }
    }

    internal suspend fun waitForShellJobPolling(
        jobId: String,
        serverName: String,
        timeoutMs: Int,
        clampedFrom: Int? = null,
        ceilingMs: Int = timeoutMs,
        poller: ShellJobPoller,
        onOutputSnapshot: suspend (String) -> Unit = {},
        nowMs: () -> Long = System::currentTimeMillis,
        delayMs: suspend (Long) -> Unit = { delay -> kotlinx.coroutines.delay(delay) },
    ): String {
        val start = nowMs()
        // A transient poll failure must not abort the wait: the job keeps running on the device.
        // Only a sustained run of failures is fatal.
        var consecutiveFailures = 0
        var lastFailure: String? = null
        var pollIntervalMs = INITIAL_WAIT_POLL_MS
        var latestSnapshot: ConchJobOutputSnapshot? = null
        var lastPublishedSnapshot: String? = null
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val raw = try {
                poller.getJob().also { consecutiveFailures = 0 }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                consecutiveFailures++
                lastFailure = e.message ?: e.javaClass.simpleName
                if (consecutiveFailures >= MAX_WAIT_POLL_FAILURES) {
                    return buildJsonObject {
                        put("type", "wait_for_job")
                        put("error", "poll_failed")
                        put(
                            "message",
                            "Failed to poll job $consecutiveFailures times in a row: $lastFailure",
                        )
                        put("server", serverName)
                        put("job_id", jobId)
                        put("durable", true)
                        put("state", "unknown")
                        latestSnapshot?.let { snapshot ->
                            put("output", snapshot.text)
                            put("output_bytes", snapshot.outputBytes)
                            put("truncated", snapshot.truncated)
                        }
                        put(
                            "note",
                            "The job may still be running. Retry with the same job_id; it was not " +
                                "stopped by this wait failure.",
                        )
                    }.toString()
                }
                null
            }
            if (raw != null) {
                conchJobOutputSnapshot(raw)?.let { snapshot ->
                    latestSnapshot = snapshot
                    if (snapshot.text != lastPublishedSnapshot) {
                        onOutputSnapshot(snapshot.text)
                        lastPublishedSnapshot = snapshot.text
                    }
                }
            }
            if (raw != null && isTerminalJobPayload(raw)) {
                val result = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
                return buildJsonObject {
                    put("type", "wait_for_job")
                    put("job_id", jobId)
                    put("waited_ms", nowMs() - start)
                    if (result != null) put("result", result) else put("result_raw", raw)
                }.toString()
            }
            val elapsed = nowMs() - start
            if (elapsed >= timeoutMs) {
                val clampNote = clampedFrom?.let {
                    " The requested timeout_ms=$it exceeded this tool call's ceiling of ${ceilingMs}ms and was clamped, so the job has only been waited on for that long."
                } ?: ""
                return buildJsonObject {
                    put("type", "wait_for_job")
                    put("job_id", jobId)
                    put("state", "running")
                    put("waited_ms", elapsed)
                    put("timed_out", true)
                    latestSnapshot?.let { snapshot ->
                        put("output", snapshot.text)
                        put("output_bytes", snapshot.outputBytes)
                        put("truncated", snapshot.truncated)
                    }
                    put(
                        "note",
                        "Job still running. Call wait_for_job again to keep waiting, or " +
                            "get_shell_job for a one-shot look.$clampNote",
                    )
                }.toString()
            }
            // Back off so a long wait does not hammer the device, but never overshoot the deadline.
            val remaining = (timeoutMs - elapsed).toInt()
            delayMs(pollIntervalMs.coerceAtMost(remaining).toLong())
            pollIntervalMs = (pollIntervalMs * 2).coerceAtMost(MAX_WAIT_POLL_MS)
        }
        return jsonError("wait_for_job", "cancelled", server = serverName)
    }

    /**
     * Decides whether a raw `/jobs/get` payload represents a finished job.
     *
     * Conch reports lifecycle in the **`state`** field (see conch shell/jobs.go): `running` and
     * `stopping` and `settling` are live; `succeeded`, `failed`, `stopped` and `interrupted` are
     * terminal. A lifecycle state always wins over incidental error fields because settlement may
     * still be syncing retained output. Only an explicit server-side `error` without a state (for
     * example, "job not found") is terminal. Unparseable or field-less payloads remain nonterminal.
     */
    internal fun isTerminalJobPayload(raw: String): Boolean {
        if (raw.isBlank()) return false
        val obj = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return false
        }
        val state = (obj["state"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content
            ?.lowercase()
        if (state != null) return state in TERMINAL_JOB_STATES
        return obj["error"] != null
    }

    companion object {
        /** Conch's durable-job runtime ceiling (24h). */
        internal const val BACKGROUND_JOB_MAX_MS = 86_400_000

        private const val MIN_WAIT_JOB_MS = 1_000
        private const val WAIT_JOB_OUTER_MARGIN_MS = 5_000L

        internal fun maxWaitMs(ctx: GenerationContext): Int =
            (ctx.toolTimeoutMs - WAIT_JOB_OUTER_MARGIN_MS)
                .coerceAtLeast(MIN_WAIT_JOB_MS.toLong())
                .toInt()

        private const val INITIAL_WAIT_POLL_MS = 500
        private const val MAX_WAIT_POLL_MS = 5_000
        private const val MAX_WAIT_POLL_FAILURES = 5

        private val TERMINAL_JOB_STATES = setOf(
            "succeeded",
            "failed",
            "stopped",
            "interrupted",
        )
    }
}

internal data class ConchJobOutputSnapshot(
    val text: String,
    val outputBytes: Long,
    val truncated: Boolean,
)

internal fun conchJobOutputSnapshot(raw: String): ConchJobOutputSnapshot? {
    val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    val output = (obj["output"] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?: return null
    val outputBytes = (obj["output_bytes"] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.content
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }
        ?: return null
    val truncated = (obj["truncated"] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.content
        ?.toBooleanStrictOrNull()
        ?: (outputBytes > output.toByteArray(Charsets.UTF_8).size)
    return ConchJobOutputSnapshot(output, outputBytes, truncated)
}

internal data class ConchJobOutputUpdate(
    val delta: String,
    val lostBytes: Long,
)
/**
 * Converts Conch's rolling durable-job snapshots into an exactly-once best-effort output stream.
 * `output_bytes` is the global byte position while `output` is a bounded UTF-8 tail. Tracking the
 * global cursor avoids replaying the full tail on every poll and also detects when polling fell
 * behind the server's retention window.
 */
internal class ConchJobOutputCursor {
    private var emittedBytes = 0L
    fun consume(raw: String): ConchJobOutputUpdate {
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return ConchJobOutputUpdate("", 0)
        val output = (obj["output"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?: return ConchJobOutputUpdate("", 0)
        val outputBytes = (obj["output_bytes"] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.content
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?: return ConchJobOutputUpdate("", 0)
        val retained = output.toByteArray(Charsets.UTF_8)
        val retainedStart = (outputBytes - retained.size).coerceAtLeast(0)
        val lostBytes = (retainedStart - emittedBytes).coerceAtLeast(0)
        val start = (emittedBytes.coerceAtLeast(retainedStart) - retainedStart)
            .coerceIn(0, retained.size.toLong())
            .toInt()
        val delta = retained.copyOfRange(start, retained.size).toString(Charsets.UTF_8)
        emittedBytes = maxOf(emittedBytes, outputBytes)
        return ConchJobOutputUpdate(delta, lostBytes)
    }
}
internal data class TerminalShellJobAcknowledgement(
    val serverName: String,
    val jobId: String,
)

/**
 * Extracts only terminal Conch jobs whose full result is about to cross a durable Room boundary.
 * Running/stopping jobs, malformed payloads, errors without a terminal state, and unrelated tools
 * are ignored. The server may live only in the original tool arguments because `/jobs/get` returns
 * a server-local job document.
 */
internal fun terminalShellJobAcknowledgements(
    calls: List<ToolCallData>,
): List<TerminalShellJobAcknowledgement> = calls.mapNotNull { call ->
    if (call.toolName !in ACKNOWLEDGEABLE_SHELL_TOOLS) return@mapNotNull null
    val envelope = runCatching {
        // Shell tools hand the model plain text; the JSON envelope is the structured result.
        Json.parseToJsonElement(call.structuredResult ?: call.result).jsonObject
    }.getOrNull() ?: return@mapNotNull null
    val nested = envelope["result"] as? JsonObject
    val state = nested.stringValue("state") ?: envelope.stringValue("state")
    if (state?.lowercase() !in TERMINAL_ACK_STATES) return@mapNotNull null
    val jobId = envelope.stringValue("job_id")
        ?: nested.stringValue("job_id")
        ?: return@mapNotNull null
    val arguments = runCatching {
        Json.parseToJsonElement(call.arguments).jsonObject
    }.getOrNull()
    TerminalShellJobAcknowledgement(
        serverName = envelope.stringValue("server")
            ?: nested.stringValue("server")
            ?: arguments.stringValue("server")
            ?: "",
        jobId = jobId,
    )
}.distinctBy { acknowledgement ->
    acknowledgement.serverName.lowercase() to acknowledgement.jobId
}

private fun JsonObject?.stringValue(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)

private val ACKNOWLEDGEABLE_SHELL_TOOLS = setOf(
    "execute_shell_command",
    "get_shell_job",
    "wait_for_job",
)

private val TERMINAL_ACK_STATES = setOf(
    "succeeded",
    "failed",
    "stopped",
    "interrupted",
)
