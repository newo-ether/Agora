package com.newoether.agora.viewmodel

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class VideoSliceConfig(
    val intervalMicros: Long,
    val frameCount: Int
)

class ImageProcessor(
    private val app: Application
) {
    suspend fun processImagesAndVideos(
        uris: List<String>,
        sliceConfigs: Map<String, VideoSliceConfig> = emptyMap()
    ): List<String> = withContext(Dispatchers.IO) {
        uris.flatMap { uriString ->
            try {
                val uri = android.net.Uri.parse(uriString)
                val mimeType = app.contentResolver.getType(uri)

                when {
                    mimeType?.startsWith("video/") == true -> {
                        val config = sliceConfigs[uriString]
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(app, uri)
                            val paths = mutableListOf<String>()

                            if (config != null && config.frameCount > 1) {
                                var timeUs = 0L
                                for (i in 0 until config.frameCount) {
                                    val bitmap = retriever.getFrameAtTime(
                                        timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST
                                    )
                                    if (bitmap != null) {
                                        val file = File(app.filesDir, "vid_${UUID.randomUUID()}_$i.jpg")
                                        file.outputStream().use { out ->
                                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                                        }
                                        bitmap.recycle()
                                        paths.add(file.absolutePath)
                                    }
                                    timeUs += config.intervalMicros
                                }
                            } else {
                                val bitmap = retriever.frameAtTime
                                if (bitmap != null) {
                                    val file = File(app.filesDir, "vid_${UUID.randomUUID()}.jpg")
                                    file.outputStream().use { out ->
                                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                                    }
                                    bitmap.recycle()
                                    paths.add(file.absolutePath)
                                }
                            }
                            paths
                        } finally {
                            retriever.release()
                        }
                    }
                    mimeType?.startsWith("image/") == true || mimeType == null -> {
                        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        app.contentResolver.openInputStream(uri)?.use { stream ->
                            android.graphics.BitmapFactory.decodeStream(stream, null, options)
                        }

                        if (options.outWidth > 0 && options.outHeight > 0) {
                            var scale = 1
                            while (options.outWidth / scale / 2 >= 1024 && options.outHeight / scale / 2 >= 1024) {
                                scale *= 2
                            }

                            val decodeOptions = android.graphics.BitmapFactory.Options().apply { inSampleSize = scale }
                            val bitmap = app.contentResolver.openInputStream(uri)?.use { stream ->
                                android.graphics.BitmapFactory.decodeStream(stream, null, decodeOptions)
                            }

                            if (bitmap != null) {
                                val file = File(app.filesDir, "img_${UUID.randomUUID()}.jpg")
                                file.outputStream().use { out ->
                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                                }
                                bitmap.recycle()
                                listOf(file.absolutePath)
                            } else emptyList()
                        } else emptyList()
                    }
                    else -> emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
