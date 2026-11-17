package com.newoether.agora.sandbox

import android.system.Os
import com.newoether.agora.util.SHELL_FILE_EDIT_MAX_BYTES
import com.newoether.agora.util.SHELL_FILE_GLOB_MAX_MATCHES
import com.newoether.agora.util.SHELL_FILE_GREP_MAX_CONTEXT_LINES
import com.newoether.agora.util.SHELL_FILE_GREP_MAX_FILE_BYTES
import com.newoether.agora.util.SHELL_FILE_GREP_MAX_MATCHES
import com.newoether.agora.util.SHELL_FILE_READ_MAX_BYTES
import com.newoether.agora.util.SHELL_FILE_WRITE_MAX_BYTES
import com.newoether.agora.util.ShellClient
import com.newoether.agora.util.ShellFileEdit
import com.newoether.agora.util.ShellFileEditResult
import com.newoether.agora.util.ShellFileReadResult
import com.newoether.agora.util.ShellGrepOptions
import com.newoether.agora.util.ShellGrepResult
import com.newoether.agora.util.decodeUtf8FileSlice
import com.newoether.agora.util.editShellFileContent
import com.newoether.agora.util.grepShellFileText
import com.newoether.agora.util.shellFileLineCount
import com.newoether.agora.util.shellExpectedSha256Error
import com.newoether.agora.util.shellFileSha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
internal suspend fun SandboxPathResolver.fileRead(
    path: String,
    offset: Long,
    limit: Long,
): ShellFileReadResult = withContext(Dispatchers.IO) {
    val file = resolvePath(path)
    if (!file.exists()) throw IllegalStateException("File not found: $path")
    val totalBytes = file.length()
    val normalizedOffset = offset.coerceAtLeast(0)
    val start = normalizedOffset.coerceAtMost(totalBytes)
    val effectiveLimit = if (limit in 1..SHELL_FILE_READ_MAX_BYTES) {
        limit
    } else {
        SHELL_FILE_READ_MAX_BYTES
    }
    val requestedBytes = minOf(effectiveLimit, totalBytes - start).toInt()
    val buffer = ByteArray(requestedBytes)
    val bytesRead = java.io.RandomAccessFile(file, "r").use { input ->
        input.seek(start)
        var totalRead = 0
        while (totalRead < buffer.size) {
            val count = input.read(buffer, totalRead, buffer.size - totalRead)
            if (count <= 0) break
            totalRead += count
        }
        totalRead
    }
    val (content, consumed) = decodeUtf8FileSlice(
        buffer,
        bytesRead,
        reachesEndOfFile = start + bytesRead >= totalBytes,
    )
    val completeBytes = if (totalBytes <= SHELL_FILE_READ_MAX_BYTES) file.readBytes() else null
    ShellFileReadResult(
        bytesRead = consumed.toLong(),
        content = content,
        lines = shellFileLineCount(content),
        totalLines = completeBytes?.let { shellFileLineCount(String(it, Charsets.UTF_8)) } ?: 0,
        // Whole-file hash for expected_sha256, bounded like Conch's 16 MB editable size.
        sha256 = completeBytes?.let(::shellFileSha256)
            ?: if (totalBytes <= SANDBOX_HASHABLE_FILE_BYTES) streamingFileSha256(file) else "",
        totalBytes = totalBytes,
        returnedBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
        offset = start,
        limit = effectiveLimit,
        truncated = start + bytesRead < totalBytes,
    )
}

/** Files up to this size get a whole-file sha256 on read; matches Conch's maxEditableFileSize. */
private const val SANDBOX_HASHABLE_FILE_BYTES = 16L shl 20

private fun streamingFileSha256(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun writeSandboxFileAtomically(
    file: File,
    content: ByteArray,
    beforeReplace: () -> String? = { null },
): String? {
    if (file.exists() && !file.isFile) return "path is not a regular file"
    val parent = file.parentFile ?: return "file has no parent directory"
    if (!parent.exists() && !parent.mkdirs()) return "failed to create parent directory"
    val mode = if (file.exists()) {
        Os.stat(file.absolutePath).st_mode and 0x1FF
    } else {
        0x1A4
    }
    val tempFile = File.createTempFile(".${file.name}.", ".tmp", parent)
    try {
        FileOutputStream(tempFile).use { output ->
            output.write(content)
            output.fd.sync()
        }
        Os.chmod(tempFile.absolutePath, mode)
        beforeReplace()?.let { return it }
        Os.rename(tempFile.absolutePath, file.absolutePath)
    } finally {
        tempFile.delete()
    }
    return null
}

/** The manager owns the mutation boundary and creates this resolver inside it. */
internal suspend fun SandboxPathResolver.fileWrite(
    path: String,
    content: String,
    expectedSha256: String = "",
): String? = withContext(Dispatchers.IO) {
    try {
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        if (contentBytes.size > SHELL_FILE_WRITE_MAX_BYTES) {
            return@withContext "content exceeds 1MB limit"
        }
        val file = resolvePath(path)
        if (expectedSha256.isNotBlank()) {
            if (file.exists() && !file.isFile) return@withContext "path is not a regular file"
            val current = if (file.exists()) file.readBytes() else null
            shellExpectedSha256Error(expectedSha256, current)?.let { return@withContext it }
        }
        writeSandboxFileAtomically(file, contentBytes)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        "Sandbox file write failed: ${e.message}"
    }
}

internal suspend fun SandboxPathResolver.fileGlob(
    pattern: String,
    basePath: String,
    homeMountPath: String,
    depth: Int?,
): Pair<List<String>, Boolean> = withContext(Dispatchers.IO) {
    currentCoroutineContext().ensureActive()
    val virtualBase = basePath.ifBlank { homeMountPath }
    val base = resolveSandboxPath(virtualBase)
    val files = mutableListOf<String>()
    // null = legacy full recursion; <=0 = explicit unlimited; >=1 = max levels.
    val remaining = if (depth == null || depth <= 0) -1 else depth
    walkVirtualFiles(
        base.file, files, base.physicalRoot.canonicalPath, base.virtualRoot, remaining,
        virtualBasePath = virtualBase,
    )
    currentCoroutineContext().ensureActive()
    val matches = globMatch(files, pattern, virtualBase)
    matches.take(SHELL_FILE_GLOB_MAX_MATCHES) to
        (matches.size > SHELL_FILE_GLOB_MAX_MATCHES)
}

internal suspend fun SandboxPathResolver.fileGrep(
    pattern: String,
    basePath: String,
    homeMountPath: String,
    fileGlob: String,
    options: ShellGrepOptions,
): Result<ShellGrepResult> = withContext(Dispatchers.IO) {
    try {
        val regex = if (options.caseInsensitive) {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } else {
            Regex(pattern)
        }
        val context = options.context.coerceIn(0, SHELL_FILE_GREP_MAX_CONTEXT_LINES)
        val virtualBase = basePath.ifBlank { homeMountPath }
        val base = resolveSandboxPath(virtualBase)
        val allFiles = mutableListOf<String>()
        walkVirtualFiles(
            base.file,
            allFiles,
            base.physicalRoot.canonicalPath,
            base.virtualRoot,
            virtualBasePath = virtualBase,
        )
        currentCoroutineContext().ensureActive()
        val files = if (fileGlob.isBlank()) allFiles else globMatch(allFiles, fileGlob, virtualBase)
        val matches = mutableListOf<ShellClient.GrepMatch>()
        // Collect one extra match so truncated is exact rather than "hit the cap".
        val collectLimit = SHELL_FILE_GREP_MAX_MATCHES + 1
        var skippedLargeFiles = 0
        for (file in files) {
            currentCoroutineContext().ensureActive()
            if (matches.size >= collectLimit) break
            try {
                val resolved = if (file.startsWith("/")) resolvePath(file) else resolvePath("/$file")
                if (!resolved.isFile) continue
                if (resolved.length() > SHELL_FILE_GREP_MAX_FILE_BYTES) {
                    skippedLargeFiles += 1
                    continue
                }
                val text = resolved.readText(Charsets.UTF_8)
                // Skip binary files: a NUL byte in the content is the standard
                // heuristic grep itself uses to avoid emitting garbage matches.
                if (text.contains('\u0000')) continue
                grepShellFileText(file, text, regex, context, collectLimit, matches)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {}
        }
        Result.success(
            ShellGrepResult(
                matches = matches.take(SHELL_FILE_GREP_MAX_MATCHES),
                truncated = matches.size > SHELL_FILE_GREP_MAX_MATCHES,
                skippedLargeFiles = skippedLargeFiles,
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/** The manager owns the mutation boundary and creates this resolver inside it. */
internal suspend fun SandboxPathResolver.fileEdit(
    path: String,
    edits: List<ShellFileEdit>,
    expectedSha256: String = "",
): ShellFileEditResult = withContext(Dispatchers.IO) {
    try {
        val file = resolvePath(path)
        if (!file.exists()) {
            return@withContext ShellFileEditResult(0, error = "File not found: $path")
        }
        if (!file.isFile) {
            return@withContext ShellFileEditResult(0, error = "path is not a regular file")
        }
        if (file.length() > SHELL_FILE_EDIT_MAX_BYTES) {
            return@withContext ShellFileEditResult(0, error = "file exceeds 16MB edit limit")
        }

        val originalBytes = file.readBytes()
        if (originalBytes.size > SHELL_FILE_EDIT_MAX_BYTES) {
            return@withContext ShellFileEditResult(0, error = "file exceeds 16MB edit limit")
        }
        shellExpectedSha256Error(expectedSha256, originalBytes)?.let {
            return@withContext ShellFileEditResult(0, error = it)
        }
        val (editedContent, editResult) = editShellFileContent(
            content = originalBytes.toString(Charsets.UTF_8),
            edits = edits,
        )
        if (editedContent == null) return@withContext editResult

        val editedBytes = editedContent.toByteArray(Charsets.UTF_8)
        if (editedBytes.size > SHELL_FILE_EDIT_MAX_BYTES) {
            return@withContext ShellFileEditResult(0, error = "edited file exceeds 16MB limit")
        }
        val writeError = writeSandboxFileAtomically(file, editedBytes) {
            if (!file.readBytes().contentEquals(originalBytes)) {
                "file changed concurrently before edit could be applied"
            } else {
                null
            }
        }
        if (writeError != null) {
            return@withContext ShellFileEditResult(0, error = writeError)
        }
        editResult.copy(sha256 = shellFileSha256(editedBytes))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        ShellFileEditResult(0, error = "Sandbox file edit failed: ${e.message}")
    }
}
