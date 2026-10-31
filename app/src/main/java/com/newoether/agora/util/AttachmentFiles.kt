package com.newoether.agora.util

import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.SelectedAttachment
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Single home for deleting files that back a [SelectedAttachment], so the composer, drafts, and
 * generation queue don't duplicate — and drift on — the same delete logic.
 *
 * Ownership rule: app-private copies remain reference-managed by drafts/messages. A Local Sandbox
 * copy is reclaimable only while it is pending; its Send transfer makes it a runtime asset that this
 * owner must never delete, even if a queued send or generation is later discarded.
 *
 * Never touches the original content:// [SelectedAttachment.uri] — that isn't ours to delete.
 */
object AttachmentFiles {
    private val ownershipLock = Any()
    private val livePaths = java.util.IdentityHashMap<Any, Set<String>>()

    /** Live owners are process-local; durable ownership remains in Room. */
    internal fun setLivePaths(owner: Any, paths: Collection<String>) = synchronized(ownershipLock) {
        val normalized = paths.asSequence().filter(String::isNotBlank)
            .map { File(it.removePrefix("file://")).canonicalPath }.toCollection(linkedSetOf())
        if (normalized.isEmpty()) livePaths.remove(owner) else livePaths[owner] = normalized
        Unit
    }

    internal fun retainLivePath(owner: Any, path: String) = synchronized(ownershipLock) {
        if (path.isNotBlank()) {
            livePaths[owner] = livePaths[owner].orEmpty() + File(path.removePrefix("file://")).canonicalPath
        }
        Unit
    }

    internal fun releaseLivePath(owner: Any, path: String) = synchronized(ownershipLock) {
        if (path.isBlank()) return@synchronized
        val remaining = livePaths[owner].orEmpty() - File(path.removePrefix("file://")).canonicalPath
        if (remaining.isEmpty()) livePaths.remove(owner) else livePaths[owner] = remaining
        Unit
    }

    internal fun releaseLivePaths(owner: Any): Set<String> = synchronized(ownershipLock) {
        livePaths.remove(owner).orEmpty()
    }

    internal fun deleteUnownedPaths(paths: Collection<String>) {
        paths.filter(String::isNotBlank).distinct().forEach { path ->
            runCatching {
                val file = File(path.removePrefix("file://"))
                deleteIfUnowned(file)
                file.parentFile?.takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
            }
        }
    }

    /** Called at the final unlink boundary, after the caller's durable reference check. */
    internal fun deleteIfUnowned(file: File): Boolean = synchronized(ownershipLock) {
        if (livePaths.values.any { file.canonicalPath in it }) return false
        check(!file.exists() || file.delete()) { "Unable to delete attachment file" }
        true
    }

    internal fun ownedPaths(attachments: List<SelectedAttachment>): Set<String> = attachments
        .flatMapTo(linkedSetOf()) { attachment ->
            (listOfNotNull(attachment.localPath) + attachment.processedFrames.orEmpty() +
                attachment.preRenderedPaths.orEmpty() + attachment.imageArtifacts
                    .filterNot { it.unavailable }.map { it.path }).filter(String::isNotBlank)
        }

    const val MAX_ATTACHMENT_BYTES: Long = 100L * 1024L * 1024L

    internal sealed class CopyResult {
        data class Success(val bytesCopied: Long) : CopyResult()
        data object TooLarge : CopyResult()
        data class Failure(val cause: Throwable) : CopyResult()
    }

    /**
     * Copies [input] to [target] while enforcing [maxBytes] against bytes actually read. This method
     * owns and closes [input]. A size hint can reject obvious overflow early, but never replaces the
     * streamed limit. Any incomplete output is deleted before a failure result is returned.
     */
    internal fun copyBounded(
        input: InputStream,
        target: File,
        expectedSize: Long? = null,
        maxBytes: Long = MAX_ATTACHMENT_BYTES,
        onProgress: (copiedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): CopyResult {
        require(maxBytes >= 0L)
        val progressTotal = expectedSize?.takeIf { it >= 0L }
        if (progressTotal != null && progressTotal > maxBytes) {
            runCatching { input.close() }
            deleteQuietly(target)
            return CopyResult.TooLarge
        }

        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            runCatching { input.close() }
            deleteQuietly(target)
            return CopyResult.Failure(IllegalStateException("Unable to create attachment directory"))
        }

        var copiedBytes = 0L
        var tooLarge = false
        return try {
            input.use { source ->
                FileOutputStream(target).use { output ->
                    onProgress(0L, progressTotal)
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (count.toLong() > maxBytes - copiedBytes) {
                            tooLarge = true
                            break
                        }
                        output.write(buffer, 0, count)
                        copiedBytes += count
                        onProgress(copiedBytes, progressTotal)
                    }
                }
            }
            if (tooLarge) {
                deleteQuietly(target)
                CopyResult.TooLarge
            } else {
                CopyResult.Success(copiedBytes)
            }
        } catch (failure: Exception) {
            deleteQuietly(target)
            if (failure is java.util.concurrent.CancellationException) throw failure
            CopyResult.Failure(failure)
        }
    }

    fun sanitizeFileName(originalName: String?): String {
        val sanitized = originalName.orEmpty()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]"), "_")
            .trim()
        return sanitized.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"
    }

    /** Delete every private file backing [att]: extracted video frames, rendered PDF pages, and the
     *  copied-to-private image/file. Best-effort and exception-safe per file. */
    fun deleteBacking(att: SelectedAttachment) {
        if (!att.storage.reclaimWhenAbandoned) return
        ownedPaths(listOf(att)).forEach(::deleteQuietly)
        if (att.storage == AttachmentStorage.LOCAL_SANDBOX_PENDING) {
            att.localPath?.takeIf(String::isNotBlank)?.let(::deleteEmptyParent)
        }
    }

    /** Delete the backing files for every attachment in [attachments]. */
    fun deleteBacking(attachments: List<SelectedAttachment>) {
        attachments.forEach { deleteBacking(it) }
    }

    /** Remove UUID directories after reference-aware draft cleanup has deleted their payloads. */
    fun deleteEmptySandboxParents(attachments: List<SelectedAttachment>) {
        attachments.asSequence()
            .filter { it.storage == AttachmentStorage.LOCAL_SANDBOX_PENDING }
            .mapNotNull(SelectedAttachment::localPath)
            .forEach(::deleteEmptyParent)
    }

    private fun deleteEmptyParent(path: String) {
        runCatching { File(path).parentFile?.takeIf(File::isDirectory)?.delete() }
    }

    private fun deleteQuietly(path: String) {
        if (path.isBlank()) return
        runCatching { deleteIfUnowned(File(path.removePrefix("file://"))) }
    }

    private fun deleteQuietly(file: File) {
        runCatching { file.delete() }
    }
}
