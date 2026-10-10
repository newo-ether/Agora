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
    private val loadMessage: suspend (String, String) -> ChatMessage?,
) {
    /** Reads one persisted occurrence; roots and paths come only from the owning message. */
    suspend fun consumeAttachment(
        conversationId: String, messageId: String, index: Int,
        consume: suspend (File, String) -> Unit,
    ): Boolean {
        if (index < 0) return false
        val message = loadMessage(conversationId, messageId)?.takeIf { it.id == messageId } ?: return false
        val meta = findMetaForIndex(message.attachmentMeta, index)
        if (meta?.unavailable == true || meta?.storage?.canPreview == false) return false
        val stored = message.images.getOrNull(index) ?: return false
        val mime = when (meta?.type) {
            "video" -> meta.mimeType?.takeIf { it in setOf("video/mp4", "video/webm", "video/quicktime") }
            "file" -> return false
            else -> meta?.mimeType?.takeIf { it in RASTER_TYPES } ?: "image/jpeg"
        } ?: return false
        val rootDirectory = attachmentDirectory ?: return false
        val owner = Any()
        return withContext(Dispatchers.IO) {
            try {
                val root = rootDirectory.toPath().toRealPath()
                val path = File(stored.removePrefix("file://")).toPath().toRealPath()
                val allowed = (path.parent == root && path.fileName.toString().matches(
                    Regex("img_[0-9a-fA-F-]{36}\\.jpg"),
                )) || listOf("images", "attachments", "run-inputs", "fork-attachments").any {
                    path.startsWith(root.resolve(it))
                }
                val file = path.toFile()
                if (!allowed || !file.isFile || file.length() !in 1..104_857_600L) return@withContext false
                AttachmentFiles.retainLivePath(owner, file.path)
                consume(file, mime)
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
        if (image.path.isBlank() || image.sizeBytes !in 1..ToolImageStore.MAX_IMAGE_BYTES) return null
        val mime = image.mimeType.lowercase()
        if (mime !in RASTER_TYPES) return null
        return withContext(Dispatchers.IO) {
            try {
                val root = directory.toPath().toRealPath()
                val path = File(image.path).toPath().toRealPath()
                val file = path.toFile()
                if (!path.startsWith(root) || path == root || !file.isFile || file.length() != image.sizeBytes) {
                    return@withContext null
                }
                WebUiToolImageFile(file, image.sizeBytes, mime)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }

    companion object {
        private val RASTER_TYPES = setOf(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif",
            "image/heic", "image/heif", "image/avif",
        )
    }
}

internal data class WebUiToolImageFile(val file: File, val size: Long, val mimeType: String)
