package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.util.DebugLog
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts plain text from a user-supplied document for RAG indexing. Plain text and markdown are
 * read directly; PDFs go through PDFBox-Android's [PDFTextStripper]. Returns null when the content
 * is unsupported or yields no extractable text (e.g. a scanned/image-only PDF).
 */
object DocumentTextExtractor {

    private const val TAG = "DocTextExtractor"
    private var pdfBoxReady = false

    /** Byte ceiling for a single imported document's extracted text (keeps indexing bounded). */
    const val MAX_TEXT_CHARS = 2_000_000

    fun isSupported(mimeType: String?, fileName: String): Boolean =
        route(mimeType, fileName) != Route.UNSUPPORTED

    private enum class Route { TEXT, PDF, UNSUPPORTED }

    private fun route(mimeType: String?, fileName: String): Route {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mt = mimeType?.lowercase().orEmpty()
        return when {
            mt == "application/pdf" || ext == "pdf" -> Route.PDF
            mt.startsWith("text/") ||
                ext in setOf("txt", "md", "markdown", "log", "csv", "json", "xml", "yaml", "yml") ->
                Route.TEXT
            else -> Route.UNSUPPORTED
        }
    }

    /** Extracts text from [input] for the given type. Caller owns closing nothing — stream is read fully. */
    suspend fun extract(
        context: Context,
        input: InputStream,
        mimeType: String?,
        fileName: String,
    ): String? = withContext(Dispatchers.IO) {
        when (route(mimeType, fileName)) {
            Route.TEXT -> input.bufferedReader().use { it.readText() }
                .take(MAX_TEXT_CHARS).trim().ifBlank { null }
            Route.PDF -> extractPdf(context, input)
            Route.UNSUPPORTED -> null
        }
    }

    private fun extractPdf(context: Context, input: InputStream): String? = try {
        if (!pdfBoxReady) {
            PDFBoxResourceLoader.init(context.applicationContext)
            pdfBoxReady = true
        }
        PDDocument.load(input).use { doc ->
            val stripper = PDFTextStripper()
            val text = stripper.getText(doc).take(MAX_TEXT_CHARS).trim()
            text.ifBlank { null } // image-only / scanned PDFs extract nothing
        }
    } catch (e: Exception) {
        DebugLog.w(TAG, "PDF extraction failed: ${e.javaClass.simpleName}")
        null
    }
}
