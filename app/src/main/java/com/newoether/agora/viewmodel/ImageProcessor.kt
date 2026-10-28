package com.newoether.agora.viewmodel

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.util.AttachmentSourceReader
import com.newoether.agora.util.ImageArtifactProcessor
import java.io.File
import java.net.URI
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class VideoSliceConfig(
    val intervalMicros: Long,
    val frameCount: Int,
)

/** Pixel size of the actual encoded artifact used for model input. */
data class ImagePixelSize(val width: Int, val height: Int)

internal fun imageSampleSizeForBounds(
    width: Int,
    height: Int,
    maxEdge: Int = ImageArtifactProcessor.DEFAULT_LONG_EDGE,
): Int = ImageArtifactProcessor.sampleSizeForBounds(width, height, maxEdge)

class ImageProcessor(
    private val app: Application,
    private val artifacts: ImageArtifactProcessor? = null,
) {
    suspend fun normalizeImage(source: String, outputOwner: Any? = null): String? = withContext(Dispatchers.IO) {
        var output: String? = null
        try {
            coroutineContext.ensureActive()
            val processor = artifacts ?: ImageArtifactProcessor.configured(app)
            output = processor.fromSource(source, app.filesDir, "img", outputOwner).path
            coroutineContext.ensureActive()
            output
        } catch (cancelled: CancellationException) {
            output?.let { File(it).delete() }
            throw cancelled
        } catch (_: Exception) {
            output?.let { File(it).delete() }
            null
        }
    }

    suspend fun measureArtifact(source: String): ImageArtifact = withContext(Dispatchers.IO) {
        (artifacts ?: ImageArtifactProcessor(app)).inspect(source, ImageArtifactProcessor.POLICY_VERSION)
    }

    /** Legacy callers may still request only bounds; new imports retain the complete descriptor. */
    suspend fun measurePixels(source: String): ImagePixelSize? = withContext(Dispatchers.IO) {
        try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            AttachmentSourceReader.open(app, source)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
            else ImagePixelSize(bounds.outWidth, bounds.outHeight)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun extractVideoFrames(
        source: String,
        config: VideoSliceConfig,
        outputOwner: Any? = null,
    ): List<String> = withContext(Dispatchers.IO) {
        val paths = mutableListOf<String>()
        val retriever = MediaMetadataRetriever()
        try {
            val processor = artifacts ?: ImageArtifactProcessor.configured(app)
            setRetrieverSource(retriever, source)
            val frameCount = config.frameCount.coerceAtLeast(1)
            var timeUs = 0L
            repeat(frameCount) { index ->
                coroutineContext.ensureActive()
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bitmap != null) {
                    try {
                        val artifact = processor.fromBitmap(bitmap, app.filesDir, "vid_$index", outputOwner)
                        paths += artifact.path
                        coroutineContext.ensureActive()
                    } finally {
                        bitmap.recycle()
                    }
                }
                timeUs += config.intervalMicros.coerceAtLeast(0L)
            }
            paths
        } catch (cancelled: CancellationException) {
            paths.forEach { File(it).delete() }
            throw cancelled
        } catch (_: Exception) {
            paths.forEach { File(it).delete() }
            emptyList()
        } finally {
            retriever.release()
        }
    }

    private fun setRetrieverSource(retriever: MediaMetadataRetriever, source: String) {
        when {
            File(source).isAbsolute -> retriever.setDataSource(source)
            source.startsWith("file:", ignoreCase = true) ->
                retriever.setDataSource(File(URI(source)).absolutePath)
            else -> retriever.setDataSource(app, Uri.parse(source))
        }
    }
}
