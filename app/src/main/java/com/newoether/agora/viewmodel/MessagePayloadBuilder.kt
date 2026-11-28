package com.newoether.agora.viewmodel

import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.previewImagePath

internal fun SelectedAttachment.hasCanonicalReadyArtifact(): Boolean =
    importState == AttachmentImportState.READY &&
        (!storage.isLocalSandbox || !sandboxPath.isNullOrBlank()) &&
        when (type) {
            "image" -> !previewImagePath.isNullOrBlank()
            "video" -> !localPath.isNullOrBlank() &&
                (storage.isLocalSandbox || hasPreparedImageSlots(processedFrames.orEmpty()))
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

/** Serializes completed import results for durable presentation, without IO or transformation. */
internal class MessagePayloadBuilder {
    data class MessagePayload(
        val allImages: List<String>,
        val attachmentMeta: AttachmentMeta?,
    )

    fun buildComposerPayload(attachments: List<SelectedAttachment>): MessagePayload {
        val allImages = mutableListOf<String>()
        val metaItems = mutableListOf<AttachmentItem>()
        attachments.forEach attachment@ { attachment ->
            if (!attachment.hasCanonicalReadyArtifact()) return@attachment
            val sourcePath = attachment.localPath?.takeIf(String::isNotBlank) ?: return@attachment
            val paths = when (attachment.type) {
                "image" -> listOfNotNull(attachment.previewImagePath)
                "video" -> attachment.processedFrames.orEmpty()
                "pdf" -> attachment.preRenderedPaths.orEmpty()
                "file" -> emptyList()
                else -> return@attachment
            }
            val imageIndex = allImages.size.takeIf { paths.isNotEmpty() }
            allImages += paths
            metaItems += AttachmentItem(
                originalUri = if (attachment.type == "file" && attachment.storage.isLocalSandbox) null
                    else "file://$sourcePath",
                type = attachment.type,
                fileName = attachment.fileName,
                mimeType = attachment.mimeType ?: "application/pdf".takeIf { attachment.type == "pdf" },
                imageIndex = imageIndex,
                pageCount = paths.size.takeIf {
                    (attachment.type == "pdf" || attachment.type == "video") && paths.isNotEmpty()
                },
                pixelWidth = attachment.pixelWidth,
                pixelHeight = attachment.pixelHeight,
                fileSize = attachment.fileSize,
                textContent = attachment.preparedText.takeIf { attachment.type == "file" },
                storage = attachment.storage.transferForSend(),
                sandboxPath = attachment.sandboxPath,
                imageArtifacts = attachment.artifactsFor(paths),
                unavailable = attachment.unavailable,
            )
        }
        return MessagePayload(allImages, metaItems.takeIf(List<*>::isNotEmpty)?.let(::AttachmentMeta))
    }
}
