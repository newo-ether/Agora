package com.newoether.agora.viewmodel

import android.content.Context
import android.net.Uri
import com.newoether.agora.api.util.imageArtifactOccurrences
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.util.ImageArtifactProcessor
import java.io.File
import java.io.IOException

/** Read-scoped compatibility for the actual files shared by UI and Provider context. */
internal class ImageArtifactHydration(context: Context) {
    private val context = context.applicationContext
    private val processor = ImageArtifactProcessor(this.context)
    private val measured = mutableMapOf<String, ImageArtifact>()

    fun hydrate(message: ChatMessage): ChatMessage {
        if (message.images.isEmpty() && message.segments.orEmpty().all { it.toolImages.isEmpty() } &&
            message.toolCall?.resultImages.isNullOrEmpty()
        ) return message
        // Resolve only the selected owner's references; readable files refresh historical measurements.
        val normalized = canonicalReferences(message)
        val occurrences = normalized.imageArtifactOccurrences()
        val images = normalized.images.mapIndexed { index, source -> resolve(source, occurrences[index]) }
        val metadata = normalized.attachmentMeta?.let { meta ->
            meta.copy(items = meta.items.map { item -> hydrateItem(item, normalized.images, images) })
        }
        val segments = normalized.segments?.map { segment ->
            if (segment.toolImages.isEmpty()) segment else segment.copy(
                toolImages = segment.toolImages.map { resolve(it.path, it) },
            )
        }
        val call = normalized.toolCall?.let { tool ->
            if (tool.resultImages.isEmpty()) tool else tool.copy(
                resultImages = tool.resultImages.map { resolve(it.path, it) },
            )
        }
        return normalized.copy(
            images = images.map(ImageArtifact::path),
            imageArtifacts = buildMap {
                images.forEach { if (get(it.path)?.unavailable != false) put(it.path, it) }
            },
            attachmentMeta = metadata,
            segments = segments,
            toolCall = call,
        )
    }

    private fun canonicalReferences(message: ChatMessage): ChatMessage {
        fun ImageArtifact.canonical() = copy(path = canonicalReference(path))
        val artifacts = buildMap<String, ImageArtifact> {
            message.imageArtifacts.forEach { (reference, recorded) ->
                val key = canonicalReference(reference)
                val artifact = recorded.canonical()
                val previous = get(key)
                if (previous == null || previous.unavailable && !artifact.unavailable) put(key, artifact)
            }
        }
        return message.copy(
            images = message.images.map(::canonicalReference),
            imageArtifacts = artifacts,
            attachmentMeta = message.attachmentMeta?.let { meta ->
                meta.copy(items = meta.items.map { item ->
                    if (item.storage.isLocalSandbox) item else item.copy(
                        imageArtifacts = item.imageArtifacts.map { it.canonical() },
                    )
                })
            },
            segments = message.segments?.map { segment ->
                segment.copy(toolImages = segment.toolImages.map { it.canonical() })
            },
            toolCall = message.toolCall?.let { tool ->
                tool.copy(resultImages = tool.resultImages.map { it.canonical() })
            },
        )
    }

    private fun canonicalReference(source: String): String = try {
        canonicalPath(source)
    } catch (_: IOException) {
        source
    } catch (_: IllegalArgumentException) {
        source
    } catch (_: SecurityException) {
        source
    }

    /** Legacy restored content URIs name the same private file; resolving them never copies bytes. */
    fun canonicalPath(source: String): String = when {
        source.startsWith("file:", ignoreCase = true) -> File(java.net.URI(source)).canonicalPath
        File(source).isAbsolute -> File(source).canonicalPath
        source.startsWith("content:", ignoreCase = true) -> {
            val uri = Uri.parse(source)
            if (uri.authority != "${context.packageName}.fileprovider" ||
                uri.pathSegments.firstOrNull() != "images" || uri.pathSegments.size < 2 ||
                uri.query != null || uri.fragment != null
            ) throw IOException("Image reference is not an owned private artifact")
            val root = File(context.filesDir, "images").canonicalFile
            val file = File(root, uri.pathSegments.drop(1).joinToString("/")).canonicalFile
            if (!file.toPath().startsWith(root.toPath()) || file == root) {
                throw IOException("Image reference escapes its private directory")
            }
            file.absolutePath
        }
        else -> throw IOException("Image reference is not a local artifact")
    }

    private fun resolve(source: String, recorded: ImageArtifact?): ImageArtifact {
        if (recorded?.unavailable == true || source.isBlank()) return unavailable(source, recorded)
        return try {
            val path = canonicalPath(source)
            if (recorded != null && canonicalPath(recorded.path) != path) {
                throw IOException("Image reference does not match its recorded artifact")
            }
            measured.getOrPut(path) { processor.inspect(path) }.copy(
                policyVersion = recorded?.policyVersion ?: 0,
            )
        } catch (_: IOException) {
            unavailable(source, recorded)
        } catch (_: IllegalArgumentException) {
            unavailable(source, recorded)
        } catch (_: SecurityException) {
            unavailable(source, recorded)
        }
    }

    private fun hydrateItem(
        item: AttachmentItem,
        sources: List<String>,
        images: List<ImageArtifact>,
    ): AttachmentItem {
        if (item.storage.isLocalSandbox || item.type !in setOf("image", "pdf", "video")) return item
        val start = item.imageIndex
        val artifacts = if (start != null && start in images.indices) {
            val count = if (item.type == "image") 1 else {
                item.pageCount ?: item.imageArtifacts.size.takeIf { it > 0 } ?: 1
            }
            val end = (start.toLong() + count.coerceAtLeast(0)).coerceAtMost(images.size.toLong()).toInt()
            (start until end).map { index ->
                val recorded = item.imageArtifacts.getOrNull(index - start)
                val resolved = if (recorded == null) images[index] else resolve(sources[index], recorded)
                if (images[index].unavailable) resolved.copy(unavailable = true) else resolved
            } + item.imageArtifacts.drop(end - start).map { resolve(it.path, it) }
        } else item.imageArtifacts.map { resolve(it.path, it) }
        val retained = if (item.unavailable && item.type == "image") {
            artifacts.map { it.copy(unavailable = true) }
        } else artifacts
        val single = retained.singleOrNull()
        return item.copy(
            imageArtifacts = retained,
            unavailable = item.unavailable || retained.any(ImageArtifact::unavailable),
            mimeType = if (item.type == "image") single?.mimeType ?: item.mimeType else item.mimeType,
            fileSize = if (item.type == "image") single?.sizeBytes ?: item.fileSize else item.fileSize,
            pixelWidth = single?.width,
            pixelHeight = single?.height,
        )
    }

    private fun unavailable(source: String, recorded: ImageArtifact?): ImageArtifact = recorded?.copy(
        path = source, unavailable = true,
    ) ?: ImageArtifact(
        path = source, mimeType = "application/octet-stream", sizeBytes = 0,
        unavailable = true,
    )
}
