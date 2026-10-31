package com.newoether.agora.data

import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.ImageArtifact
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
        val primarySource = listOfNotNull(
            attachment.localPath?.takeIf(String::isNotBlank),
            attachment.uri.takeIf(String::isNotBlank),
        ).firstNotNullOfOrNull(archiveEntryForSource)
        if (primarySource == null) onMissingResource()
        attachment.rewriteDraftMedia(
            primary = primarySource?.let { it to it },
            rewritePath = archiveEntryForSource,
            onMissingResource = onMissingResource,
        ).copy(fileName = fileName)
    }

    fun restoreDraftAttachments(
        attachments: List<SelectedAttachment>,
        restoredPrimaryForArchiveEntry: (String) -> Pair<String, String>?,
        restoredPathForArchiveEntry: (String) -> String?,
    ): List<SelectedAttachment> = attachments.map { attachment ->
        val primaryEntry = attachment.localPath
            ?.takeIf(String::isNotBlank)
            ?: attachment.uri.takeIf(String::isNotBlank)
        attachment.rewriteDraftMedia(
            primary = primaryEntry?.let(restoredPrimaryForArchiveEntry),
            rewritePath = restoredPathForArchiveEntry,
        ).copy(fileName = attachment.fileName ?: primaryEntry?.let(::sourceFileName))
    }

    fun toolImagePaths(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<MessageSegment>>(raw)
                .flatMap { segment ->
                    segment.toolImages.filterNot { it.unavailable }.mapNotNull { image ->
                        image.path.takeIf(String::isNotBlank)
                    }
                }
        }.getOrDefault(emptyList())
    }

    fun rewriteAttachmentMetaForExport(
        raw: String?,
        originalImageSources: List<String> = emptyList(),
        oldToNewImageIndex: Map<Int, Int>,
        archiveEntryForSource: (String) -> String?,
        onMissingResource: () -> Unit = {},
    ): String? {
        val indexedSources = originalImageSources.withIndex().associate { it.index to it.value }
        val sourceItems = attachmentItems(raw, indexedSources)
        if (sourceItems.isEmpty()) return null
        val items = sourceItems
            .filterNot { it.storage.isLocalSandbox }
            .map { item ->
                val artifacts = item.rewriteMessageArtifacts(
                    oldToNewImageIndex, indexedSources, archiveEntryForSource,
                )
                val archivedOriginalUri = item.originalUri
                    ?.takeIf { !item.unavailable && item.type == "video" }
                    ?.let(archiveEntryForSource)
                val unavailable = when {
                    item.unavailable -> true
                    item.type == "video" -> archivedOriginalUri == null
                    item.type == "file" -> item.textContent == null
                    item.type == "image" || item.type == "pdf" ->
                        artifacts.isEmpty() || artifacts.any { it.unavailable }
                    else -> false
                }
                if (unavailable) onMissingResource()
                item.copy(
                    // Only copied video payloads keep a structural original reference. Other types
                    // render from archived message media/text and must not expose a device URI.
                    originalUri = archivedOriginalUri,
                    fileName = item.fileName ?: item.originalUri?.let(::sourceFileName),
                    imageIndex = item.imageIndex?.let(oldToNewImageIndex::get),
                    pageCount = item.pageCount,
                    imageArtifacts = artifacts,
                    textContent = item.textContent.takeUnless { unavailable },
                    unavailable = unavailable,
                )
            }
        return json.encodeToString(AttachmentMeta(items))
    }

    fun rewriteToolImagePathsForExport(
        raw: String?,
        archiveEntryForSource: (String) -> String?,
    ): String? = rewriteToolImagePathsForExport(raw, {}, archiveEntryForSource)

    fun rewriteToolImagePathsForExport(
        raw: String?,
        onMissingResource: () -> Unit,
        archiveEntryForSource: (String) -> String?,
    ): String? {
        if (raw.isNullOrBlank()) return raw
        val segments = json.decodeFromString<List<MessageSegment>>(raw)
        return json.encodeToString(
            segments.map { segment ->
                segment.copy(
                    toolImages = segment.toolImages.map { image ->
                        val archivedPath = image.path.takeIf { !image.unavailable && it.isNotBlank() }
                            ?.let(archiveEntryForSource)
                        if (archivedPath == null) {
                            onMissingResource()
                            image.asUnavailablePlaceholder()
                        } else {
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
        restoredPathForArchiveEntry: (String) -> String? = { null },
        originalImageSources: List<String> = emptyList(),
        legacyImagePaths: Map<Int, String> = emptyMap(),
    ): String? {
        val indexedSources = if (archiveVersion < 4) legacyImagePaths
            else originalImageSources.withIndex().associate { it.index to it.value }
        val sourceItems = attachmentItems(raw, indexedSources)
        if (sourceItems.isEmpty()) return null
        return json.encodeToString(
            AttachmentMeta(
                sourceItems.map { item ->
                    val artifacts = item.rewriteMessageArtifacts(
                        oldToNewImageIndex, indexedSources, restoredPathForArchiveEntry,
                        legacyMappedPaths = archiveVersion < 4,
                    )
                    val restoredOriginalUri = when {
                        item.unavailable -> null
                        archiveVersion >= 4 -> item.originalUri
                            ?.let(restoredUriForArchiveEntry)
                        item.type == "video" -> legacyVideoUris[item.imageIndex ?: 0]
                        else -> null
                    }
                    val unavailable = when {
                        item.unavailable -> true
                        item.type == "video" -> restoredOriginalUri == null
                        item.type == "file" -> item.textContent == null
                        item.type == "image" || item.type == "pdf" ->
                            artifacts.isEmpty() || artifacts.any { it.unavailable }
                        else -> false
                    }
                    item.copy(
                        originalUri = restoredOriginalUri,
                        fileName = item.fileName ?: item.originalUri?.let(::sourceFileName),
                        imageIndex = item.imageIndex?.let(oldToNewImageIndex::get),
                        pageCount = item.pageCount,
                        imageArtifacts = artifacts,
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
        val segments = json.decodeFromString<List<MessageSegment>>(raw)
        return json.encodeToString(
            segments.map { segment ->
                segment.copy(
                    toolImages = segment.toolImages.map { image ->
                        val restoredPath = image.path.takeIf {
                            archiveVersion >= 4 && !image.unavailable && it.isNotBlank()
                        }?.let(restoredPathForArchiveEntry)
                        if (restoredPath == null) image.asUnavailablePlaceholder()
                        else image.copy(path = restoredPath)
                    },
                )
            },
        )
    }

    private fun attachmentItems(raw: String?, originalImageSources: Map<Int, String>): List<AttachmentItem> {
        val decodedMeta = raw?.let {
            runCatching { json.decodeFromString<AttachmentMeta>(it) }.getOrNull()
        }
        val claimedImageIndices = decodedMeta?.items.orEmpty().flatMapTo(mutableSetOf()) { item ->
            val start = item.imageIndex ?: return@flatMapTo emptyList()
            val count = item.pageCount?.coerceAtLeast(1) ?: 1
            (start until start + count).toList()
        }
        return decodedMeta?.items.orEmpty() + originalImageSources.mapNotNull { (index, source) ->
            if (index in claimedImageIndices) null else AttachmentItem(
                originalUri = source,
                type = "image",
                fileName = sourceFileName(source),
                imageIndex = index,
            )
        }
    }

    /** Every page/frame keeps its offset, including missing slots and repeated paths. */
    private fun AttachmentItem.rewriteMessageArtifacts(
        oldToNewImageIndex: Map<Int, Int>,
        originalImageSources: Map<Int, String>,
        rewritePath: (String) -> String?,
        legacyMappedPaths: Boolean = false,
    ): List<ImageArtifact> {
        val start = imageIndex
        val count = maxOf(imageArtifacts.size, if (start == null) 0 else pageCount?.coerceAtLeast(1) ?: 1)
        return List(count) { offset ->
            val artifact = imageArtifacts.getOrNull(offset) ?: unmeasuredArtifact(
                mimeType, pixelWidth.takeIf { count == 1 }, pixelHeight.takeIf { count == 1 },
            )
            val index = start?.plus(offset)
            val source = index?.let(originalImageSources::get) ?: artifact.path
            val target = if (index != null && index !in oldToNewImageIndex) null
                else if (legacyMappedPaths) index?.let(originalImageSources::get)?.takeIf(String::isNotBlank)
                else source.takeIf(String::isNotBlank)?.let(rewritePath)
            if (artifact.unavailable || target.isNullOrBlank()) artifact.asUnavailablePlaceholder()
            else artifact.copy(path = target)
        }
    }

    private fun SelectedAttachment.rewriteDraftMedia(
        primary: Pair<String, String>?,
        rewritePath: (String) -> String?,
        onMissingResource: () -> Unit = {},
    ): SelectedAttachment {
        fun rewrite(paths: List<String>?): List<String>? = paths?.mapIndexed { index, source ->
            source.takeIf { it.isNotBlank() && imageArtifacts.getOrNull(index)?.unavailable != true }
                ?.let(rewritePath).orEmpty().also {
                    if (it.isBlank()) onMissingResource()
                }
        }
        val frames = rewrite(processedFrames)
        val pages = rewrite(preRenderedPaths)
        val targets = when (type) {
            "image" -> listOf(primary?.first.orEmpty())
            "video" -> frames.orEmpty()
            "pdf" -> pages.orEmpty()
            else -> emptyList()
        }
        val artifacts = List(maxOf(targets.size, imageArtifacts.size)) { index ->
            val artifact = imageArtifacts.getOrNull(index) ?: unmeasuredArtifact(
                mimeType, pixelWidth.takeIf { targets.size == 1 }, pixelHeight.takeIf { targets.size == 1 },
            )
            val target = targets.getOrNull(index)
            if (artifact.unavailable || target.isNullOrBlank()) artifact.asUnavailablePlaceholder()
            else artifact.copy(path = target)
        }
        return copy(
            uri = primary?.second.orEmpty(),
            localPath = primary?.first,
            processedFrames = frames,
            preRenderedPaths = pages,
            imageArtifacts = artifacts,
            storage = AttachmentStorage.APP_PRIVATE,
            sandboxPath = null,
            unavailable = unavailable || primary == null ||
                (type in setOf("image", "pdf", "video") && artifacts.any { it.unavailable }),
        )
    }

    private fun unmeasuredArtifact(mimeType: String?, width: Int?, height: Int?) = ImageArtifact(
        path = "", mimeType = mimeType?.takeIf { it.startsWith("image/") } ?: "application/octet-stream",
        sizeBytes = 0, width = width, height = height,
    )

    private fun sourceFileName(source: String): String? {
        val clean = source.substringBefore('?').substringBefore('#').trimEnd('/', '\\')
        return clean.substringAfterLast('/').substringAfterLast('\\')
            .takeIf(String::isNotBlank)
    }
}

/** Unavailable images keep their occurrence and known bounds, never an old device reference. */
internal fun ImageArtifact.asUnavailablePlaceholder(): ImageArtifact = copy(
    path = "",
    sizeBytes = 0,
    unavailable = true,
)
