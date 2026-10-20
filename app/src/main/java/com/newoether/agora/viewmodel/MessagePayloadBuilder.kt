package com.newoether.agora.viewmodel

import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.SelectedAttachment

internal fun SelectedAttachment.hasCanonicalReadyArtifact(): Boolean =
    importState == AttachmentImportState.READY &&
        !unavailable &&
        when (type) {
            "image" -> !localPath.isNullOrBlank()
            "video" -> !localPath.isNullOrBlank() &&
                processedFrames.orEmpty().any(String::isNotBlank)
            "pdf" -> !localPath.isNullOrBlank() &&
                preRenderedPaths.orEmpty().any(String::isNotBlank)
            "file" -> if (storage.isLocalSandbox) {
                !sandboxPath.isNullOrBlank()
            } else {
                !localPath.isNullOrBlank() && preparedText != null
            }
            else -> false
        }

/**
 * Serializes the immutable results produced by [AttachmentImportProcessor].
 *
 * This boundary performs no file IO or media transformation. Attachments without a complete
 * canonical READY artifact are omitted, so Send cannot fall back to picker URIs or repeat work.
 */
internal class MessagePayloadBuilder {
    data class MessagePayload(
        val allImages: List<String>,
        val attachmentMeta: AttachmentMeta?,
    )

    fun buildComposerPayload(
        attachments: List<SelectedAttachment>,
    ): MessagePayload {
        val allImages = mutableListOf<String>()
        val metaItems = mutableListOf<AttachmentItem>()

        attachments.forEach attachment@ { attachment ->
            if (!attachment.hasCanonicalReadyArtifact()) return@attachment

            when (attachment.type) {
                "image" -> {
                    val path = attachment.localPath
                        ?.takeIf(String::isNotBlank)
                        ?: return@attachment
                    val imageIndex = allImages.size
                    allImages += path
                    metaItems += AttachmentItem(
                        originalUri = path.asFileUri(),
                        type = "image",
                        fileName = attachment.fileName,
                        mimeType = attachment.mimeType,
                        imageIndex = imageIndex,
                        pixelWidth = attachment.pixelWidth,
                        pixelHeight = attachment.pixelHeight,
                        fileSize = attachment.fileSize,
                    )
                }

                "video" -> {
                    val sourcePath = attachment.localPath
                        ?.takeIf(String::isNotBlank)
                        ?: return@attachment
                    val frames = attachment.processedFrames
                        .orEmpty()
                        .filter(String::isNotBlank)
                    if (frames.isEmpty()) return@attachment
                    val imageIndex = allImages.size
                    allImages += frames
                    metaItems += AttachmentItem(
                        originalUri = sourcePath.asFileUri(),
                        type = "video",
                        fileName = attachment.fileName,
                        mimeType = attachment.mimeType,
                        imageIndex = imageIndex,
                        pageCount = frames.size,
                        pixelWidth = attachment.pixelWidth,
                        pixelHeight = attachment.pixelHeight,
                        fileSize = attachment.fileSize,
                    )
                }

                "pdf" -> {
                    val sourcePath = attachment.localPath
                        ?.takeIf(String::isNotBlank)
                        ?: return@attachment
                    val pages = attachment.preRenderedPaths
                        .orEmpty()
                        .filter(String::isNotBlank)
                    if (pages.isEmpty()) return@attachment
                    val imageIndex = allImages.size
                    allImages += pages
                    metaItems += AttachmentItem(
                        originalUri = sourcePath.asFileUri(),
                        type = "pdf",
                        fileName = attachment.fileName,
                        mimeType = attachment.mimeType ?: "application/pdf",
                        imageIndex = imageIndex,
                        pageCount = pages.size,
                        pixelWidth = attachment.pixelWidth,
                        pixelHeight = attachment.pixelHeight,
                        fileSize = attachment.fileSize,
                    )
                }

                "file" -> {
                    if (attachment.storage.isLocalSandbox) {
                        val sandboxPath = attachment.sandboxPath
                            ?.takeIf(String::isNotBlank)
                            ?: return@attachment
                        metaItems += AttachmentItem(
                            type = "file",
                            fileName = attachment.fileName,
                            mimeType = attachment.mimeType,
                            storage = attachment.storage.transferForSend(),
                            sourceTitle = attachment.sourceTitle,
                            sourceBookTitle = attachment.sourceBookTitle,
                            sourceAuthors = attachment.sourceAuthors,
                            sourceThumbnailPath = attachment.sourceThumbnailPath,
                            sandboxPath = sandboxPath,
                            fileSize = attachment.fileSize,
                        )
                    } else {
                        val sourcePath = attachment.localPath
                            ?.takeIf(String::isNotBlank)
                            ?: return@attachment
                        val text = attachment.preparedText ?: return@attachment
                        metaItems += AttachmentItem(
                            originalUri = sourcePath.asFileUri(),
                            type = "file",
                            fileName = attachment.fileName,
                            mimeType = attachment.mimeType,
                            textContent = text,
                            warning = attachment.warning,
                            notesDocumentId = attachment.notesDocumentId,
                            notesRevisionId = attachment.notesRevisionId,
                            notesThumbnailPath = attachment.notesThumbnailPath,
                            sourceTitle = attachment.sourceTitle,
                            sourceBookTitle = attachment.sourceBookTitle,
                            sourceAuthors = attachment.sourceAuthors,
                            sourceThumbnailPath = attachment.sourceThumbnailPath,
                            fileSize = attachment.fileSize,
                        )
                    }
                }
            }
        }

        return MessagePayload(
            allImages = allImages,
            attachmentMeta = metaItems
                .takeIf(List<*>::isNotEmpty)
                ?.let(::AttachmentMeta),
        )
    }

    private fun String.asFileUri(): String = "file://$this"
}
