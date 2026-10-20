package com.newoether.agora.data.repository

import com.newoether.agora.data.local.MessageAttachmentReference
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.SelectedAttachment
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal fun String?.decodeSelectedAttachments(): List<SelectedAttachment>? =
    this?.let { raw ->
        runCatching { Json.decodeFromString<List<SelectedAttachment>>(raw) }.getOrNull()
    }

internal fun List<SelectedAttachment>.removedReclaimablePaths(
    replacement: List<SelectedAttachment>?,
): Set<String> {
    val retainedPaths = replacement.orEmpty().reclaimablePaths()
    return reclaimablePaths() - retainedPaths
}

internal fun List<SelectedAttachment>.appPrivatePaths(): Set<String> =
    filter { it.storage == AttachmentStorage.APP_PRIVATE }.reclaimablePaths()

internal fun List<SelectedAttachment>.reclaimablePaths(): Set<String> =
    asSequence()
        .filter { it.storage.reclaimWhenAbandoned }
        .flatMap { attachment ->
            sequence {
                attachment.localPath?.let { yield(normalizeAttachmentPath(it)) }
                attachment.notesThumbnailPath?.let { yield(normalizeAttachmentPath(it)) }
                attachment.sourceThumbnailPath?.let { yield(normalizeAttachmentPath(it)) }
                attachment.processedFrames.orEmpty().forEach {
                    yield(normalizeAttachmentPath(it))
                }
                attachment.preRenderedPaths.orEmpty().forEach {
                    yield(normalizeAttachmentPath(it))
                }
            }
        }
        .toSet()

internal fun MessageEntity.toAttachmentReference() = MessageAttachmentReference(
    id = id,
    images = images,
    attachmentMeta = attachmentMeta,
)

internal fun List<MessageAttachmentReference>.messageReclaimablePaths(): Set<String> =
    flatMapTo(linkedSetOf()) { reference ->
        attachmentFilePaths(reference.images, reference.attachmentMeta.decodeAttachmentMeta())
    }

internal fun String?.decodeAttachmentMeta(): AttachmentMeta? =
    this?.let { raw -> runCatching { Json.decodeFromString<AttachmentMeta>(raw) }.getOrNull() }

internal fun attachmentFilePaths(
    images: List<String>,
    meta: AttachmentMeta?,
): List<String> {
    val retainedImageIndices = meta?.items.orEmpty()
        .asSequence()
        .filterNot { it.storage.reclaimWhenAbandoned }
        .flatMap { item ->
            val start = item.imageIndex ?: return@flatMap emptySequence()
            val count = (item.pageCount ?: 1).coerceAtLeast(0)
            (start until start + count).asSequence()
        }
        .toSet()
    return buildList {
        images.forEachIndexed { index, path ->
            if (index !in retainedImageIndices) add(normalizeAttachmentPath(path))
        }
        meta?.items.orEmpty().filter { it.storage.reclaimWhenAbandoned }
            .flatMap { listOfNotNull(it.notesThumbnailPath, it.sourceThumbnailPath) }.forEach { add(normalizeAttachmentPath(it)) }
        meta?.items.orEmpty()
            .asSequence()
            .filter { it.storage.reclaimWhenAbandoned }
            .mapNotNull { item ->
                item.originalUri
                    ?.takeIf { it.startsWith("file://") }
                    ?.let(::normalizeAttachmentPath)
            }
            .forEach(::add)
    }
}

internal fun normalizeAttachmentPath(path: String): String {
    val raw = path.removePrefix("file://")
    return runCatching { java.io.File(raw).canonicalPath }
        .getOrElse { java.io.File(raw).absolutePath }
}
