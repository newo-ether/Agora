package com.newoether.agora.data

import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.SelectedAttachment
import kotlinx.serialization.json.Json

internal object NativeBackupMediaPolicy {
    private val json = Json { ignoreUnknownKeys = true }

    /** Sandbox payloads belong to the live runtime and are never copied into native backups. */
    fun exportableDraftAttachments(
        attachments: List<SelectedAttachment>,
    ): List<SelectedAttachment> = attachments.filterNot { it.storage.isLocalSandbox }

    fun rewriteDraftAttachmentsForExport(
        attachments: List<SelectedAttachment>,
        archiveEntryForSource: (String) -> String?,
        onMissingResource: () -> Unit = {},
    ): List<SelectedAttachment> = exportableDraftAttachments(attachments).map { attachment ->
        val fileName = attachment.fileName ?: listOfNotNull(
            attachment.localPath,
            attachment.uri.takeIf(String::isNotBlank),
        ).firstNotNullOfOrNull(::sourceFileName)
        if (attachment.unavailable) {
            onMissingResource()
            return@map attachment.asUnavailablePlaceholder(fileName)
        }
        val primarySource = listOfNotNull(
            attachment.localPath,
            attachment.uri.takeIf(String::isNotBlank),
        ).firstNotNullOfOrNull(archiveEntryForSource)
        if (primarySource == null) {
            onMissingResource()
            attachment.asUnavailablePlaceholder(fileName)
        } else {
            attachment.copy(
                uri = primarySource,
                localPath = primarySource,
                fileName = fileName,
                processedFrames = attachment.processedFrames
                    ?.mapNotNull(archiveEntryForSource)
                    ?.takeIf(List<String>::isNotEmpty),
                notesThumbnailPath = attachment.notesThumbnailPath?.let(archiveEntryForSource),
                sourceThumbnailPath = attachment.sourceThumbnailPath?.let(archiveEntryForSource),
                preRenderedPaths = attachment.preRenderedPaths
                    ?.mapNotNull(archiveEntryForSource)
                    ?.takeIf(List<String>::isNotEmpty),
                storage = AttachmentStorage.APP_PRIVATE,
                sandboxPath = null,
                unavailable = false,
            )
        }
    }

    fun restoreDraftAttachments(
        attachments: List<SelectedAttachment>,
        restoredPrimaryForArchiveEntry: (String) -> Pair<String, String>?,
        restoredPathForArchiveEntry: (String) -> String?,
    ): List<SelectedAttachment> = attachments.map { attachment ->
        if (attachment.unavailable) {
            return@map attachment.asUnavailablePlaceholder(attachment.fileName)
        }
        val primaryEntry = attachment.localPath
            ?.takeIf(String::isNotBlank)
            ?: attachment.uri.takeIf(String::isNotBlank)
        val primary = primaryEntry?.let(restoredPrimaryForArchiveEntry)
        if (primary == null) {
            attachment.asUnavailablePlaceholder(attachment.fileName ?: primaryEntry?.let(::sourceFileName))
        } else {
            attachment.copy(
                uri = primary.second,
                localPath = primary.first,
                processedFrames = attachment.processedFrames
                    ?.mapNotNull(restoredPathForArchiveEntry)
                    ?.takeIf(List<String>::isNotEmpty),
                notesThumbnailPath = attachment.notesThumbnailPath?.let(restoredPathForArchiveEntry),
                sourceThumbnailPath = attachment.sourceThumbnailPath?.let(restoredPathForArchiveEntry),
                preRenderedPaths = attachment.preRenderedPaths
                    ?.mapNotNull(restoredPathForArchiveEntry)
                    ?.takeIf(List<String>::isNotEmpty),
                storage = AttachmentStorage.APP_PRIVATE,
                sandboxPath = null,
                unavailable = false,
            )
        }
    }

    fun toolImagePaths(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<MessageSegment>>(raw)
                .flatMap { segment -> segment.toolImages.map { it.path } }
        }.getOrDefault(emptyList())
    }

    fun rewriteAttachmentMetaForExport(
        raw: String?,
        originalImageSources: List<String> = emptyList(),
        oldToNewImageIndex: Map<Int, Int>,
        archiveEntryForSource: (String) -> String?,
        onMissingResource: () -> Unit = {},
    ): String? {
        val decodedMeta = raw?.let {
            runCatching { json.decodeFromString<AttachmentMeta>(it) }.getOrNull()
        }
        val claimedImageIndices = decodedMeta?.items.orEmpty().flatMapTo(mutableSetOf()) { item ->
            val start = item.imageIndex ?: return@flatMapTo emptyList()
            val count = item.pageCount?.coerceAtLeast(1) ?: 1
            (start until start + count).toList()
        }
        val legacyImageItems = originalImageSources.mapIndexedNotNull { index, source ->
            if (index in claimedImageIndices) null else AttachmentItem(
                originalUri = source,
                type = "image",
                fileName = sourceFileName(source),
                imageIndex = index,
            )
        }
        val sourceItems = decodedMeta?.items.orEmpty() + legacyImageItems
        if (sourceItems.isEmpty()) return null
        val items = sourceItems
            .filterNot { it.storage.isLocalSandbox }
            .map { item ->
                val originalCount = item.pageCount?.coerceAtLeast(1) ?: 1
                val survivingIndices = item.imageIndex
                    ?.let { start ->
                        (start until start + originalCount).mapNotNull(oldToNewImageIndex::get)
                    }
                    .orEmpty()
                val archivedOriginalUri = item.originalUri
                    ?.takeIf { !item.unavailable && (item.type == "video" || item.mimeType == "application/vnd.kalamos.notes-document+zip") }
                    ?.let(archiveEntryForSource)
                val unavailable = when {
                    item.unavailable -> true
                    item.type == "video" || item.mimeType == "application/vnd.kalamos.notes-document+zip" -> archivedOriginalUri == null
                    item.type == "file" -> item.textContent == null
                    item.type == "image" || item.type == "pdf" -> survivingIndices.isEmpty()
                    else -> false
                }
                if (unavailable) onMissingResource()
                item.copy(
                    // Videos and editable Notes bundles keep their copied original bytes. Other types
                    // render from archived message media/text and must not expose a device URI.
                    originalUri = archivedOriginalUri,
                    notesThumbnailPath = item.notesThumbnailPath?.let(archiveEntryForSource),
                    sourceThumbnailPath = item.sourceThumbnailPath?.let(archiveEntryForSource),
                    fileName = item.fileName ?: item.originalUri?.let(::sourceFileName),
                    imageIndex = when {
                        unavailable || item.imageIndex == null -> null
                        else -> survivingIndices.firstOrNull()
                    },
                    pageCount = when {
                        unavailable || item.pageCount == null -> null
                        else -> survivingIndices.size
                    },
                    textContent = item.textContent.takeUnless { unavailable },
                    unavailable = unavailable,
                )
            }
        return json.encodeToString(AttachmentMeta(items))
    }

    fun rewriteToolImagePathsForExport(
        raw: String?,
        archiveEntryForSource: (String) -> String?,
    ): String? {
        if (raw.isNullOrBlank()) return raw
        val segments = runCatching {
            json.decodeFromString<List<MessageSegment>>(raw)
        }.getOrNull() ?: return raw
        return json.encodeToString(
            segments.map { segment ->
                segment.copy(
                    toolImages = segment.toolImages.mapNotNull { image ->
                        archiveEntryForSource(image.path)?.let { archivedPath ->
                            image.copy(path = archivedPath)
                        }
                    },
                )
            },
        )
    }

    fun restoreAttachmentMeta(
        raw: String?,
        archiveVersion: Int,
        oldToNewImageIndex: Map<Int, Int> = emptyMap(),
        legacyVideoUris: Map<Int, String>,
        restoredUriForArchiveEntry: (String) -> String?,
    ): String? {
        if (raw.isNullOrBlank()) return raw
        val meta = runCatching {
            json.decodeFromString<AttachmentMeta>(raw)
        }.getOrNull() ?: return null
        return json.encodeToString(
            AttachmentMeta(
                meta.items.map { item ->
                    val originalCount = item.pageCount?.coerceAtLeast(1) ?: 1
                    val survivingIndices = item.imageIndex
                        ?.let { start ->
                            (start until start + originalCount).mapNotNull(oldToNewImageIndex::get)
                        }
                        .orEmpty()
                    val restoredOriginalUri = when {
                        item.unavailable -> null
                        archiveVersion >= 4 -> item.originalUri
                            ?.let(restoredUriForArchiveEntry)
                        item.type == "video" -> legacyVideoUris[item.imageIndex ?: 0]
                        else -> null
                    }
                    val unavailable = when {
                        item.unavailable -> true
                        item.type == "video" || item.mimeType == "application/vnd.kalamos.notes-document+zip" -> restoredOriginalUri == null
                        item.type == "file" -> item.textContent == null
                        item.type == "image" || item.type == "pdf" -> survivingIndices.isEmpty()
                        else -> false
                    }
                    item.copy(
                        originalUri = restoredOriginalUri,
                        notesThumbnailPath = item.notesThumbnailPath?.let(restoredUriForArchiveEntry),
                        sourceThumbnailPath = item.sourceThumbnailPath?.let(restoredUriForArchiveEntry),
                        imageIndex = when {
                            unavailable || item.imageIndex == null -> null
                            else -> survivingIndices.firstOrNull()
                        },
                        pageCount = when {
                            unavailable || item.pageCount == null -> null
                            else -> survivingIndices.size
                        },
                        textContent = item.textContent.takeUnless { unavailable },
                        storage = AttachmentStorage.APP_PRIVATE,
                        sandboxPath = null,
                        unavailable = unavailable,
                    )
                },
            ),
        )
    }

    fun restoreToolImagePaths(
        raw: String?,
        archiveVersion: Int,
        restoredPathForArchiveEntry: (String) -> String?,
    ): String? {
        if (raw.isNullOrBlank()) return raw
        val segments = runCatching {
            json.decodeFromString<List<MessageSegment>>(raw)
        }.getOrNull() ?: return raw
        return json.encodeToString(
            segments.map { segment ->
                segment.copy(
                    toolImages = segment.toolImages.mapNotNull { image ->
                        if (archiveVersion < 4) {
                            // v1-v3 stored only a device-absolute path. Dropping an unusable
                            // thumbnail is safer than retaining a misleading foreign path.
                            null
                        } else {
                            restoredPathForArchiveEntry(image.path)?.let { restoredPath ->
                                image.copy(path = restoredPath)
                            }
                        }
                    },
                )
            },
        )
    }

    private fun SelectedAttachment.asUnavailablePlaceholder(
        retainedFileName: String?,
    ): SelectedAttachment = copy(
        uri = "",
        fileName = retainedFileName,
        processedFrames = null,
        selectedPages = null,
        preRenderedPaths = null,
        notesThumbnailPath = null,
        sourceThumbnailPath = null,
        localPath = null,
        storage = AttachmentStorage.APP_PRIVATE,
        sandboxPath = null,
        unavailable = true,
    )

    private fun sourceFileName(source: String): String? {
        val clean = source.substringBefore('?').substringBefore('#').trimEnd('/', '\\')
        return clean.substringAfterLast('/').substringAfterLast('\\')
            .takeIf(String::isNotBlank)
    }
}
