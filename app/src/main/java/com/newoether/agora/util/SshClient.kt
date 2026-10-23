package com.newoether.agora.util

import android.util.Base64
import com.newoether.agora.data.ShellDeviceConfig
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * SSH client using JSch for shell command execution (exec channel)
 * and file operations (SFTP channel). All file operations are SFTP-based,
 * no remote command assumptions (compatible with Windows/Termux/BusyBox).
 *
 * Host-key handling (trust-on-first-use):
 *  - [pinnedHostKey] set  → the server key MUST match, else the connection is
 *    rejected (defends against MITM / key changes).
 *  - [pinnedHostKey] blank + [allowUnknownHostKey] true → connect and capture the
 *    key (used by "Verify & pin" in settings, and as back-compat for un-pinned
 *    devices); the captured value is exposed via [capturedHostKey].
 *  - blank + false → unknown keys are rejected (fail-closed).
 *
 * NOT thread-safe — create a new instance per tool call.
 */
class SshClient(
    private val device: ShellDeviceConfig,
    private val timeoutMs: Int = Constants.NETWORK_CONNECT_TIMEOUT_MS.toInt(),
    private val pinnedHostKey: String = "",
    private val allowUnknownHostKey: Boolean = false,
    /** Optional OpenSSH/PEM private key for public-key auth; blank = password auth only. */
    private val privateKey: String = "",
    /** Passphrase for an encrypted [privateKey]; blank for an unencrypted key. */
    private val keyPassphrase: String = ""
) {
    private var session: Session? = null
    private var identityOwner: JSch? = null

    /** The server host key (base64 of the public-key blob) seen on the last connect. */
    var capturedHostKey: String? = null
        private set

    // ── Connection ─────────────────────────────────────────

    private suspend fun getSession(): Session {
        session?.let { if (it.isConnected) return it }
        return withContext(Dispatchers.IO) {
            close()
            val jsch = JSch()
            var candidate: Session? = null
            try {
                val connected = jsch.getSession(device.sshUser, device.sshHost, device.sshPort).apply {
                    candidate = this
                    configureSshAuthentication(jsch, this, device)
                    hostKeyRepository = TofuHostKeyRepository()
                    setConfig("StrictHostKeyChecking", if (allowUnknownHostKey && pinnedHostKey.isBlank()) "no" else "yes")
                    connect(timeoutMs)
                }
                currentCoroutineContext().ensureActive()
                identityOwner = jsch
                session = connected
                connected
            } catch (error: Throwable) {
                candidate?.disconnect()
                jsch.removeAllIdentity()
                throw error
            }
        }
    }

    /** TOFU host-key verifier: captures the presented key and accepts only a match. */
    private inner class TofuHostKeyRepository : HostKeyRepository {
        override fun check(host: String?, key: ByteArray): Int {
            val incoming = Base64.encodeToString(key, Base64.NO_WRAP)
            capturedHostKey = incoming
            return when {
                pinnedHostKey.isNotBlank() && pinnedHostKey == incoming -> HostKeyRepository.OK
                pinnedHostKey.isNotBlank() -> HostKeyRepository.CHANGED   // mismatch → reject
                else -> HostKeyRepository.NOT_INCLUDED                    // unknown
            }
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = ""
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    companion object {
        /** OpenSSH-style "SHA256:…" fingerprint of a base64 host-key blob, for display. */
        fun fingerprintSha256(base64Key: String): String = try {
            val bytes = Base64.decode(base64Key, Base64.DEFAULT)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: Exception) { "" }
    }

    fun close() {
        try {
            session?.disconnect()
        } finally {
            session = null
            identityOwner?.removeAllIdentity()
            identityOwner = null
        }
    }

    // ── Shell Command ──────────────────────────────────────

    data class CommandResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )

    suspend fun executeCommand(
        command: String,
        workdir: String = "",
        execTimeoutMs: Int = timeoutMs
    ): CommandResult = withContext(Dispatchers.IO) {
        val sess = getSession()
        val cmd = if (workdir.isNotBlank()) "cd ${escapeBash(workdir)} && $command" else command
        val channel = sess.openChannel("exec") as ChannelExec
        channel.setCommand(cmd)
        val stdoutStream = ByteArrayOutputStream()
        val stderrStream = ByteArrayOutputStream()
        channel.setOutputStream(stdoutStream)
        channel.setErrStream(stderrStream)
        try {
            channel.connect(timeoutMs)
            // Wait for completion (channel closes when the remote command exits), bounded by a
            // wall-clock deadline so a non-terminating remote command (`sleep infinity`, an
            // interactive prompt) cannot pin this IO thread and the generation forever.
            val deadline = System.currentTimeMillis() + execTimeoutMs.coerceAtLeast(1_000)
            var timedOut = false
            while (!channel.isClosed) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() >= deadline) {
                    timedOut = true
                    break
                }
                delay(100)
            }
            val exitCode = if (channel.isClosed) channel.exitStatus else -1
            if (timedOut) {
                throw IllegalStateException("Command timed out after ${execTimeoutMs}ms")
            }
            CommandResult(
                stdout = stdoutStream.toString("UTF-8"),
                stderr = stderrStream.toString("UTF-8"),
                exitCode = exitCode
            )
        } finally {
            channel.disconnect()
        }
    }

    // ── SFTP Helpers ───────────────────────────────────────

    private suspend fun <T> withSftp(block: suspend (ChannelSftp) -> T): T {
        val sess = getSession()
        return withContext(Dispatchers.IO) {
            val channel = sess.openChannel("sftp") as ChannelSftp
            channel.connect(timeoutMs)
            try {
                block(channel)
            } finally {
                channel.disconnect()
            }
        }
    }

    private fun ensureParentDirs(sftp: ChannelSftp, path: String) {
        val parts = path.split('/').filter { it.isNotBlank() }
        var current = if (path.startsWith('/')) "" else "."
        for (part in parts) {
            current += "/$part"
            if (current.isBlank() || current == ".") continue
            try { sftp.stat(current) } catch (_: Exception) {
                try { sftp.mkdir(current) } catch (_: Exception) { /* best-effort */ }
            }
        }
    }

    // ── file_read ──────────────────────────────────────────

    suspend fun fileRead(
        path: String,
        offset: Long = 0,
        limit: Long = 0
    ): ShellFileReadResult = withSftp { sftp ->
        try {
            fun readSlice(start: Long, length: Int): ByteArray {
                if (length == 0) return ByteArray(0)
                val buffer = ByteArray(length)
                val bytesRead = sftp.get(path).use { input ->
                    var remaining = start
                    while (remaining > 0) {
                        val skipped = input.skip(remaining)
                        if (skipped > 0) {
                            remaining -= skipped
                        } else if (input.read() >= 0) {
                            remaining -= 1
                        } else {
                            break
                        }
                    }
                    var totalRead = 0
                    while (totalRead < buffer.size) {
                        val count = input.read(buffer, totalRead, buffer.size - totalRead)
                        if (count <= 0) break
                        totalRead += count
                    }
                    totalRead
                }
                return if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
            }

            val totalBytes = sftp.stat(path).size
            val normalizedOffset = offset.coerceAtLeast(0)
            val start = normalizedOffset.coerceAtMost(totalBytes)
            val effectiveLimit = if (limit in 1..SHELL_FILE_READ_MAX_BYTES) {
                limit
            } else {
                SHELL_FILE_READ_MAX_BYTES
            }
            val contentBytes = readSlice(
                start = start,
                length = minOf(effectiveLimit, totalBytes - start).toInt(),
            )
            val (content, consumed) = decodeUtf8FileSlice(
                contentBytes,
                contentBytes.size,
                reachesEndOfFile = start + contentBytes.size >= totalBytes,
            )
            // Small files are read whole once more (unless this slice already was the whole file)
            // for the total line count and the sha256 that expected_sha256 compares against.
            val completeBytes = if (totalBytes <= SHELL_FILE_READ_MAX_BYTES) {
                if (start == 0L && contentBytes.size.toLong() == totalBytes) {
                    contentBytes
                } else {
                    readSlice(0, totalBytes.toInt())
                }
            } else {
                null
            }
            val completeContent = completeBytes?.let { String(it, Charsets.UTF_8) }
            ShellFileReadResult(
                content = content,
                lines = shellFileLineCount(content),
                totalLines = completeContent?.let(::shellFileLineCount) ?: 0,
                totalBytes = totalBytes,
                returnedBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
                offset = start,
                limit = effectiveLimit,
                truncated = start + contentBytes.size < totalBytes,
                bytesRead = consumed.toLong(),
                sha256 = completeBytes?.let(::shellFileSha256).orEmpty(),
            )
        } catch (e: Exception) {
            throw IllegalStateException("SFTP read failed: ${e.message}")
        }
    }

    // ── file_write ─────────────────────────────────────────

    /**
     * Returns null on success, error message string on failure.
     */
    suspend fun fileWrite(
        path: String,
        content: String,
        expectedSha256: String = "",
    ): String? = withSftp { sftp ->
        try {
            if (expectedSha256.isNotBlank()) {
                val current = try {
                    if (sftp.stat(path).isDir) return@withSftp "target is not a regular file"
                    sftp.get(path).use { it.readBytes() }
                } catch (e: com.jcraft.jsch.SftpException) {
                    if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw e
                }
                shellExpectedSha256Error(expectedSha256, current)?.let { return@withSftp it }
            }
            val parent = path.substringBeforeLast('/', "")
            if (parent.isNotBlank()) {
                ensureParentDirs(sftp, parent)
            }
            sftp.put(content.byteInputStream(Charsets.UTF_8), path, ChannelSftp.OVERWRITE)
            null
        } catch (e: Exception) {
            "SFTP write failed: ${e.message}"
        }
    }

    suspend fun fileEdit(
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): ShellFileEditResult = fileEdit(path, listOf(ShellFileEdit(oldString, newString, replaceAll)))

    suspend fun fileEdit(
        path: String,
        edits: List<ShellFileEdit>,
        expectedSha256: String = "",
    ): ShellFileEditResult = withSftp { sftp ->
        try {
            fun readEditableFile(): ByteArray {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                sftp.get(path).use { input ->
                    while (output.size() <= SHELL_FILE_EDIT_MAX_BYTES) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                    }
                }
                return output.toByteArray()
            }

            val attrs = sftp.stat(path)
            if (attrs.isDir) return@withSftp ShellFileEditResult(0, error = "path is not a regular file")
            if (attrs.size > SHELL_FILE_EDIT_MAX_BYTES) {
                return@withSftp ShellFileEditResult(0, error = "file exceeds 16MB edit limit")
            }
            val originalBytes = readEditableFile()
            if (originalBytes.size > SHELL_FILE_EDIT_MAX_BYTES) {
                return@withSftp ShellFileEditResult(0, error = "file exceeds 16MB edit limit")
            }
            shellExpectedSha256Error(expectedSha256, originalBytes)?.let {
                return@withSftp ShellFileEditResult(0, error = it)
            }
            val (editedContent, editResult) = editShellFileContent(
                content = originalBytes.toString(Charsets.UTF_8),
                edits = edits,
            )
            if (editedContent == null) return@withSftp editResult
            val editedBytes = editedContent.toByteArray(Charsets.UTF_8)
            if (editedBytes.size > SHELL_FILE_EDIT_MAX_BYTES) {
                return@withSftp ShellFileEditResult(0, error = "edited file exceeds 16MB limit")
            }
            if (!readEditableFile().contentEquals(originalBytes)) {
                return@withSftp ShellFileEditResult(
                    0,
                    error = "file changed concurrently before edit could be applied",
                )
            }
            sftp.put(editedBytes.inputStream(), path, ChannelSftp.OVERWRITE)
            editResult.copy(sha256 = shellFileSha256(editedBytes))
        } catch (e: Exception) {
            ShellFileEditResult(0, error = "SFTP edit failed: ${e.message}")
        }
    }

    // ── file_glob ──────────────────────────────────────────

    suspend fun fileGlob(
        pattern: String,
        basePath: String = "",
        depth: Int? = null,
    ): Pair<List<String>, Boolean> =
        withSftp { sftp ->
            val base = basePath.ifBlank {
                try { sftp.pwd() } catch (_: Exception) { "/" }
            }.trimEnd('/')
            val allFiles = mutableListOf<String>()
            // null = legacy full recursion; <=0 = explicit unlimited; >=1 = max levels.
            val remaining = if (depth == null || depth <= 0) -1 else depth
            sftpListRecursive(sftp, base, allFiles, remaining)
            val matches = globMatch(allFiles, base, pattern)
            matches.take(SHELL_FILE_GLOB_MAX_MATCHES) to
                (matches.size > SHELL_FILE_GLOB_MAX_MATCHES)
        }

    // remaining: levels still allowed including the current dir's files. -1 = unlimited;
    // 1 = only this dir's files (no descent); >1 = descend with one fewer level.
    private fun sftpListRecursive(sftp: ChannelSftp, dir: String, result: MutableList<String>, remaining: Int = -1) {
        try {
            @Suppress("UNCHECKED_CAST")
            val entries = sftp.ls(dir) as? List<ChannelSftp.LsEntry> ?: return
            for (entry in entries) {
                val name = entry.filename
                if (name == "." || name == "..") continue
                val fullPath = "$dir/$name"
                if (entry.attrs.isDir) {
                    if (name in SHELL_FILE_SEARCH_SKIPPED_DIRS) continue
                    if (remaining < 0 || remaining > 1) {
                        sftpListRecursive(sftp, fullPath, result, if (remaining < 0) -1 else remaining - 1)
                    }
                } else {
                    result.add(fullPath)
                }
            }
        } catch (_: Exception) {
            // Permission denied or directory doesn't exist — skip
        }
    }

    private fun globMatch(files: List<String>, basePath: String, pattern: String): List<String> {
        val adjustedPattern = if (pattern.contains('/')) pattern else "**/$pattern"
        val fullPattern = "$basePath/$adjustedPattern"
        return files.filter { file -> PortableGlobMatcher.matches(fullPattern, file) }
    }

    // ── file_grep ──────────────────────────────────────────

    /**
     * grep for pattern in files.
     * Strategy: try server-side grep first (fast), fall back to SFTP read+grep locally. Context
     * requests go straight to the SFTP path, whose structured output avoids parsing grep -C
     * separators that are ambiguous for paths containing '-'.
     */
    suspend fun fileGrep(
        pattern: String,
        basePath: String = "",
        fileGlob: String = "",
        options: ShellGrepOptions = ShellGrepOptions(),
    ): Result<ShellGrepResult> {
        val base = basePath.ifBlank { "." }
        if (options.context > 0) return fallbackGrep(pattern, base, fileGlob, options)

        // Try server-side grep via exec channel first
        return try {
            val grepCmd = buildString {
                // -I skips binary files (matches the local-fallback NUL heuristic below).
                append("grep -rnI ")
                if (options.caseInsensitive) append("-i ")
                SHELL_FILE_SEARCH_SKIPPED_DIRS.forEach { append("--exclude-dir=$it ") }
                // The glob is model-supplied input on a non-confirmed (read-only) tool path, so it
                // must be shell-escaped exactly like pattern/base — otherwise it is a command
                // injection vector that bypasses the remote-mutation confirmation gate.
                if (fileGlob.isNotBlank()) append("--include=${escapeBash(fileGlob)} ")
                append("-- ")
                append(escapeBash(pattern))
                append(" ")
                append(escapeBash(base))
            }
            val result = executeCommand(grepCmd)
            when {
                result.exitCode == 0 || result.exitCode == 1 -> {
                    // 0=matches found, 1=no matches (both valid)
                    val matches = result.stdout.lines()
                        .filter { it.isNotBlank() }
                        .mapNotNull { parseGrepLine(it) }
                    Result.success(
                        ShellGrepResult(
                            matches = matches.take(SHELL_FILE_GREP_MAX_MATCHES),
                            truncated = matches.size > SHELL_FILE_GREP_MAX_MATCHES,
                        ),
                    )
                }
                else -> {
                    // exitCode >= 2: grep error (not installed, bad args, etc.) — fallback
                    fallbackGrep(pattern, base, fileGlob, options)
                }
            }
        } catch (_: Exception) {
            fallbackGrep(pattern, base, fileGlob, options)
        }
    }

    private suspend fun fallbackGrep(
        regex: String,
        basePath: String,
        fileGlob: String,
        options: ShellGrepOptions,
    ): Result<ShellGrepResult> {
        return try {
            val globPattern = fileGlob.ifBlank { "*" }
            val files = fileGlob(globPattern, basePath).first
            val regexOptions = if (options.caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val pattern = try {
                Regex(regex, regexOptions)
            } catch (e: Exception) {
                Regex(java.util.regex.Pattern.quote(regex), regexOptions)
            }
            // One match past the cap distinguishes an exact-limit result from a truncated one.
            val limit = SHELL_FILE_GREP_MAX_MATCHES + 1
            val allMatches = mutableListOf<ShellClient.GrepMatch>()
            var skippedLarge = 0
            for (file in files) {
                try {
                    val read = fileRead(file, 0, SHELL_FILE_GREP_MAX_FILE_BYTES)
                    if (read.totalBytes > SHELL_FILE_GREP_MAX_FILE_BYTES) {
                        skippedLarge += 1
                        continue
                    }
                    // Skip binary files (NUL-byte heuristic), as grep -I would.
                    if (read.content.contains('\u0000')) continue
                    grepShellFileText(file, read.content, pattern, options.context, limit, allMatches)
                    if (allMatches.size >= limit) break
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Skip unreadable files
                }
            }
            Result.success(
                ShellGrepResult(
                    matches = allMatches.take(SHELL_FILE_GREP_MAX_MATCHES),
                    truncated = allMatches.size > SHELL_FILE_GREP_MAX_MATCHES,
                    skippedLargeFiles = skippedLarge,
                ),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseGrepLine(line: String): ShellClient.GrepMatch? {
        // Format: "path:lineNum:content" or "path:lineNum:col:content"
        val firstColon = line.indexOf(':')
        if (firstColon < 0) return null
        val path = line.substring(0, firstColon)
        val afterPath = line.substring(firstColon + 1)
        val secondColon = afterPath.indexOf(':')
        if (secondColon < 0) return null
        val lineNumStr = afterPath.substring(0, secondColon)
        val content = afterPath.substring(secondColon + 1).shellGrepLine()
        val lineNum = lineNumStr.toIntOrNull() ?: return null
        return ShellClient.GrepMatch(path = path, line = lineNum, content = content)
    }

    // ── Shell Escaping ─────────────────────────────────────

    private fun escapeBash(s: String): String {
        // Wrap in single quotes; escape any embedded single quotes as '\''
        return "'${s.replace("'", "'\\''")}'"
    }
}
