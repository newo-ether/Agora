package com.newoether.agora.data

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.util.AttachmentFiles
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Owns attachment copies created while cloning persisted messages.
 *
 * Repeated local references inside one owner resolve to one file. Different owners receive
 * independent copies. Commit after the graph commits; rollback removes uncommitted copies.
 */
internal class MessageAttachmentCloneSession(
    private val destinationDir: File,
) {
    private val copiedByOwnerAndSource = mutableMapOf<Pair<String, String>, String>()
    private val createdFiles = mutableListOf<File>()
    private var committed = false

    fun cloneMessage(
        message: MessageEntity,
        ownerKey: String = message.id,
    ): MessageEntity {
        val clonePath: (String) -> String = { path -> cloneBackingPath(ownerKey, path) }
        return message.copy(
            images = message.images.map(clonePath),
            attachmentMeta = message.attachmentMeta?.let { cloneAttachmentMeta(it, clonePath) },
            toolCallJson = message.toolCallJson?.takeIf(String::isNotBlank)?.let { raw ->
                cloneJson.encodeToString(
                    cloneJson.decodeFromString<List<MessageSegment>>(raw).map { segment ->
                        segment.copy(toolImages = segment.toolImages.map { it.cloneArtifact(clonePath) })
                    },
                )
            } ?: message.toolCallJson,
        )
    }

    fun cloneBackingPath(ownerKey: String, path: String): String {
        check(!committed) { "Attachment clone session is already committed" }
        if (path.isBlank()) return ""
        val source = localCloneSource(path) ?: ownedImageRelativePath(path)?.let { relative ->
            val root = File(destinationDir.parentFile, "images").canonicalFile
            File(root, relative).canonicalFile.takeIf { it.toPath().startsWith(root.toPath()) && it != root }
        } ?: return ""
        val cacheKey = ownerKey to source.canonicalPath
        copiedByOwnerAndSource[cacheKey]?.let { return it }
        if (!source.isFile) return ""
        check(destinationDir.isDirectory || destinationDir.mkdirs()) {
            "Cannot create attachment clone directory"
        }
        val suffix = source.extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
        val destination = File(destinationDir, "${UUID.randomUUID()}$suffix")
        createdFiles += destination
        AttachmentFiles.retainLivePath(this, destination.absolutePath)
        source.copyTo(destination, overwrite = false)
        return destination.absolutePath.also { copiedByOwnerAndSource[cacheKey] = it }
    }

    fun commit() {
        committed = true
        AttachmentFiles.releaseLivePaths(this)
        createdFiles.clear()
        copiedByOwnerAndSource.clear()
    }

    fun rollback() {
        if (committed) return
        AttachmentFiles.releaseLivePaths(this)
        AttachmentFiles.deleteUnownedPaths(createdFiles.asReversed().map(File::getAbsolutePath))
        createdFiles.clear()
        copiedByOwnerAndSource.clear()
    }
}

internal fun cloneAttachmentMeta(
    raw: String,
    cloneBackingPath: (String) -> String,
): String {
    val meta = cloneJson.decodeFromString<AttachmentMeta>(raw)
    return cloneJson.encodeToString(
        meta.copy(items = meta.items.map { item ->
            if (!item.storage.reclaimWhenAbandoned) return@map item
            val uri = item.originalUri
            val original = when {
                item.unavailable -> null
                uri == null -> null
                localCloneSource(uri) == null && ownedImageRelativePath(uri) == null -> uri
                else -> cloneBackingPath(uri.removePrefix("file://"))
                    .takeIf(String::isNotBlank)?.let { "file://$it" }
            }
            item.copy(
                originalUri = original,
                imageArtifacts = item.imageArtifacts.map { artifact ->
                    if (item.unavailable) artifact.asUnavailablePlaceholder()
                    else artifact.cloneArtifact(cloneBackingPath)
                },
            )
        }),
    )
}

private val cloneJson = Json { ignoreUnknownKeys = true }

private fun localCloneSource(path: String): File? = when {
    path.isBlank() -> null
    path.startsWith("file:", ignoreCase = true) -> runCatching { File(java.net.URI(path)) }.getOrElse {
        path.takeIf { it.startsWith("file://") }?.removePrefix("file://")?.let(::File)
    }
    File(path).isAbsolute -> File(path)
    else -> null
}

private fun ownedImageRelativePath(path: String): String? = runCatching {
    val uri = java.net.URI(path)
    val ownedAuthorities = setOf("com.newoether.agora.fileprovider", "com.newoether.agora.screenshots.fileprovider")
    if (uri.scheme != "content" || uri.authority !in ownedAuthorities ||
        uri.query != null || uri.fragment != null || !uri.path.orEmpty().startsWith("/images/")
    ) null else uri.path.removePrefix("/images/").takeIf(String::isNotBlank)
}.getOrNull()

private fun ImageArtifact.cloneArtifact(cloneBackingPath: (String) -> String): ImageArtifact {
    if (unavailable || path.isBlank()) return asUnavailablePlaceholder()
    val cloned = cloneBackingPath(path)
    return if (cloned.isBlank()) asUnavailablePlaceholder() else copy(path = cloned)
}
