package com.newoether.agora.viewmodel

import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.SelectedAttachment

internal fun SelectedAttachment.hasCanonicalReadyArtifact(): Boolean =
    importState == AttachmentImportState.READY &&
        when (type) {
            "image" -> !localPath.isNullOrBlank()
            "video" -> !localPath.isNullOrBlank() &&
                hasPreparedImageSlots(processedFrames.orEmpty())
            "pdf" -> !localPath.isNullOrBlank() &&
                hasPreparedImageSlots(preRenderedPaths.orEmpty())
            "file" -> if (unavailable) false else if (storage.isLocalSandbox) {
                !sandboxPath.isNullOrBlank()
            } else {
                !localPath.isNullOrBlank() && preparedText != null
            }
            else -> false
        }

private fun SelectedAttachment.hasPreparedImageSlots(paths: List<String>): Boolean =
    paths.isNotEmpty() && (paths.any(String::isNotBlank) || imageArtifacts.size == paths.size)

private fun SelectedAttachment.artifactsFor(paths: List<String>): List<ImageArtifact> =
    if (imageArtifacts.isEmpty() && paths.all(String::isNotBlank) && !unavailable) emptyList()
    else paths.mapIndexed { index, path ->
        val recorded = imageArtifacts.getOrNull(index)?.takeIf { it.path == path }
            ?: imageArtifacts.firstOrNull { it.path == path }
        when {
            type == "image" && unavailable -> recorded?.copy(unavailable = true)
                ?: ImageArtifact(path, mimeType ?: "application/octet-stream", 0L,
                    width = pixelWidth, height = pixelHeight, unavailable = true)
            recorded != null -> recorded
            else -> ImageArtifact(path, "application/octet-stream", 0L, unavailable = path.isBlank())
        }
    }

/**
 * Serializes the immutable results produced by [AttachmentImportProcessor].
 *
 * This boundary performs no file IO or media transformation. READY media retains its prepared
 * UI slots, including broken slots; dispatch omits unreadable occurrences after owner-scoped
 * hydration. Picker URIs and unfinished imports never become request input.
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
                        imageArtifacts = attachment.artifactsFor(listOf(path)),
                        unavailable = attachment.unavailable,
                    )
                }

                "video" -> {
                    val sourcePath = attachment.localPath
                        ?.takeIf(String::isNotBlank)
                        ?: return@attachment
                    val frames = attachment.processedFrames
                        .orEmpty()
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
                        imageArtifacts = attachment.artifactsFor(frames),
                        unavailable = attachment.unavailable,
                    )
                }

                "pdf" -> {
                    val sourcePath = attachment.localPath
                        ?.takeIf(String::isNotBlank)
                        ?: return@attachment
                    val pages = attachment.preRenderedPaths
                        .orEmpty()
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
                        imageArtifacts = attachment.artifactsFor(pages),
                        unavailable = attachment.unavailable,
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
