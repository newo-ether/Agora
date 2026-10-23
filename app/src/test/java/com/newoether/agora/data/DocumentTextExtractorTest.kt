package com.newoether.agora.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DocumentTextExtractorTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun supportsTextMarkdownAndPdfByExtension() {
        assertTrue(DocumentTextExtractor.isSupported(null, "notes.txt"))
        assertTrue(DocumentTextExtractor.isSupported(null, "README.md"))
        assertTrue(DocumentTextExtractor.isSupported(null, "data.csv"))
        assertTrue(DocumentTextExtractor.isSupported(null, "paper.pdf"))
        assertTrue(DocumentTextExtractor.isSupported("application/pdf", "noext"))
        assertTrue(DocumentTextExtractor.isSupported("text/plain", "noext"))
    }

    @Test
    fun rejectsUnsupportedTypes() {
        assertFalse(DocumentTextExtractor.isSupported("image/png", "photo.png"))
        assertFalse(DocumentTextExtractor.isSupported(null, "archive.zip"))
        assertFalse(DocumentTextExtractor.isSupported("application/octet-stream", "blob.bin"))
    }

    @Test
    fun extractsPlainTextDirectly() = runTest {
        val body = "First line about scanning.\nSecond line about embeddings."
        val text = DocumentTextExtractor.extract(
            app, ByteArrayInputStream(body.toByteArray()), "text/plain", "notes.txt",
        )
        assertEquals(body, text)
    }

    @Test
    fun blankTextYieldsNull() = runTest {
        val text = DocumentTextExtractor.extract(
            app, ByteArrayInputStream("   \n  ".toByteArray()), "text/plain", "empty.txt",
        )
        assertNull(text)
    }

    @Test
    fun unsupportedTypeExtractsNull() = runTest {
        val text = DocumentTextExtractor.extract(
            app, ByteArrayInputStream(byteArrayOf(1, 2, 3)), "image/png", "photo.png",
        )
        assertNull(text)
    }

    @Test
    fun extractsTextFromAGeneratedPdf() = runTest {
        val marker = "AgoraRagPdfExtractionMarker"
        val pdfBytes = buildOnePagePdf(marker)
        val text = DocumentTextExtractor.extract(
            app, ByteArrayInputStream(pdfBytes), "application/pdf", "fixture.pdf",
        )
        assertTrue("expected PDF text to contain the marker, got: $text", text?.contains(marker) == true)
    }

    private fun buildOnePagePdf(text: String): ByteArray {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(app)
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 12f)
            cs.newLineAtOffset(72f, 700f)
            cs.showText(text)
            cs.endText()
        }
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }
}
