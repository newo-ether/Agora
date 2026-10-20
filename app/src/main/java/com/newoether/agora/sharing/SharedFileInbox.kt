package com.newoether.agora.sharing

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.AttachmentFiles
import com.newoether.agora.util.FileValidator
import com.newoether.agora.viewmodel.AttachmentImportProcessor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Copies a share group before temporary grants expire; the ordinary Composer owns preparation. */
internal class SharedFileInbox(
    private val context: Context,
    private val processor: AttachmentImportProcessor = AttachmentImportProcessor(context.applicationContext as Application),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun folder(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        return File(context.filesDir, "attachments/shared/$id")
    }

    fun load(id: String): List<SelectedAttachment>? = File(folder(id), "attachments.json")
        .takeIf { it.isFile }?.let { json.decodeFromString(it.readText()) }

    suspend fun receive(id: String, intent: Intent): List<SelectedAttachment> {
        load(id)?.let { return it }
        require(intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE))
        val uris = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }.ifEmpty {
            buildList {
                intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add) }
                if (isEmpty()) intent.data?.let(::add)
            }
        }.distinct()
        require(uris.size in 1..32 && uris.all { it.scheme == "content" }) { "Share up to 32 readable files using content URIs" }
        val folder = folder(id).apply { mkdirs() }
        val copied = mutableListOf<SelectedAttachment>()
        try {
            for ((index, uri) in uris.withIndex()) {
                val mime = FileValidator.resolveMimeType(context, uri.toString())
                    ?: intent.type?.takeUnless { '*' in it }
                val source = requireNotNull(FileValidator.inspectAttachment(
                    source = uri.toString(), fileName = FileValidator.resolveFileName(context, uri) ?: uri.lastPathSegment ?: "Attachment",
                    mimeType = mime, fileSize = FileValidator.resolveFileSize(context, uri),
                    forcedType = null, allowLocalSandbox = true,
                )).copy(localId = UUID.nameUUIDFromBytes("$id:$index".toByteArray()).toString())
                val staged = when (val result = processor.stage(source)) {
                    is AttachmentImportProcessor.StageResult.Success -> result.attachment
                    AttachmentImportProcessor.StageResult.TooLarge -> error("Shared file exceeds the 100 MiB attachment limit")
                    is AttachmentImportProcessor.StageResult.Failure -> {
                        result.attachment?.let(AttachmentFiles::deleteBacking)
                        throw result.cause ?: IllegalStateException("Could not copy shared file")
                    }
                }
                copied += staged
                val original = File(requireNotNull(staged.localPath))
                val owned = File(folder, "${staged.localId}/source.${original.extension}")
                check(owned.parentFile!!.isDirectory || owned.parentFile!!.mkdirs())
                check(original.renameTo(owned)) { "Could not retain shared file" }
                val retained = staged.copy(localPath = owned.absolutePath)
                copied[copied.lastIndex] = retained.copy(sourceThumbnailPath =
                    SharedFileThumbnail.create(context, uri, retained))
                original.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
            }
            val temporary = File(folder, "attachments.json.tmp")
            temporary.writeText(json.encodeToString(copied))
            check(temporary.renameTo(File(folder, "attachments.json"))) { "Could not save shared files" }
            return copied
        } catch (failure: Exception) {
            if (!File(folder, "attachments.json").isFile) copied.forEach(AttachmentFiles::deleteBacking)
            throw failure
        }
    }
}
