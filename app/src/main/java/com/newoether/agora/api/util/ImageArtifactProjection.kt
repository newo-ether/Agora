package com.newoether.agora.api.util

import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact

/** One descriptor per UI slot; indexed records take precedence over the lossy path lookup. */
internal fun ChatMessage.imageArtifactOccurrences(): List<ImageArtifact?> {
    val attachments = attachmentMeta?.items.orEmpty().filter {
        !it.storage.isLocalSandbox && it.type in setOf("image", "pdf", "video")
    }
    val toolArtifacts = segments.orEmpty().flatMap { it.toolImages }
        .ifEmpty { toolCall?.resultImages.orEmpty() }
    return images.mapIndexed { index, reference ->
        val explicit = imageArtifacts[reference].takeIf { reference.isNotBlank() }
        fun ImageArtifact.matches(): Boolean = path == reference || (
            reference.isImageUriReference() && explicit?.path == path
        )
        val indexed = mutableListOf<ImageArtifact>()
        val linked = mutableListOf<ImageArtifact>()
        var unavailableItem: AttachmentItem? = null
        attachments.forEach { item ->
            val start = item.imageIndex
            val count = if (item.type == "image") 1 else {
                item.pageCount ?: item.imageArtifacts.size.takeIf { it > 0 } ?: 1
            }
            val offset = if (start != null && index >= start &&
                index.toLong() < start.toLong() + count.coerceAtLeast(0)
            ) index - start else null
            val recorded = offset?.let { item.imageArtifacts.getOrNull(it) }
            if (recorded != null) indexed += recorded
            val linkedArtifacts = item.imageArtifacts.filterIndexed { artifactOffset, artifact ->
                val slot = start?.toLong()?.plus(artifactOffset)
                reference.isNotBlank() && artifact.matches() &&
                    (slot == null || artifactOffset >= count || slot < 0L ||
                        slot >= images.size.toLong() || slot == index.toLong())
            }
            linked += linkedArtifacts
            // A multi-page item's aggregate error must not hide its remaining readable pages.
            if (item.unavailable && (offset != null || linkedArtifacts.isNotEmpty()) &&
                (item.type == "image" || recorded == null && linkedArtifacts.isEmpty())
            ) unavailableItem = item
        }
        val tool = toolArtifacts.getOrNull(index)?.takeIf { toolArtifacts.size == images.size }
        val selected = indexed.firstOrNull { it.matches() } ?: indexed.firstOrNull()
            ?: tool ?: explicit?.takeIf { it.matches() }
            ?: linked.firstOrNull() ?: toolArtifacts.firstOrNull { reference.isNotBlank() && it.matches() }
            ?: explicit
            ?: attachments.asSequence().flatMap { it.imageArtifacts.asSequence() }.firstOrNull {
                reference.isNotBlank() && !it.unavailable && it.matches()
            }
        if (selected == null) {
            val item = unavailableItem ?: return@mapIndexed null
            return@mapIndexed ImageArtifact(
                path = reference, mimeType = item.mimeType ?: "application/octet-stream",
                sizeBytes = 0, width = item.pixelWidth, height = item.pixelHeight, unavailable = true,
            )
        }
        val rejected = reference.isBlank() || unavailableItem != null || selected.unavailable ||
            !selected.matches() || indexed.any { !it.matches() || it.unavailable }
        if (rejected) selected.copy(path = reference, unavailable = true) else selected
    }
}

/** A path map is only a convenience; it must not replace a readable sibling with a broken slot. */
internal fun ChatMessage.artifactsForImages(
    imagePaths: List<String> = images,
): Map<String, ImageArtifact> {
    val requested = imagePaths.toSet()
    return buildMap {
        images.zip(imageArtifactOccurrences()).forEach { (reference, artifact) ->
            if (artifact != null && (reference in requested || artifact.path in requested)) {
                if (reference.isNotBlank() && get(reference)?.unavailable != false) put(reference, artifact)
                if (artifact.path.isNotBlank() && get(artifact.path)?.unavailable != false) {
                    put(artifact.path, artifact)
                }
            }
        }
    }
}

internal data class ProjectedImageOccurrence(val path: String, val artifact: ImageArtifact?)

internal fun ChatMessage.projectedImageOccurrences(): List<ProjectedImageOccurrence> =
    images.zip(imageArtifactOccurrences()) { path, artifact ->
        ProjectedImageOccurrence(artifact?.path ?: path, artifact)
    }

private fun String.isImageUriReference(): Boolean =
    startsWith("file:", ignoreCase = true) || startsWith("content:", ignoreCase = true)

/** Dispatch omits broken slots and deduplicates only matching paths and available measured facts. */
internal fun List<ProjectedImageOccurrence>.distinctAvailableImages(): List<ProjectedImageOccurrence> {
    val seen = mutableSetOf<ProjectedImageOccurrence>()
    return filter { occurrence ->
        occurrence.path.isNotBlank() && occurrence.artifact?.unavailable != true && seen.add(
            occurrence.copy(artifact = occurrence.artifact?.copy(path = occurrence.path, policyVersion = 0)),
        )
    }
}

/** Remaps ordered descriptors without changing the durable attachment records. */
internal fun ChatMessage.withProjectedImages(
    occurrences: List<ProjectedImageOccurrence>,
): ChatMessage {
    val metadata = attachmentMeta ?: AttachmentMeta()
    val canonical = occurrences.map { occurrence ->
        occurrence.copy(path = occurrence.artifact?.path ?: occurrence.path)
    }
    val retained = metadata.items.filter {
        it.storage.isLocalSandbox || it.type !in setOf("image", "pdf", "video")
    }
    val projected = copy(
        images = canonical.map { it.path }, imageArtifacts = emptyMap(),
        attachmentMeta = metadata.copy(items = retained + canonical.mapIndexed { index, occurrence ->
            AttachmentItem(
                type = "image", imageIndex = index,
                mimeType = occurrence.artifact?.mimeType,
                fileSize = occurrence.artifact?.sizeBytes,
                pixelWidth = occurrence.artifact?.width, pixelHeight = occurrence.artifact?.height,
                imageArtifacts = listOfNotNull(occurrence.artifact),
                unavailable = occurrence.path.isBlank() || occurrence.artifact?.unavailable == true,
            )
        }),
    )
    return projected.copy(imageArtifacts = projected.artifactsForImages())
}
