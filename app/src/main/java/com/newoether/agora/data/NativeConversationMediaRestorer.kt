package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.model.SelectedAttachment
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

internal class NativeConversationMediaRestorer(
    private val context: Context,
    private val importJson: Json,
) {
    private fun detectImageExtension(bytes: ByteArray): String {
        if (bytes.size < 4) return "jpg"
        return when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
            bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "gif"
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() -> "webp"
            else -> "jpg"
        }
    }

    private fun detectVideoExtension(bytes: ByteArray): String {
        if (bytes.size < 4) return "mp4"
        return when {
            bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte() -> "webm"
            else -> "mp4"
        }
    }

    data class RestoredMediaFile(
        val absolutePath: String,
        val uri: String,
    )

    data class RestoredMedia(
        val archiveFiles: Map<String, RestoredMediaFile>,
        val legacyImagesByMessage: Map<String, Map<Int, String>>,
        val legacyVideosByMessage: Map<String, Map<Int, String>>,
        val createdFiles: List<File>,
    )

    fun restoreConversationMedia(archive: NativeBackupArchive): RestoredMedia {
        val archiveFiles = mutableMapOf<String, RestoredMediaFile>()
        val legacyImagesByMessage =
            mutableMapOf<String, MutableMap<Int, String>>()
        val legacyVideosByMessage =
            mutableMapOf<String, MutableMap<Int, String>>()
        val createdFiles = mutableListOf<File>()
        val names = archive.names()
        try {
            val imagesDir = File(context.filesDir, "images")
            imagesDir.mkdirs()

            fun restoreEntry(path: String, kind: String): RestoredMediaFile? {
                val header = archive.prefix(path, 16) ?: return null
                val extension = when (kind) {
                    "image" -> detectImageExtension(header)
                    "video" -> detectVideoExtension(header)
                    else -> path.substringAfterLast('.', "bin")
                        .lowercase()
                        .takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
                        ?: "bin"
                }
                val targetDir = if (kind == "image") imagesDir else context.filesDir
                val prefix = when (kind) {
                    "image" -> "img_import_"
                    "video" -> "vid_import_"
                    else -> "draft_import_"
                }
                val target = File(targetDir, "$prefix${UUID.randomUUID()}.$extension")
                // An empty attachment is exported as a normal entry, so it is restored as one.
                createdFiles += target
                archive.copyTo(path, target) ?: return null
                return RestoredMediaFile(target.absolutePath, "file://${target.absolutePath}")
            }

            names.asSequence()
                .filter {
                    it.startsWith(NativeBackupFormat.IMAGE_MEDIA_PREFIX) ||
                        it.startsWith(NativeBackupFormat.VIDEO_MEDIA_PREFIX) ||
                        it.startsWith(NativeBackupFormat.DRAFT_MEDIA_PREFIX)
                }
                .forEach { path ->
                    val kind = when {
                        path.startsWith(NativeBackupFormat.IMAGE_MEDIA_PREFIX) -> "image"
                        path.startsWith(NativeBackupFormat.VIDEO_MEDIA_PREFIX) -> "video"
                        else -> "draft"
                    }
                    restoreEntry(path, kind)?.let { archiveFiles[path] = it }
                }

            // v1-v3 media layout. Sort by the explicit numeric index instead of trusting ZIP
            // enumeration order.
            names.filter { it.startsWith("images/") }.forEach { path ->
                val parts = path.removePrefix("images/").split("/")
                if (parts.size != 2) return@forEach
                val index = parts[1].toIntOrNull() ?: return@forEach
                restoreEntry(path, "image")?.let { restored ->
                    legacyImagesByMessage
                        .getOrPut(parts[0]) { mutableMapOf() }[index] = restored.absolutePath
                }
            }

            names.filter { it.startsWith("videos/") }.forEach { path ->
                val parts = path.removePrefix("videos/").split("/")
                if (parts.size != 2) return@forEach
                val index = parts[1].toIntOrNull() ?: return@forEach
                restoreEntry(path, "video")?.let { restored ->
                    legacyVideosByMessage
                        .getOrPut(parts[0]) { mutableMapOf() }[index] = restored.uri
                }
            }
        } catch (error: Exception) {
            createdFiles.forEach { runCatching { it.delete() } }
            throw error
        }

        return RestoredMedia(
            archiveFiles = archiveFiles,
            legacyImagesByMessage = legacyImagesByMessage.mapValues { (_, indexed) ->
                indexed.toMap()
            },
            legacyVideosByMessage = legacyVideosByMessage,
            createdFiles = createdFiles,
        )
    }

    fun restoreDraftAttachments(
        raw: String?,
        restoredMedia: RestoredMedia,
    ): String? {
        if (raw.isNullOrBlank()) return null
        val attachments = runCatching {
            importJson.decodeFromString<List<SelectedAttachment>>(raw)
        }.getOrNull() ?: return null
        val restored = NativeBackupMediaPolicy.restoreDraftAttachments(
            attachments = attachments,
            restoredPrimaryForArchiveEntry = { entry ->
                restoredMedia.archiveFiles[entry]?.let { restoredFile ->
                    restoredFile.absolutePath to restoredFile.uri
                }
            },
            restoredPathForArchiveEntry = { entry ->
                restoredMedia.archiveFiles[entry]?.absolutePath
            },
        )
        return restored.takeIf(List<SelectedAttachment>::isNotEmpty)
            ?.let(importJson::encodeToString)
    }
}
