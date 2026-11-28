package com.newoether.agora.ui.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * MediaStore writes issued by the media action sheet. Every save returns the absolute path it wrote
 * so the caller can tell the user exactly where the file landed.
 */

/** Save the image into the device gallery (Pictures/Agora). Returns the saved path on success. */
suspend fun saveImageToGallery(context: Context, url: String): String? = saveMediaToStore(
    context = context,
    url = url,
    collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    relativePath = "Pictures/Agora",
    resolveType = { _, source -> imageSourceType(source) },
)

/** Save a video into the device gallery (Movies/Agora). Returns the saved path on success. */
suspend fun saveVideoToGallery(context: Context, url: String): String? = saveMediaToStore(
    context = context,
    url = url,
    collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
    relativePath = "Movies/Agora",
    resolveType = { ctx, _ -> videoMimeAndExtension(ctx, url) },
)

/** Save an audio clip into the device Music folder (Music/Agora). Returns the saved path on success. */
suspend fun saveAudioToMusic(context: Context, url: String): String? = saveMediaToStore(
    context = context,
    url = url,
    collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
    relativePath = "Music/Agora",
    resolveType = { ctx, _ -> audioMimeAndExtension(ctx, url) },
)

internal fun mediaExtension(url: String): String =
    url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase(Locale.ROOT)

internal fun videoMimeAndExtension(context: Context, url: String): Pair<String, String> {
    val declared = if (url.startsWith("content://", true) || url.startsWith("file://", true)) {
        runCatching { context.contentResolver.getType(Uri.parse(url)) }.getOrNull()
    } else null
    val extension = mediaExtension(url)
    val mime = declared?.takeIf { it.startsWith("video/") }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.takeIf { it.startsWith("video/") }
        ?: "video/mp4"
    val resolvedExtension = extension.ifEmpty {
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "mp4"
    }
    return mime to resolvedExtension
}

private val AUDIO_MIME_BY_EXTENSION = mapOf(
    "mp3" to "audio/mpeg",
    "wav" to "audio/wav",
    "ogg" to "audio/ogg",
    "oga" to "audio/ogg",
    "opus" to "audio/opus",
    "m4a" to "audio/mp4",
    "aac" to "audio/aac",
    "flac" to "audio/flac",
    "webm" to "audio/webm",
    "amr" to "audio/amr",
    "mid" to "audio/midi",
    "midi" to "audio/midi",
)

internal fun audioMimeAndExtension(context: Context, url: String): Pair<String, String> {
    val declared = if (url.startsWith("content://", true) || url.startsWith("file://", true)) {
        runCatching { context.contentResolver.getType(Uri.parse(url)) }.getOrNull()
    } else null
    val extension = mediaExtension(url)
    val mime = declared?.takeIf { it.startsWith("audio/") }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.takeIf { it.startsWith("audio/") }
        ?: AUDIO_MIME_BY_EXTENSION[extension]
        ?: "audio/mpeg"
    val resolvedExtension = extension.ifEmpty {
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "mp3"
    }
    return mime to resolvedExtension
}

private suspend fun saveMediaToStore(
    context: Context,
    url: String,
    collection: Uri,
    relativePath: String,
    resolveType: (Context, OpenedImageSource) -> Pair<String, String>,
): String? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val actionContext = currentCoroutineContext()
    var destination: Uri? = null
    try {
        val mediaSource = openImageSource(context, url, actionContext::ensureActive)
            ?: return@withContext null
        mediaSource.use { source ->
            val (mime, extension) = resolveType(context, source)
            actionContext.ensureActive()
            val fileName = "agora_${System.currentTimeMillis()}.$extension"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(collection, values)
                ?: return@withContext null
            destination = uri
            val output = resolver.openOutputStream(uri)
                ?: throw IOException("Unable to open destination")
            output.use { sink -> copyImageBytes(source.input, sink, actionContext::ensureActive) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            // Pre-Q rows ignore RELATIVE_PATH, so those files land in the collection's root folder.
            val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                relativePath
            } else {
                relativePath.substringBefore('/')
            }
            File(Environment.getExternalStorageDirectory(), "$folder/$fileName").absolutePath
        }
    } catch (failure: Exception) {
        destination?.let { runCatching { resolver.delete(it, null, null) } }
        if (failure is CancellationException) throw failure
        null
    }
}
