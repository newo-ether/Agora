package com.newoether.agora.viewmodel

import com.newoether.agora.templates.readBytesBounded
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.AttachmentFiles
import java.io.File
import java.util.UUID

/** Selected source snapshots use the same owned files and message metadata as ordinary attachments. */
internal class TemplateReferenceAttachments(private val application: android.app.Application? = null, private val filesDirectory: () -> File) : AutoCloseable {
    private val owner = Any()
    private val paths = mutableListOf<String>()
    private val staged = mutableListOf<SelectedAttachment>()
    private var sendAttempted = false
    private var nativeSourceBytes = 0L
    val attachments: List<SelectedAttachment> get() = staged.toList()

    /** Called on IO, after the caller has validated the source path and combined byte limit. */
    fun add(path: String, content: String) {
        val name = AttachmentFiles.sanitizeFileName(path.substringAfterLast('/'))
        val file = File(filesDirectory(), "att_${UUID.randomUUID()}_$name")
        AttachmentFiles.retainLivePath(owner, file.absolutePath)
        paths += file.absolutePath
        file.writeText(content, Charsets.UTF_8)
        staged += SelectedAttachment(uri = file.toURI().toString(), type = "file", fileName = name,
            mimeType = "text/markdown", fileSize = file.length(), localPath = file.absolutePath, preparedText = content)
    }

    /** Staging and media conversion share the ordinary picker pipeline, with the same file ownership. */
    suspend fun addNative(access: com.newoether.agora.templates.TemplateAndroidSources, folder: String, path: String) {
        val app = checkNotNull(application)
        val (uri, info) = access.resolve(folder, path)
        val mime = info.mimeType?.takeUnless { it == "application/octet-stream" }
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(info.name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val type = when { mime == "application/pdf" -> "pdf"; mime.startsWith("image/") -> "image"; else -> "file" }
        val processor = AttachmentImportProcessor(app)
        val source = SelectedAttachment(uri = uri.toString(), type = type, fileName = info.name,
            mimeType = mime, fileSize = info.size)
        val stagedResult = processor.stage(source, owner)
        val stagedFile = when (stagedResult) {
            is AttachmentImportProcessor.StageResult.Success -> {
                paths += stagedResult.createdPaths
                stagedResult.attachment
            }
            AttachmentImportProcessor.StageResult.TooLarge -> error("Selected file exceeds the attachment size limit")
            is AttachmentImportProcessor.StageResult.Failure -> {
                paths += stagedResult.createdPaths
                throw IllegalStateException("Could not attach ${info.name}", stagedResult.cause)
            }
        }
        nativeSourceBytes += stagedFile.fileSize ?: 0L
        require(nativeSourceBytes <= AttachmentFiles.MAX_ATTACHMENT_BYTES) { "Selected files exceed the attachment size limit" }
        if (type == "file") {
            val text = com.newoether.agora.templates.decodeTemplateFileText(File(checkNotNull(stagedFile.localPath)).inputStream().use {
                it.readBytesBounded(128000)
            })
            staged += stagedFile.copy(importState = com.newoether.agora.model.AttachmentImportState.READY, preparedText = text)
        } else when (val processed = processor.process(stagedFile, outputOwner = owner)) {
            is AttachmentImportProcessor.ProcessResult.Ready -> {
                paths += processed.createdPaths
                staged += processed.attachment
            }
            is AttachmentImportProcessor.ProcessResult.Failure -> throw IllegalStateException("Could not prepare ${info.name}", processed.cause)
        }
        require(staged.sumOf { it.fileSize ?: 0L } <= AttachmentFiles.MAX_ATTACHMENT_BYTES) { "Selected files exceed the attachment size limit" }
    }

    fun handOffForSend() { sendAttempted = true }

    override fun close() {
        AttachmentFiles.releaseLivePaths(owner)
        // A Send can commit before cancellation is observed. Once attempted, leave ambiguous
        // files to the existing reference-aware orphan sweeper instead of risking a durable file.
        if (!sendAttempted) AttachmentFiles.deleteUnownedPaths(paths)
    }
}
