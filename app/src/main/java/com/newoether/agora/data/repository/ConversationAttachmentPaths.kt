package com.newoether.agora.data.repository

import com.newoether.agora.data.local.MessageAttachmentReference
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.AttachmentFiles
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
    flatMapTo(linkedSetOf()) { attachment ->
        AttachmentFiles.reclaimablePaths(attachment).map(::normalizeAttachmentPath)
    }

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
    val runtimeOriginals = meta?.items.orEmpty()
        .filter { !it.storage.reclaimWhenAbandoned }
        .mapNotNull { it.originalUri?.let(::normalizeAttachmentPath) }.toSet()
    fun reclaimableSnapshot(path: String): Boolean =
        !AttachmentFiles.isSandboxAttachmentPath(path) && normalizeAttachmentPath(path) !in runtimeOriginals
    return buildList {
        images.filter(::reclaimableSnapshot).forEach { add(normalizeAttachmentPath(it)) }
        meta?.items.orEmpty()
            .asSequence()
            .filter { it.storage.reclaimWhenAbandoned }
            .mapNotNull { item ->
                item.originalUri
                    ?.takeIf { it.startsWith("file://") }
                    ?.let(::normalizeAttachmentPath)
            }
            .forEach(::add)
        meta?.items.orEmpty().asSequence()
            .flatMap { it.imageArtifacts.asSequence() }
            .filterNot { it.unavailable }
            .filter { reclaimableSnapshot(it.path) }
            .map { normalizeAttachmentPath(it.path) }
            .forEach(::add)
    }.filter(String::isNotBlank)
}

internal fun normalizeAttachmentPath(path: String): String {
    if (path.isBlank()) return ""
    val raw = path.removePrefix("file://")
    if (raw.isBlank()) return ""
    return runCatching { java.io.File(raw).canonicalPath }
        .getOrElse { java.io.File(raw).absolutePath }
}
