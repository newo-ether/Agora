package com.newoether.agora.sharing

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.newoether.agora.util.AttachmentFiles
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.AttachmentImportProcessor
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.newoether.agora.R
import com.newoether.agora.model.SelectedAttachment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.util.UUID

const val EXTRA_SHARED_TEXT_REQUEST = "com.newoether.agora.extra.SHARED_TEXT_REQUEST"
const val EXTRA_EXCERPT_METADATA = "org.koreader.extra.EXCERPT_METADATA"

/** Copies untrusted incoming text into app-owned bytes before the sending Activity loses focus. */
internal class SharedTextInbox(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun directory(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        return File(context.filesDir, "attachments/shared/$id")
    }

    suspend fun receive(id: String, intent: Intent): SelectedAttachment {
        val folder = directory(id)
        val record = File(folder, "attachment.json")
        if (record.isFile) return load(id)
        require(intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) { "Share plain text to Agora" }
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        require(stream == null || (stream.scheme == "content" && intent.type == "text/plain")) {
            "Share a readable text file using a content URI"
        }
        val inline = if (stream == null) intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty() else null
        if (inline != null) {
            require(inline.isNotBlank()) { "The shared content contains no text" }
            require(inline.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "Shared text is too large (512 KiB maximum)" }
        }
        val raw = intent.getStringExtra(EXTRA_EXCERPT_METADATA)
        val metadata = raw?.takeIf { it.length <= 140_000 }?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.optString("schema") == "org.koreader.excerpt/v1" }
        val title = label(metadata?.optString("title")) ?: label(intent.getStringExtra(Intent.EXTRA_TITLE))
            ?: label(intent.getStringExtra(Intent.EXTRA_SUBJECT)) ?: context.getString(R.string.shared_content_name)
        val book = label(metadata?.optString("bookTitle"))
        val author = label(metadata?.optString("authors"))
        folder.mkdirs()
        // Use the ordinary importer while the Activity still owns the temporary URI grant.
        val imported = stream?.let { receiveFile(id, it) }
        val content = imported?.localPath?.let(::File) ?: File(folder, "excerpt.txt").apply { writeText(requireNotNull(inline)) }
        val text = imported?.preparedText ?: requireNotNull(inline)
        require(text.isNotBlank()) { "The shared content contains no text" }
        val contextLimited = imported != null && content.inputStream().bufferedReader().use {
            var remaining = Constants.MAX_FILE_CONTENT_READ_LENGTH
            val buffer = CharArray(8192)
            while (remaining > 0) {
                val count = it.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                remaining -= count
            }
            remaining == 0 && it.read() != -1
        }
        val warning = if (contextLimited) context.getString(R.string.shared_content_context_limit, Constants.MAX_FILE_CONTENT_READ_LENGTH) else null
        val preview = metadata?.optString("thumbnailBase64")?.takeIf { it.length in 1..120_000 }
            ?.let { encoded -> runCatching { saveThumbnail(folder, Base64.decode(encoded, Base64.DEFAULT)) }.getOrNull() }
            ?: receiveStandardThumbnail(folder, intent)
        val prepared = buildString {
            append("Excerpt: ").append(title).append('\n')
            book?.let { append("Book: ").append(it).append('\n') }
            author?.let { append("Author: ").append(it).append('\n') }
            warning?.let { append(it).append('\n') }
            append('\n').append(text)
        }
        val attachment = SelectedAttachment(localId = id, uri = content.toURI().toString(), type = "file",
            fileName = (book?.takeUnless { it == title }?.let { "$it — $title" } ?: title) + ".txt", mimeType = "text/plain", fileSize = content.length(),
            localPath = content.absolutePath, preparedText = prepared, warning = warning,
            sourceTitle = title, sourceBookTitle = book, sourceAuthors = author, sourceThumbnailPath = preview)
        val temporary = File(folder, "attachment.json.tmp")
        temporary.writeText(json.encodeToString(attachment))
        check(temporary.renameTo(record)) { "Could not save shared content" }
        return attachment
    }

    private suspend fun receiveFile(id: String, uri: Uri): SelectedAttachment {
        val processor = AttachmentImportProcessor(context.applicationContext as Application)
        val source = SelectedAttachment(localId = id, uri = uri.toString(), type = "file", mimeType = "text/plain")
        val staged = when (val result = processor.stage(source)) {
            is AttachmentImportProcessor.StageResult.Success -> result.attachment
            AttachmentImportProcessor.StageResult.TooLarge -> error("Shared file exceeds the 100 MiB attachment limit")
            is AttachmentImportProcessor.StageResult.Failure -> throw result.cause ?: IllegalStateException("Could not copy shared file")
        }
        try {
            return when (val result = processor.process(staged)) {
                is AttachmentImportProcessor.ProcessResult.Ready -> {
                    require(!result.attachment.preparedText.isNullOrBlank()) { "The shared content contains no text" }
                    val owned = File(directory(id), "excerpt.txt")
                    check(File(requireNotNull(staged.localPath)).renameTo(owned)) { "Could not retain shared file" }
                    File(requireNotNull(staged.localPath)).parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
                    result.attachment.copy(localPath = owned.absolutePath)
                }
                is AttachmentImportProcessor.ProcessResult.Failure -> throw result.cause ?: IllegalStateException("Could not read shared file")
            }
        } catch (failure: Exception) {
            AttachmentFiles.deleteBacking(staged)
            throw failure
        }
    }

    private fun label(value: String?): String? = value
        ?.map { if (it.isISOControl()) ' ' else it }
        ?.joinToString("")
        ?.trim()
        ?.take(300)
        ?.takeIf { it.isNotBlank() }

    private fun receiveStandardThumbnail(folder: File, intent: Intent): String? {
        val candidates = buildList {
            intent.clipData?.let { clip ->
                for (index in 0 until minOf(clip.itemCount, 8)) clip.getItemAt(index).uri?.let(::add)
            }
            intent.data?.let(::add)
        }.distinct()
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        for (uri in candidates) {
            if (uri.scheme != "content" || uri == stream) continue
            val thumbnail = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 90_000) { "Source cover is too large" }
                        output.write(buffer, 0, count)
                    }
                    saveThumbnail(folder, output.toByteArray())
                }
            }.getOrNull()
            if (thumbnail != null) return thumbnail
        }
        return null
    }

    private fun saveThumbnail(folder: File, bytes: ByteArray): String {
        require(bytes.size <= 90_000)
        val jpeg = bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        val png = bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        require(jpeg || png) { "Invalid source thumbnail" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048)
        val image = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            val file = File(folder, "cover.jpg")
            file.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
            return file.absolutePath
        } finally {
            image.recycle()
        }
    }

    fun loadMany(id: String): List<SelectedAttachment> =
        SharedFileInbox(context).load(id) ?: listOf(load(id))
    fun load(id: String): SelectedAttachment = json.decodeFromString(File(directory(id), "attachment.json").readText())
    fun owner(id: String): String? = File(directory(id), "owner").takeIf { it.isFile }?.readText()
    fun choose(id: String, owner: String) {
        marker(id, "owner", owner)
    }
    private fun marker(id: String, name: String, value: String) {
        val folder = directory(id)
        val temporary = File(folder, "$name.tmp")
        temporary.writeText(value)
        check(temporary.renameTo(File(folder, name))) { "Could not save share receipt" }
    }
    fun consumed(id: String) = File(directory(id), "consumed").exists()
    fun consume(id: String) {
        marker(id, "consumed", "1")
    }
}
