package com.newoether.agora.api.util

import android.graphics.BitmapFactory
import com.newoether.agora.api.HttpClient
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.util.AttachmentFiles
import com.newoether.agora.util.ImageArtifactProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.URI
import java.util.Base64
import java.util.UUID

private val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()

internal data class StreamingJsonRequest(
    val body: RequestBody,
    val diagnosticJson: String,
)

/** Occurrences retain their own availability even when several refer to the same path. */
internal fun ChatMessage.imageArtifactsForSend(): List<ImageArtifact> =
    images.zip(imageArtifactOccurrences()).mapNotNull { (reference, artifact) ->
        artifact?.takeIf { reference.isNotBlank() && !it.unavailable }
    }

internal fun readableImageArtifactFile(artifact: ImageArtifact): File? {
    val file = imageArtifactFile(artifact) ?: return null
    return file.takeIf { readImageSnapshot(it) != null }
}

private fun imageArtifactFile(artifact: ImageArtifact): File? {
    if (artifact.unavailable || artifact.path.isBlank()) return null
    return try {
        val source = if (artifact.path.startsWith("file:", ignoreCase = true)) {
            File(URI(artifact.path))
        } else {
            File(artifact.path)
        }
        if (source.isAbsolute) source.canonicalFile.takeIf { it.isFile && it.canRead() } else null
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

private data class ImageSnapshot(val bytes: ByteArray, val mimeType: String)

/** A single bounded original-byte snapshot is decoded before any of its JSON is written. */
private fun readImageSnapshot(file: File): ImageSnapshot? = try {
    checkImageReadCancellation()
    val length = file.length()
    if (!file.isFile || !file.canRead() || length !in 1..AttachmentFiles.MAX_ATTACHMENT_BYTES) {
        null
    } else {
        val bytes = ByteArray(length.toInt())
        val complete = file.inputStream().use { input ->
            var offset = 0
            while (offset < bytes.size) {
                checkImageReadCancellation()
                val count = input.read(bytes, offset, minOf(DEFAULT_BUFFER_SIZE, bytes.size - offset))
                if (count < 0) return@use false
                offset += count
            }
            input.read() < 0
        }
        if (!complete) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val mime = bounds.outMimeType?.takeIf { it.startsWith("image/") }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || mime == null) null else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = ImageArtifactProcessor.sampleSizeForBounds(
                        bounds.outWidth, bounds.outHeight, ImageArtifactProcessor.DEFAULT_LONG_EDGE,
                    )
                }
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (decoded == null) null else {
                    decoded.recycle()
                    checkImageReadCancellation()
                    ImageSnapshot(bytes, mime)
                }
            }
        }
    }
} catch (error: CancellationException) {
    throw error
} catch (error: InterruptedIOException) {
    throw error
} catch (_: IOException) {
    null
} catch (_: IllegalArgumentException) {
    null
} catch (_: SecurityException) {
    null
}

private fun checkImageReadCancellation() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Image read interrupted")
    if (HttpClient.isRequestCancelled()) throw CancellationException("Image read cancelled")
}

/** Keeps only paths and opaque placeholders between writes; each write buffers one image at a time. */
internal class Base64FileRegistry {
    data class RegisteredImage(val placeholder: String, val mimeType: String)

    private data class FilePart(
        val image: RegisteredImage,
        val file: File,
        val byteCount: Int,
    )

    private val lock = Any()
    private val files = mutableListOf<FilePart>()
    private var sealed = false

    fun register(artifact: ImageArtifact): RegisteredImage? {
        synchronized(lock) { check(!sealed) { "Image registry is already prepared" } }
        val file = imageArtifactFile(artifact) ?: return null
        val snapshot = readImageSnapshot(file) ?: return null
        val image = RegisteredImage("__AGORA_BASE64_${UUID.randomUUID()}__", snapshot.mimeType)
        synchronized(lock) {
            check(!sealed) { "Image registry is already prepared" }
            files += FilePart(image, file, snapshot.bytes.size)
        }
        return image
    }

    fun prepare(templateJson: String): StreamingJsonRequest? {
        val snapshot = synchronized(lock) {
            sealed = true
            files.toList()
        }
        if (snapshot.isEmpty()) return null
        var diagnosticJson = templateJson
        snapshot.forEach { part ->
            val placeholder = part.image.placeholder
            val position = templateJson.indexOf(placeholder)
            require(position >= 0) { "Base64 placeholder is missing from serialized request" }
            require(templateJson.indexOf(placeholder, position + placeholder.length) < 0) {
                "Base64 placeholder occurs more than once in serialized request"
            }
            diagnosticJson = diagnosticJson.replace(placeholder, "[STREAMED_BASE64:${part.byteCount}_BYTES]")
        }
        return StreamingJsonRequest(
            body = ReplayableJsonRequestBody(Json.parseToJsonElement(templateJson), snapshot),
            diagnosticJson = diagnosticJson,
        )
    }

    private class ReplayableJsonRequestBody(
        private val template: JsonElement,
        files: List<FilePart>,
    ) : RequestBody() {
        private val filesByPlaceholder = files.associateBy { it.image.placeholder }

        override fun contentType(): MediaType = JSON_MEDIA_TYPE

        // Filtering and file sizes may change between prepare and an OkHttp replay.
        override fun contentLength(): Long = -1L

        override fun writeTo(sink: BufferedSink) = writeJson(template, sink)

        private fun writeJson(element: JsonElement, sink: BufferedSink, field: String? = null) {
            when (element) {
                is JsonObject -> {
                    sink.writeByte('{'.code)
                    element.entries.forEachIndexed { index, (key, value) ->
                        if (index > 0) sink.writeByte(','.code)
                        sink.writeUtf8(JsonPrimitive(key).toString()).writeByte(':'.code)
                        writeJson(value, sink, key)
                    }
                    sink.writeByte('}'.code)
                }
                is JsonArray -> writeArray(element, sink, field)
                else -> sink.writeUtf8(element.toString())
            }
        }

        private fun writeArray(array: JsonArray, sink: BufferedSink, field: String?) {
            sink.writeByte('['.code)
            var written = 0
            for (element in array) {
                val part = imagePart(element)
                val image = if (part != null) readImageSnapshot(part.file) ?: continue else null
                if (written++ > 0) sink.writeByte(','.code)
                if (part != null && image != null) writeImage(element, part, image, sink)
                else writeJson(element, sink)
            }
            if (written == 0 && array.isNotEmpty() && field in setOf("content", "parts")) {
                val type = (array.firstOrNull() as? JsonObject)?.get("type") as? JsonPrimitive
                val fallback = when {
                    field == "parts" -> "{\"text\":\"Continue\"}"
                    type?.content == "input_image" -> "{\"type\":\"input_text\",\"text\":\"Continue\"}"
                    else -> "{\"type\":\"text\",\"text\":\"Continue\"}"
                }
                sink.writeUtf8(fallback)
            }
            sink.writeByte(']'.code)
        }

        private fun imagePart(element: JsonElement): FilePart? {
            val isImage = when (element) {
                is JsonPrimitive -> element.isString && element.content in filesByPlaceholder
                is JsonObject -> (element["type"] as? JsonPrimitive)?.content in
                    setOf("image_url", "input_image", "image") || element["inlineData"] != null
                else -> false
            }
            if (!isImage) return null
            return findPlaceholder(element)?.let(filesByPlaceholder::get)
        }

        private fun findPlaceholder(element: JsonElement): String? = when (element) {
            is JsonPrimitive -> if (element.isString) {
                element.content.substringAfter(";base64,", element.content).takeIf { it in filesByPlaceholder }
            } else null
            is JsonObject -> element.values.firstNotNullOfOrNull(::findPlaceholder)
            is JsonArray -> element.firstNotNullOfOrNull(::findPlaceholder)
            else -> null
        }

        private fun writeImage(element: JsonElement, part: FilePart, image: ImageSnapshot, sink: BufferedSink) {
            val json = element.toString()
                .replace(JsonPrimitive(part.image.mimeType).toString(), JsonPrimitive(image.mimeType).toString())
                .replace("data:${part.image.mimeType};base64,", "data:${image.mimeType};base64,")
            val position = json.indexOf(part.image.placeholder)
            check(position >= 0)
            sink.writeUtf8(json.substring(0, position))
            Base64.getEncoder().wrap(NonClosingOutputStream(sink.outputStream())).use { output ->
                output.write(image.bytes)
            }
            sink.writeUtf8(json.substring(position + part.image.placeholder.length))
        }
    }

    private class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
        override fun write(value: Int) = delegate.write(value)
        override fun write(buffer: ByteArray, offset: Int, length: Int) = delegate.write(buffer, offset, length)
        override fun flush() = delegate.flush()
        override fun close() = Unit
    }
}
