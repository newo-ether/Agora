package com.newoether.agora.webui

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.tool.ToolImageStore
import com.newoether.agora.ui.chat.message.mergeAdjacentSegments
import java.io.File
import java.io.IOException
import com.newoether.agora.ui.chat.findMetaForIndex
import com.newoether.agora.util.AttachmentFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves only one conversation-owned persisted tool image; never accepts a browser file path. */
internal class WebUiToolImages(
    private val directory: File,
    private val attachmentDirectory: File? = null,
    private val markdownImage: suspend (String) -> com.newoether.agora.model.ToolImageAttachment? = { null },
    private val loadMessage: suspend (String, String) -> ChatMessage?,
) {
    suspend fun openMarkdown(conversationId: String, messageId: String, link: String): WebUiToolImageFile? {
        if (link.length > 8_192) return null
        val message = loadMessage(conversationId, messageId)?.takeIf { it.id == messageId } ?: return null
        if (!withContext(Dispatchers.Default) { message.ownsShellMarkdownImage(link) }) return null
        val image = markdownImage(link) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val file = File(image.path)
                val measured = inspectWebUiImageArtifact(file, image, ToolImageStore.MAX_IMAGE_BYTES)
                    ?: return@withContext null
                WebUiToolImageFile(file, measured.sizeBytes, measured.mimeType)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }
    /** Reads one persisted occurrence; roots and paths come only from the owning message. */
    suspend fun consumeAttachment(
        conversationId: String, messageId: String, index: Int,
        consume: suspend (File, String) -> Unit,
    ): Boolean {
        if (index < 0) return false
        val message = loadMessage(conversationId, messageId)?.takeIf { it.id == messageId } ?: return false
        val meta = findMetaForIndex(message.attachmentMeta, index)
        if (meta?.storage?.canPreview == false ||
            (meta?.unavailable == true && meta.type !in listOf("pdf", "video"))
        ) return false
        val stored = message.images.getOrNull(index) ?: return false
        val artifact = meta?.imageIndex?.let { meta.imageArtifacts.getOrNull(index - it) }
            ?: message.imageArtifacts[stored]
        if (artifact?.unavailable == true || stored.isBlank() ||
            (artifact != null && artifact.path != stored)
        ) return false
        val rootDirectory = attachmentDirectory ?: return false
        val owner = Any()
        return withContext(Dispatchers.IO) {
            try {
                val root = rootDirectory.toPath().toRealPath()
                val path = File(stored.removePrefix("file://")).toPath().toRealPath()
                val allowed = allowedAttachmentPath(root, path)
                val file = path.toFile()
                if (!allowed || !file.isFile || file.length() !in 1..AttachmentFiles.MAX_ATTACHMENT_BYTES) {
                    return@withContext false
                }
                AttachmentFiles.retainLivePath(owner, file.path)
                val measured = inspectWebUiImageArtifact(file, artifact) ?: return@withContext false
                consume(file, measured.mimeType)
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            } finally {
                AttachmentFiles.releaseLivePaths(owner)
            }
        }
    }
    suspend fun open(conversationId: String, messageId: String, detailIndex: Int, imageIndex: Int): WebUiToolImageFile? {
        if (detailIndex < 0 || imageIndex < 0) return null
        val message = loadMessage(conversationId, messageId)?.takeIf { it.id == messageId } ?: return null
        val segment = mergeAdjacentSegments(message.segments.orEmpty())
            .filter { it.type != "answer" && it.type != "error" }
            .getOrNull(detailIndex)?.takeIf { it.type == "tool" } ?: return null
        val image = segment.toolImages.getOrNull(imageIndex) ?: return null
        if (image.unavailable || image.path.isBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                val root = directory.takeIf(File::isDirectory)?.toPath()?.toRealPath()
                val path = File(image.path).toPath().toRealPath()
                val file = path.toFile()
                val allowed = (root != null && path.startsWith(root) && path != root) ||
                    attachmentDirectory?.takeIf(File::isDirectory)?.let {
                        allowedAttachmentPath(it.toPath().toRealPath(), path)
                    } == true
                if (!allowed || !file.isFile || file.length() !in 1..ToolImageStore.MAX_IMAGE_BYTES) {
                    return@withContext null
                }
                val measured = inspectWebUiImageArtifact(file, image, ToolImageStore.MAX_IMAGE_BYTES)
                    ?: return@withContext null
                WebUiToolImageFile(file, measured.sizeBytes, measured.mimeType)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }

    private fun allowedAttachmentPath(root: java.nio.file.Path, path: java.nio.file.Path): Boolean =
        (path.parent == root && GENERATED_IMAGE.matches(path.fileName.toString())) ||
            listOf("images", "attachments", "run-inputs", "fork-attachments").any { child ->
                path.startsWith(root.resolve(child)) && path != root.resolve(child)
            }

    companion object {
        private val GENERATED_IMAGE = Regex("(?:img|pdf|vid|tool|phone)_[A-Za-z0-9_-]+\\.(?:jpg|jpeg|png)", RegexOption.IGNORE_CASE)
    }
}

internal data class WebUiToolImageFile(val file: File, val size: Long, val mimeType: String)
