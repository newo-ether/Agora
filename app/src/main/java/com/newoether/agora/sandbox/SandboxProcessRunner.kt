package com.newoether.agora.sandbox

import com.newoether.agora.util.SHELL_COMMAND_OUTPUT_MAX_BYTES
import com.newoether.agora.util.readBoundedShellOutput
import java.io.File
import java.io.InputStreamReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/** Identity fences recovery signals against PID reuse and a device reboot. */
@Serializable
internal data class SandboxProcessIdentity(val pid: Int, val startTicks: String, val bootId: String) {
    fun stillMatches(): Boolean = read(pid) == this

    companion object {
        fun of(process: Process): SandboxProcessIdentity? {
            val pid = runCatching { Process::class.java.getMethod("pid").invoke(process) as Long }
                .getOrNull()?.toInt() ?: runCatching {
                process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process)
            }.getOrNull() ?: return null
            return read(pid)
        }

        private fun read(pid: Int): SandboxProcessIdentity? = runCatching {
            val stat = File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')
            SandboxProcessIdentity(pid, stat[19], File("/proc/sys/kernel/random/boot_id").readText().trim())
        }.getOrNull()
    }
}

/** Owns process teardown and output draining for both package commands and retained jobs. */
internal object SandboxProcessRunner {
    suspend fun execute(
        builder: ProcessBuilder,
        timeoutMs: Int,
        onStarted: (SandboxProcessIdentity?) -> Unit = {},
        onOutput: ((String) -> Unit)? = null,
    ): SandboxManager.SandboxResult = withContext(Dispatchers.IO) {
        val process = builder.redirectErrorStream(true).start()
        try {
            onStarted(SandboxProcessIdentity.of(process))
            process.outputStream.close()
            supervisorScope {
                val output = async(Dispatchers.IO) {
                    if (onOutput == null) {
                        process.inputStream.use { it.readBoundedShellOutput() }
                    } else {
                        // Streamed chunks are also retained, bounded like the non-streaming read,
                        // so callers that need exit status can still inspect stdout/stderr.
                        val retained = StringBuilder()
                        var retainedBytes = 0
                        var truncated = false
                        fun retain(chunk: String) {
                            if (truncated) return
                            val bytes = chunk.toByteArray(Charsets.UTF_8).size
                            if (retainedBytes + bytes > SHELL_COMMAND_OUTPUT_MAX_BYTES) {
                                truncated = true
                                return
                            }
                            retained.append(chunk)
                            retainedBytes += bytes
                        }
                        InputStreamReader(process.inputStream, Charsets.UTF_8).use { reader ->
                            val chars = CharArray(4096)
                            var pending = ""
                            while (true) {
                                val count = reader.read(chars)
                                if (count < 0) break
                                val text = pending + String(chars, 0, count)
                                val trailing = text.lastOrNull()?.isHighSurrogate() == true
                                pending = if (trailing) text.takeLast(1) else ""
                                val chunk = if (trailing) text.dropLast(1) else text
                                if (chunk.isNotEmpty()) {
                                    onOutput(chunk)
                                    retain(chunk)
                                }
                            }
                            if (pending.isNotEmpty()) onOutput("\uFFFD")
                        }
                        retained.toString() to truncated
                    }
                }
                try {
                    val code = withTimeoutOrNull(timeoutMs.toLong().coerceAtLeast(1)) {
                        waitForExit(process) {
                            // A failed output checkpoint must stop the process promptly.
                            if (output.isCompleted) output.await()
                        }
                    }
                    if (code == null) terminate(process)
                    val captured = if (code == null) {
                        try {
                            withTimeoutOrNull(2_000) { output.await() }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        withTimeoutOrNull(2_000) { output.await() }
                    }
                    if (captured == null) {
                        runCatching { process.inputStream.close() }
                        output.cancelAndJoin()
                    }
                    SandboxManager.SandboxResult(
                        stdout = captured?.first.orEmpty(),
                        stderr = when {
                            code == null -> "Timed out"
                            captured == null -> "Process output did not close after exit"
                            else -> ""
                        },
                        exitCode = if (captured == null || code == null) -1 else code,
                        warning = captured?.second?.takeIf { it }?.let { truncationWarning() },
                    )
                } catch (error: Throwable) {
                    withContext(NonCancellable) {
                        terminate(process)
                        runCatching { process.inputStream.close() }
                        output.cancelAndJoin()
                    }
                    throw error
                }
            }
        } finally {
            withContext(NonCancellable) {
                terminate(process)
                runCatching { process.inputStream.close() }
                runCatching { process.outputStream.close() }
                runCatching { process.errorStream.close() }
            }
        }
    }

    private fun truncationWarning() =
        "Local Sandbox output was truncated at $SHELL_COMMAND_OUTPUT_MAX_BYTES UTF-8 bytes."

    private suspend fun waitForExit(process: Process, checkOutput: suspend () -> Unit = {}): Int {
        while (true) {
            checkOutput()
            runCatching { process.exitValue() }.getOrNull()?.let { return it }
            delay(25)
        }
    }

    private suspend fun terminate(process: Process) = withContext(NonCancellable + Dispatchers.IO) {
        if (runCatching { process.exitValue() }.isSuccess) return@withContext
        process.destroy()
        if (withTimeoutOrNull(2_000) { waitForExit(process) } == null) {
            process.destroyForcibly()
            waitForExit(process)
        }
    }
}
