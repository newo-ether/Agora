package com.newoether.agora.viewmodel

import androidx.room.withTransaction
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.AttachmentFiles
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import java.io.File

/** Reclaims private attachments only while their durable ownership is transactionally stable. */
internal class AttachmentOrphanSweeper(
    private val database: ChatDatabase,
    private val filesDirectory: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val chatDao = database.chatDao()

    suspend fun sweep() {
        val referenced = collectReferences()
        val cutoffNow = now()
        deleteOldUnreferencedRootAttachments(referenced, cutoffNow)
        deleteOldUnreferencedFiles(File(filesDirectory, "images"), "camera_", referenced, cutoffNow)
        listOf(
            File(filesDirectory, "run-inputs"),
            File(filesDirectory, "fork-attachments"),
        ).forEach { directory ->
            deleteOldUnreferencedFiles(directory, requiredPrefix = null, referenced, cutoffNow)
        }
    }

    suspend fun deleteExact(path: String): Boolean {
        val normalized = normalizePath(path)
        val root = filesDirectory.canonicalFile
        val file = File(normalized).canonicalFile
        require(file != root && file.path.startsWith(root.path + File.separator)) {
            "Attachment debt is outside app-private storage"
        }
        // An ASCII token also matches legacy/raw and escaped JSON. Only canonical full paths
        // decide ownership; equal basenames in different directories are not equal references.
        val pathToken = Regex("[A-Za-z0-9._-]+").findAll(file.name)
            .maxByOrNull { it.value.length }?.value.orEmpty()
        val completed = database.withTransaction {
            if (normalized in collectReferences(pathToken)) return@withTransaction true
            // Keep reference verification and unlink in one transaction with Send's ownership
            // transfer. Separate message/draft snapshots can miss both sides of that transfer.
            AttachmentFiles.deleteIfUnowned(file)
        }
        val sandboxRoot = File(root, "sandbox-home").canonicalFile
        val parent = file.parentFile?.canonicalFile
        if (parent != null && parent != sandboxRoot && parent.path.startsWith(sandboxRoot.path)) {
            runCatching { parent.takeIf { it.listFiles().isNullOrEmpty() }?.delete() }
        }
        return completed
    }

    private suspend fun collectReferences(pathToken: String? = null): Set<String> = HashSet<String>().also { referenced ->
        collectMessageReferences(referenced, pathToken)
        collectDraftReferences(referenced, pathToken)
    }

    private suspend fun collectMessageReferences(referenced: MutableSet<String>, pathToken: String?) {
        var afterMessageId: String? = null
        while (true) {
            val page = chatDao.getMessageAttachmentReferencesPage(
                afterId = afterMessageId,
                limit = DATABASE_SCAN_PAGE_SIZE,
                pathToken = pathToken,
            )
            page.forEach { message ->
                message.images.forEach { referenced.add(normalizePath(it)) }
                message.attachmentMeta?.let { json ->
                    runCatching { Json.decodeFromString<AttachmentMeta>(json) }.getOrElse {
                        if (pathToken != null) throw it
                        null
                    }
                        ?.items?.forEach { item ->
                            item.notesThumbnailPath?.let { referenced.add(normalizePath(it)) }
                            item.sourceThumbnailPath?.let { referenced.add(normalizePath(it)) }
                            item.originalUri?.takeIf { it.startsWith("file://") }
                                ?.let { referenced.add(normalizePath(it)) }
                        }
                }
            }
            afterMessageId = page.lastOrNull()?.id
            if (page.size < DATABASE_SCAN_PAGE_SIZE) break
            yield()
        }
    }

    private suspend fun collectDraftReferences(referenced: MutableSet<String>, pathToken: String?) {
        var afterConversationId: String? = null
        while (true) {
            val page = chatDao.getConversationDraftAttachmentReferencesPage(
                afterId = afterConversationId,
                limit = DATABASE_SCAN_PAGE_SIZE,
                pathToken = pathToken,
            )
            page.forEach { conversation ->
                runCatching {
                    Json.decodeFromString<List<SelectedAttachment>>(conversation.draftAttachments)
                }.getOrElse {
                    if (pathToken != null) throw it
                    null
                }?.forEach { attachment ->
                    attachment.localPath?.let { referenced.add(normalizePath(it)) }
                    attachment.notesThumbnailPath?.let { referenced.add(normalizePath(it)) }
                    attachment.sourceThumbnailPath?.let { referenced.add(normalizePath(it)) }
                    attachment.processedFrames?.forEach { referenced.add(normalizePath(it)) }
                    attachment.preRenderedPaths?.forEach { referenced.add(normalizePath(it)) }
                }
            }
            afterConversationId = page.lastOrNull()?.id
            if (page.size < DATABASE_SCAN_PAGE_SIZE) break
            yield()
        }

        chatDao.getNewChatDraftAttachmentReference(pathToken)?.let { newChat ->
            runCatching {
                Json.decodeFromString<List<SelectedAttachment>>(newChat.draftAttachments)
            }.getOrElse {
                if (pathToken != null) throw it
                null
            }?.forEach { attachment ->
                attachment.localPath?.let { referenced.add(normalizePath(it)) }
                    attachment.notesThumbnailPath?.let { referenced.add(normalizePath(it)) }
                    attachment.sourceThumbnailPath?.let { referenced.add(normalizePath(it)) }
                attachment.processedFrames?.forEach { referenced.add(normalizePath(it)) }
                attachment.preRenderedPaths?.forEach { referenced.add(normalizePath(it)) }
            }
        }
    }

    private suspend fun deleteOldUnreferencedRootAttachments(
        referenced: Set<String>,
        cutoffNow: Long,
    ) {
        val prefixes = arrayOf("att_", "vid_", "img_", "pdf_")
        filesDirectory.listFiles { file ->
            file.isFile && prefixes.any { prefix -> file.name.startsWith(prefix) }
        }?.forEach { file ->
            deleteIfOldAndUnreferenced(file, referenced, cutoffNow)
        }
    }

    private suspend fun deleteOldUnreferencedFiles(
        directory: File,
        requiredPrefix: String?,
        referenced: Set<String>,
        cutoffNow: Long,
    ) {
        directory.listFiles { file ->
            file.isFile && (requiredPrefix == null || file.name.startsWith(requiredPrefix))
        }?.forEach { file ->
            deleteIfOldAndUnreferenced(file, referenced, cutoffNow)
        }
    }

    private fun normalizePath(path: String): String {
        val raw = path.removePrefix("file://")
        return runCatching { File(raw).canonicalPath }.getOrElse { File(raw).absolutePath }
    }

    private suspend fun deleteIfOldAndUnreferenced(
        file: File,
        referenced: Set<String>,
        cutoffNow: Long,
    ) {
        if (
            normalizePath(file.absolutePath) !in referenced &&
            cutoffNow - file.lastModified() > MINIMUM_FILE_AGE_MS
        ) {
            deleteExact(file.absolutePath)
        }
    }

    private companion object {
        const val DATABASE_SCAN_PAGE_SIZE = 64
        const val MINIMUM_FILE_AGE_MS = 60 * 60 * 1000L
    }
}
