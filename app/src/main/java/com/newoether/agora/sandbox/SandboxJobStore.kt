package com.newoether.agora.sandbox

import com.newoether.agora.util.SHELL_COMMAND_MAX_BYTES
import com.newoether.agora.util.SHELL_COMMAND_OUTPUT_MAX_BYTES
import com.newoether.agora.util.SHELL_WORKDIR_MAX_BYTES
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Retained shell execution only; the generation/Room owner remains the caller's responsibility. */
internal class SandboxJobStore(
    private val directory: File,
    private val mutationMutex: Mutex,
    private val processBuilder: (String, String) -> ProcessBuilder,
    private val recoverProcess: suspend (SandboxProcessIdentity) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    @Volatile private var initialized = false
    private var resetting = false
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class Entry(var record: Record, var output: SandboxJobOutput? = null) {
        var job: Job? = null
        var checkpointNanos = 0L
        var persistenceFailure: String? = null
    }

    /** Metadata is separate from the bounded output file, so listing never loads output bodies. */
    @Serializable
    private data class Record(
        val jobId: String,
        val command: String,
        val description: String,
        val workdir: String,
        val timeoutMs: Int,
        val createdAt: Long,
        val startedAt: Long? = null,
        val finishedAt: Long? = null,
        val acknowledgedAt: Long? = null,
        val state: String = "running",
        val exitCode: Int? = null,
        val error: String? = null,
        val process: SandboxProcessIdentity? = null,
        val outputBytes: Long = 0,
        val truncated: Boolean = false,
        val outputSlot: Int = 0,
    )

    private suspend fun initialize() {
        if (initialized) return
        lifecycleMutex.withLock {
            if (initialized) return@withLock
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create sandbox job directory" }
            for (file in directory.listFiles { file -> file.extension == "json" }.orEmpty()) {
                check(file.length() <= MAX_METADATA_BYTES) { "Oversized sandbox job metadata" }
                val record = json.decodeFromString<Record>(file.readText(Charsets.UTF_8))
                check(file.nameWithoutExtension == record.jobId && UUID.fromString(record.jobId).toString() == record.jobId)
                val entry = Entry(record)
                if (record.state in LIVE_STATES) {
                    record.process?.let { recoverProcess(it) }
                    entry.record = record.copy(state = "interrupted", finishedAt = now(), process = null,
                        error = "App process restarted; this command was not reexecuted")
                    persist(entry)
                }
                synchronized(lock) { entries[record.jobId] = entry }
            }
            initialized = true
        }
    }

    /** All rootfs writers must finish recovery before acquiring the mutation lock. */
    suspend fun recover() = withContext(Dispatchers.IO) { initialize() }

    suspend fun start(command: String, workdir: String, timeoutMs: Int, description: String): String {
        currentCoroutineContext().ensureActive()
        require(command.isNotBlank() && '\u0000' !in command && command.toByteArray().size <= SHELL_COMMAND_MAX_BYTES)
        require('\u0000' !in workdir && workdir.toByteArray().size <= SHELL_WORKDIR_MAX_BYTES)
        require(description.toByteArray().size <= 4096) { "Description exceeds 4096 UTF-8 bytes" }
        require(timeoutMs in 1..MAX_RUNTIME_MS) { "Runtime must be between 1ms and 24 hours" }
        initialize()
        var admitted: Entry? = null
        try {
            val result = withContext(NonCancellable + Dispatchers.IO) {
                synchronized(lock) {
                    check(!resetting) { "Sandbox reset is in progress" }
                    // Retained history has no lifetime quota. Only resident execution buffers are bounded.
                    check(entries.values.count { it.output != null } < MAX_ACTIVE_JOBS) {
                        "Sandbox already has $MAX_ACTIVE_JOBS active jobs or unpersisted results"
                    }
                    val entry = Entry(Record(UUID.randomUUID().toString(), command, description,
                        workdir.ifBlank { "/home/agora" }, timeoutMs, now()), SandboxJobOutput())
                    persist(entry)
                    entries[entry.record.jobId] = entry
                    admitted = entry
                    val execution = scope.launch(start = CoroutineStart.LAZY) { execute(entry) }
                    entry.job = execution
                    // A job cancelled before its body starts must still receive a terminal record.
                    execution.invokeOnCompletion { error -> synchronized(lock) {
                        if (entry.record.state in LIVE_STATES) finish(entry, error)
                        releasePersistedOutput(entry)
                    } }
                    execution.start()
                    payload(entry).toString()
                }
            }
            currentCoroutineContext().ensureActive()
            return result
        } catch (error: Throwable) {
            // Stop is manager-owned even if admission's caller disappears before receiving the ID.
            admitted?.let { entry -> withContext(NonCancellable) {
                withTimeoutOrNull(5_000) { stopEntry(entry) }
            } }
            throw error
        }
    }

    private suspend fun execute(entry: Entry) {
        try {
            val result = mutationMutex.withLock {
                currentCoroutineContext().ensureActive()
                val record = synchronized(lock) { entry.record }
                SandboxProcessRunner.execute(processBuilder(record.command, record.workdir), record.timeoutMs,
                    onStarted = { process -> synchronized(lock) {
                        entry.record = entry.record.copy(startedAt = now(), process = process)
                        persist(entry)
                    } },
                    onOutput = { text -> synchronized(lock) {
                        entry.output!!.append(text)
                        if (System.nanoTime() - entry.checkpointNanos >= 1_000_000_000L) persist(entry)
                    } })
            }
            synchronized(lock) {
                entry.record = entry.record.copy(
                    state = if (entry.record.state == "stopping") "stopped" else if (result.exitCode == 0) "succeeded" else "failed",
                    exitCode = result.exitCode, error = result.stderr.takeIf { it.isNotEmpty() },
                    finishedAt = now(), process = null)
                persist(entry)
            }
        } catch (error: Throwable) {
            synchronized(lock) { finish(entry, error) }
        } finally {
            synchronized(lock) { releasePersistedOutput(entry) }
        }
    }

    private fun finish(entry: Entry, error: Throwable?) {
        entry.record = entry.record.copy(state = if (error is CancellationException) "stopped" else "failed",
            error = if (error is CancellationException) "Stopped" else error?.message ?: "Execution did not start",
            finishedAt = now(), process = null)
        // Failures remain queryable without escaping an independent SupervisorJob as an uncaught error.
        runCatching { persist(entry) }
    }

    private fun releasePersistedOutput(entry: Entry) {
        if (entry.record.state !in LIVE_STATES && entry.persistenceFailure == null) entry.output = null
    }

    suspend fun get(jobId: String): String = withContext(Dispatchers.IO) {
        initialize()
        synchronized(lock) { payload(find(jobId)).toString() }
    }

    suspend fun list(offset: Int, limit: Int): String = withContext(Dispatchers.IO) {
        require(offset >= 0 && limit in 1..100)
        initialize()
        synchronized(lock) {
            val records = entries.values.sortedByDescending { it.record.createdAt }
            val page = records.drop(offset).take(limit)
            buildJsonObject {
                put("server", "Local Sandbox")
                put("jobs", buildJsonArray { page.forEach { add(payload(it, summary = true)) } })
                put("has_more", offset.toLong() + page.size < records.size)
                put("next_offset", offset.toLong() + page.size)
                put("total", records.size)
            }.toString()
        }
    }

    suspend fun stop(jobId: String): String = withContext(Dispatchers.IO) {
        initialize()
        val entry = synchronized(lock) { find(jobId) }
        stopEntry(entry)
        synchronized(lock) { payload(entry).toString() }
    }

    private suspend fun stopEntry(entry: Entry) {
        val execution = synchronized(lock) {
            if (entry.record.state in LIVE_STATES) {
                entry.record = entry.record.copy(state = "stopping")
                runCatching { persist(entry) }
                entry.job?.cancel()
            }
            entry.job
        }
        // Cancelling this wait does not cancel cleanup, which belongs to the execution job.
        execution?.join()
        synchronized(lock) {
            entry.persistenceFailure?.let { throw IOException(it) }
        }
    }

    suspend fun acknowledge(jobId: String): String = withContext(Dispatchers.IO) {
        initialize()
        synchronized(lock) {
            val entry = find(jobId)
            if (entry.record.acknowledgedAt == null || entry.persistenceFailure != null) {
                entry.record = entry.record.copy(acknowledgedAt = entry.record.acknowledgedAt ?: now())
                persist(entry)
                releasePersistedOutput(entry)
            }
            payload(entry).toString()
        }
    }

    /** Refuse live executions and close admission while the caller changes the active rootfs. */
    suspend fun <T> whileIdle(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        initialize()
        check(lifecycleMutex.tryLock()) { "Sandbox maintenance is in progress" }
        try {
            synchronized(lock) {
                check(!resetting) { "Sandbox maintenance is in progress" }
                check(entries.values.none {
                    it.record.state in LIVE_STATES || it.job?.isCompleted == false || it.persistenceFailure != null
                }) { "Stop active sandbox jobs before changing distribution" }
                resetting = true
            }
            try {
                action()
            } finally {
                synchronized(lock) { resetting = false }
            }
        } finally {
            lifecycleMutex.unlock()
        }
    }

    /** Holds admission closed through rootfs deletion, while get/list remain available. */
    suspend fun <T> reset(action: suspend () -> T): T = withContext(NonCancellable + Dispatchers.IO) {
        initialize()
        lifecycleMutex.withLock {
            val live = synchronized(lock) {
                resetting = true
                entries.values.toList()
            }
            try {
                live.forEach { stopEntry(it) }
                action()
            } finally {
                synchronized(lock) { resetting = false }
            }
        }
    }

    private fun find(jobId: String): Entry = entries[jobId] ?: error("Unknown sandbox job: $jobId")

    private fun persist(entry: Entry) {
        try {
            val output = entry.output
            val published = if (output == null) entry.record else {
                // Write only the inactive slot. Metadata atomically publishes its bytes and counters.
                val next = entry.record.copy(outputSlot = 1 - entry.record.outputSlot,
                    outputBytes = output.totalBytes, truncated = output.truncated)
                writeAtomically(outputFile(next), output.text().toByteArray(Charsets.UTF_8))
                next
            }
            writeAtomically(File(directory, "${published.jobId}.json"), json.encodeToString(published).toByteArray(Charsets.UTF_8))
            entry.record = published
            entry.persistenceFailure = null
            entry.checkpointNanos = System.nanoTime()
        } catch (error: Exception) {
            entry.persistenceFailure = "Cannot persist sandbox job: ${error.message ?: error.javaClass.simpleName}"
            throw error
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temporary = File(directory, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }

    private fun outputFile(record: Record): File {
        check(record.outputSlot in 0..1) { "Invalid sandbox output slot" }
        val suffix = if (record.outputSlot == 0) ".output" else ".output-next"
        return File(directory, "${record.jobId}$suffix")
    }

    private fun retainedOutput(entry: Entry): String = entry.output?.text() ?: run {
        val file = outputFile(entry.record)
        check(file.isFile && file.length() <= SHELL_COMMAND_OUTPUT_MAX_BYTES) { "Missing or oversized sandbox job output" }
        file.readText(Charsets.UTF_8)
    }

    private fun payload(entry: Entry, summary: Boolean = false) = buildJsonObject {
        val record = entry.record
        put("job_id", record.jobId)
        put("server", "Local Sandbox")
        put("command", if (summary) record.command.take(256) else record.command)
        put("description", if (summary) record.description.take(256) else record.description)
        if (!summary) {
            put("workdir", record.workdir)
            put("output", retainedOutput(entry))
        }
        put("state", record.state)
        put("output_bytes", entry.output?.totalBytes ?: record.outputBytes)
        put("truncated", entry.output?.truncated ?: record.truncated)
        put("exit_code", record.exitCode?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
        put("created_at", java.time.Instant.ofEpochMilli(record.createdAt).toString())
        record.startedAt?.let { put("started_at", java.time.Instant.ofEpochMilli(it).toString()) }
        record.finishedAt?.let { put("finished_at", java.time.Instant.ofEpochMilli(it).toString()) }
        record.acknowledgedAt?.let { put("acknowledged_at", java.time.Instant.ofEpochMilli(it).toString()) }
        record.error?.let { put("error_message", if (summary) it.take(256) else it) }
        put("timeout_ms", record.timeoutMs)
        entry.persistenceFailure?.let { put("persistence_error", it) }
        put("durable", entry.persistenceFailure == null)
    }

    companion object {
        const val MAX_RUNTIME_MS = 86_400_000
        private const val MAX_ACTIVE_JOBS = 16
        private const val MAX_METADATA_BYTES = 1_048_576L
        private val LIVE_STATES = setOf("running", "stopping", "settling")
        private fun now() = System.currentTimeMillis()
    }
}

/** A UTF-8 byte ring retains the tail without retaining or rewriting an unbounded transcript. */
internal class SandboxJobOutput(initial: String = "", total: Long = 0) {
    private val bytes = ByteArray(SHELL_COMMAND_OUTPUT_MAX_BYTES)
    private var start = 0
    private var size = 0
    var totalBytes = 0L
        private set
    val truncated: Boolean get() = totalBytes > size

    init {
        append(initial)
        totalBytes = maxOf(totalBytes, total)
    }

    fun append(text: String) {
        val incoming = text.toByteArray(Charsets.UTF_8)
        totalBytes += incoming.size
        if (incoming.size >= bytes.size) {
            incoming.copyInto(bytes, 0, incoming.size - bytes.size)
            start = 0
            size = bytes.size
            return
        }
        val discard = (size + incoming.size - bytes.size).coerceAtLeast(0)
        start = (start + discard) % bytes.size
        size -= discard
        val end = (start + size) % bytes.size
        val first = minOf(incoming.size, bytes.size - end)
        incoming.copyInto(bytes, end, 0, first)
        incoming.copyInto(bytes, 0, first)
        size += incoming.size
    }

    private fun textBytes(): ByteArray {
        val result = ByteArray(size)
        val first = minOf(size, bytes.size - start)
        bytes.copyInto(result, 0, start, start + first)
        bytes.copyInto(result, first, 0, size - first)
        var skip = 0
        while (skip < result.size && result[skip].toInt() and 0xc0 == 0x80) skip++
        return if (skip == 0) result else result.copyOfRange(skip, result.size)
    }

    fun text(): String = textBytes().toString(Charsets.UTF_8)
}
