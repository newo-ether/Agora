package com.newoether.agora.tool

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Base64InputStream
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.util.ImageArtifactProcessor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Owns bounded tool ingress; model images are published by the shared image processor. */
class ToolImageStore(
    context: Context,
    private val directory: File = File(context.applicationContext.filesDir, "tool-media"),
    /** Remote viewing displays another session's bytes and must not prepare model input. */
    private val prepareForModel: Boolean = true,
    private val processor: ImageArtifactProcessor = ImageArtifactProcessor(context),
) {
    companion object {
        const val MAX_IMAGE_BYTES = 20L * 1024L * 1024L
    }

    fun persistStream(
        input: InputStream,
        mimeType: String,
        filePrefix: String = "tool",
    ): ToolImageAttachment {
        val mime = mimeType.substringBefore(';').trim().lowercase()
        if (!mime.startsWith("image/")) throw IOException("Unsupported tool image type")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create tool media directory")
        val prefix = safePrefix(filePrefix)
        val destination = File(directory, "${prefix}_${UUID.randomUUID()}.${extensionFor(mime)}")
        val temporary = File(directory, ".${destination.name}.tmp")
        try {
            var size = 0L
            temporary.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > MAX_IMAGE_BYTES) throw IOException("Tool image exceeds its byte bound")
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            if (prepareForModel) {
                return processor.fromSource(
                    temporary.absolutePath, directory, prefix, maxBytes = MAX_IMAGE_BYTES,
                )
            }
            val measured = processor.inspect(temporary.absolutePath)
            if (!temporary.renameTo(destination)) throw IOException("Could not finalize tool image")
            return measured.copy(path = destination.absolutePath, mimeType = mime)
        } finally {
            temporary.delete()
        }
    }

    fun persistBase64(
        data: String,
        mimeType: String,
        filePrefix: String = "tool",
    ): ToolImageAttachment {
        val estimatedBytes = data.length.toLong() * 3L / 4L
        if (estimatedBytes > MAX_IMAGE_BYTES + 3L) {
            throw IOException("Tool image exceeds ${MAX_IMAGE_BYTES / (1024 * 1024)} MB")
        }
        val encoded = object : InputStream() {
            private var position = 0

            override fun read(): Int {
                if (position >= data.length) return -1
                val value = data[position++].code
                if (value > 127) throw IOException("Tool image contains invalid base64")
                return value
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (offset < 0 || length < 0 || offset > buffer.size - length) {
                    throw IndexOutOfBoundsException()
                }
                if (length == 0) return 0
                if (position >= data.length) return -1
                val count = minOf(length, data.length - position)
                for (index in 0 until count) {
                    val value = data[position++].code
                    if (value > 127) throw IOException("Tool image contains invalid base64")
                    buffer[offset + index] = value.toByte()
                }
                return count
            }
        }
        return Base64InputStream(encoded, Base64.DEFAULT).use { decoded ->
            persistStream(decoded, mimeType, filePrefix)
        }
    }

    fun persistGeneratedBytes(
        bytes: ByteArray,
        filePrefix: String = "generated",
    ): ToolImageAttachment = persistBytes(bytes, "image/unknown", filePrefix)

    fun persistBytes(
        bytes: ByteArray,
        mimeType: String,
        filePrefix: String = "tool",
    ): ToolImageAttachment {
        if (bytes.isEmpty()) throw IOException("Tool image is empty")
        if (bytes.size.toLong() > MAX_IMAGE_BYTES) throw IOException("Tool image exceeds its byte bound")
        return ByteArrayInputStream(bytes).use { persistStream(it, mimeType, filePrefix) }
    }

    /** Phone and other bitmap producers share the same sizing and encoding without a byte array. */
    fun persistBitmap(bitmap: Bitmap, filePrefix: String = "tool"): ToolImageAttachment {
        check(prepareForModel) { "Display-only stores do not prepare model bitmaps" }
        return processor.fromBitmap(bitmap, directory, safePrefix(filePrefix), maxBytes = MAX_IMAGE_BYTES)
    }

    private fun safePrefix(filePrefix: String): String = filePrefix
        .map { char -> if (char.isLetterOrDigit() || char == '_') char else '_' }
        .joinToString("")
        .trim('_')
        .ifBlank { "tool" }
        .take(32)

    private fun extensionFor(mime: String): String = when (mime) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        "image/avif" -> "avif"
        else -> "img"
    }
}
