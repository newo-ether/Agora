package com.newoether.agora.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.newoether.agora.model.ImageArtifact
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt

/** The sole pixel and encoding boundary for images that Agora previews and sends to a model. */
class ImageArtifactProcessor(
    context: Context,
    val maxLongEdge: Int = DEFAULT_LONG_EDGE,
    val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
) {
    private val context = context.applicationContext

    init {
        require(maxLongEdge > 0)
        require(jpegQuality in 0..100)
    }

    data class PixelSize(val width: Int, val height: Int)

    /** Raster sources never upscale; PDF callers choose their raster resolution before this step. */
    fun targetSize(width: Int, height: Int): PixelSize {
        require(width > 0 && height > 0)
        val longest = maxOf(width, height)
        if (longest <= maxLongEdge) return PixelSize(width, height)
        val ratio = maxLongEdge.toDouble() / longest
        return PixelSize(
            (width * ratio).roundToInt().coerceIn(1, maxLongEdge),
            (height * ratio).roundToInt().coerceIn(1, maxLongEdge),
        )
    }

    /** Sampling only saves decode memory; enough pixels remain for the exact final resize. */
    fun sampleSizeForBounds(width: Int, height: Int): Int =
        sampleSizeForBounds(width, height, maxLongEdge)

    fun fromSource(
        source: String,
        directory: File,
        prefix: String = "img",
        outputOwner: Any? = null,
        existing: ImageArtifact? = null,
        maxBytes: Long = AttachmentFiles.MAX_ATTACHMENT_BYTES,
    ): ImageArtifact {
        if (existing != null && existing.path == source && isValid(existing, maxBytes)) return existing
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(source).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unsupported image source")
        val orientation = readOrientation(source)
        val swapsAxes = orientation in setOf(
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270,
        )
        val target = if (swapsAxes) targetSize(bounds.outHeight, bounds.outWidth)
            else targetSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeForBounds(bounds.outWidth, bounds.outHeight)
        }
        val decoded = open(source).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Image decode failed")
        var oriented = decoded
        try {
            orientationMatrix(orientation)?.let { matrix ->
                oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            }
            return publishSized(oriented, target, directory, prefix, outputOwner, maxBytes)
        } finally {
            if (oriented !== decoded) oriented.recycle()
            decoded.recycle()
        }
    }

    /** The caller retains ownership of [source]; only intermediate bitmaps are recycled here. */
    fun fromBitmap(
        source: Bitmap,
        directory: File,
        prefix: String = "img",
        outputOwner: Any? = null,
        maxBytes: Long = AttachmentFiles.MAX_ATTACHMENT_BYTES,
    ): ImageArtifact = publishSized(
        source, targetSize(source.width, source.height), directory, prefix, outputOwner, maxBytes,
    )

    /** Measures existing bytes through the source reader; this never copies or re-encodes them. */
    fun inspect(path: String, policyVersion: Int = 0): ImageArtifact {
        val resolvedFile = localFile(path)
        val source = resolvedFile?.absolutePath ?: path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(source).use { BitmapFactory.decodeStream(it, null, bounds) }
        val mime = bounds.outMimeType?.takeIf { it.startsWith("image/") }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || mime == null) {
            throw IOException("Unsupported or missing image artifact")
        }
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeForBounds(bounds.outWidth, bounds.outHeight)
        }
        val decoded = open(source).use { BitmapFactory.decodeStream(it, null, decodeOptions) }
            ?: throw IOException("Image artifact decode failed")
        decoded.recycle()
        val size = if (resolvedFile != null) resolvedFile.length() else {
            var total = 0L
            open(source).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                }
            }
            total
        }
        if (size <= 0) throw IOException("Image artifact is empty")
        return ImageArtifact(
            path = source,
            mimeType = mime,
            sizeBytes = size,
            width = bounds.outWidth,
            height = bounds.outHeight,
            policyVersion = policyVersion,
        )
    }

    /** Reuse outputs whose current measured properties still match the processing policy. */
    fun isValid(artifact: ImageArtifact, maxBytes: Long = AttachmentFiles.MAX_ATTACHMENT_BYTES): Boolean {
        if (artifact.unavailable || artifact.policyVersion != POLICY_VERSION || artifact.sizeBytes !in 1..maxBytes ||
            (artifact.width ?: 0) !in 1..maxLongEdge || (artifact.height ?: 0) !in 1..maxLongEdge ||
            artifact.mimeType !in setOf("image/jpeg", "image/png")
        ) return false
        return runCatching {
            val measured = inspect(artifact.path, artifact.policyVersion)
            measured.copy(path = artifact.path) == artifact
        }.getOrDefault(false)
    }

    private fun publishSized(
        source: Bitmap,
        target: PixelSize,
        directory: File,
        prefix: String,
        outputOwner: Any?,
        maxBytes: Long,
    ): ImageArtifact {
        val bitmap = if (source.width == target.width && source.height == target.height) source
            else Bitmap.createScaledBitmap(source, target.width, target.height, true)
        try {
            return publish(bitmap, directory, prefix, outputOwner, maxBytes)
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
    }

    private fun publish(
        bitmap: Bitmap,
        directory: File,
        prefix: String,
        outputOwner: Any?,
        maxBytes: Long,
    ): ImageArtifact {
        require(maxBytes > 0)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create image directory")
        val alpha = bitmap.hasAlpha()
        val format = if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val extension = if (alpha) "png" else "jpg"
        val safePrefix = prefix.map { if (it.isLetterOrDigit() || it == '_') it else '_' }
            .joinToString("").take(48).ifBlank { "img" }
        val destination = File(directory, "${safePrefix}_${UUID.randomUUID()}.$extension")
        val temporary = File(directory, ".${destination.name}.tmp")
        outputOwner?.let {
            AttachmentFiles.retainLivePath(it, destination.absolutePath)
            AttachmentFiles.retainLivePath(it, temporary.absolutePath)
        }
        try {
            temporary.outputStream().use { output ->
                if (!bitmap.compress(format, jpegQuality, output)) throw IOException("Image encoding failed")
                output.fd.sync()
            }
            val measured = inspect(temporary.absolutePath, POLICY_VERSION)
            if (measured.sizeBytes > maxBytes) throw IOException("Image artifact exceeds its byte bound")
            if (!temporary.renameTo(destination)) throw IOException("Could not publish image artifact")
            return measured.copy(path = destination.absolutePath)
        } finally {
            temporary.delete()
        }
    }

    private fun open(source: String) = AttachmentSourceReader.open(context, source)
        ?: throw IOException("Image source is unavailable")

    private fun readOrientation(source: String): Int = runCatching {
        open(source).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun orientationMatrix(orientation: Int): Matrix? = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setScale(1f, -1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { setRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { setRotate(-90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f) }
        else -> null
    }

    private fun localFile(path: String): File? = when {
        path.startsWith("file:", ignoreCase = true) -> File(java.net.URI(path))
        File(path).isAbsolute -> File(path)
        else -> null
    }

    companion object {
        const val DEFAULT_LONG_EDGE = 2048
        const val DEFAULT_JPEG_QUALITY = 80
        const val POLICY_VERSION = 1

        internal fun sampleSizeForBounds(width: Int, height: Int, maxLongEdge: Int): Int {
            require(width > 0 && height > 0 && maxLongEdge > 0)
            val longest = maxOf(width, height).toLong()
            var sample = 1
            while (longest / (sample.toLong() * 2L) >= maxLongEdge) sample *= 2
            return sample
        }
    }
}
