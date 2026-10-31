package com.newoether.agora.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.net.URI
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

object PdfPageRenderer {
    private const val MAX_PAGES = 5

    suspend fun renderAsImages(
        context: Context,
        uri: Uri,
        pages: Set<Int>? = null,
    ): List<String> = renderAsImages(context, uri.toString(), pages)

    suspend fun renderAsImages(
        context: Context,
        source: String,
        pages: Set<Int>? = null,
        outputOwner: Any? = null,
    ): List<String> = renderPages(context, source, outputOwner, select = { count ->
        pages?.filter { it in 0 until count }?.distinct()?.sorted()
            ?: (0 until minOf(count, MAX_PAGES)).toList()
    })

    suspend fun renderAllPages(
        context: Context,
        uri: Uri,
        maxPages: Int = 200,
        onProgress: (suspend (current: Int, total: Int) -> Unit)? = null,
    ): List<String> = renderAllPages(context, uri.toString(), maxPages, onProgress)

    /** These completed page artifacts are also used by the eventual selected-page request. */
    suspend fun renderAllPages(
        context: Context,
        source: String,
        maxPages: Int = 200,
        onProgress: (suspend (current: Int, total: Int) -> Unit)? = null,
        outputOwner: Any? = null,
    ): List<String> = renderPages(context, source, outputOwner, onProgress) { count ->
        (0 until minOf(count, maxPages.coerceAtLeast(0))).toList()
    }

    private suspend fun renderPages(
        context: Context,
        source: String,
        outputOwner: Any?,
        onProgress: (suspend (current: Int, total: Int) -> Unit)? = null,
        select: (Int) -> List<Int>,
    ): List<String> {
        val processor = ImageArtifactProcessor.configured(context)
        val descriptor = openDescriptor(context, source) ?: return emptyList()
        val renderer = runCatching { PdfRenderer(descriptor) }
            .onFailure { runCatching { descriptor.close() } }.getOrThrow()
        val paths = mutableListOf<String>()
        try {
            val indices = select(renderer.pageCount)
            for ((position, index) in indices.withIndex()) {
                coroutineContext.ensureActive()
                val page = renderer.openPage(index)
                var bitmap: Bitmap? = null
                try {
                    // PDF dimensions are points; choose a raster resolution, not a raster upscale.
                    val scale = processor.maxLongEdge.toDouble() / maxOf(page.width, page.height)
                    val width = (page.width * scale).roundToInt().coerceIn(1, processor.maxLongEdge)
                    val height = (page.height * scale).roundToInt().coerceIn(1, processor.maxLongEdge)
                    bitmap = createPageBitmap(width, height)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    paths += processor.fromBitmap(bitmap, context.filesDir, "pdf_$index", outputOwner).path
                    coroutineContext.ensureActive()
                } finally {
                    bitmap?.recycle()
                    page.close()
                }
                onProgress?.invoke(position + 1, indices.size)
            }
        } catch (cancelled: CancellationException) {
            paths.forEach { File(it).delete() }
            throw cancelled
        } catch (failure: Exception) {
            paths.forEach { File(it).delete() }
            throw failure
        } finally {
            renderer.close()
        }
        return paths
    }

    private fun openDescriptor(context: Context, source: String): ParcelFileDescriptor? = try {
        when {
            File(source).isAbsolute -> ParcelFileDescriptor.open(File(source), ParcelFileDescriptor.MODE_READ_ONLY)
            source.startsWith("file:", ignoreCase = true) ->
                ParcelFileDescriptor.open(File(URI(source)), ParcelFileDescriptor.MODE_READ_ONLY)
            else -> context.contentResolver.openFileDescriptor(Uri.parse(source), "r")
        }
    } catch (_: Exception) {
        null
    }

    private fun createPageBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            setHasAlpha(false)
        }

    fun getPageCount(context: Context, uri: Uri): Int = getPageCount(context, uri.toString())

    fun getPageCount(context: Context, source: String): Int = try {
        val descriptor = openDescriptor(context, source)
        if (descriptor == null) 0 else {
            val renderer = runCatching { PdfRenderer(descriptor) }
                .onFailure { runCatching { descriptor.close() } }.getOrThrow()
            try { renderer.pageCount } finally { renderer.close() }
        }
    } catch (_: Exception) {
        0
    }
}
