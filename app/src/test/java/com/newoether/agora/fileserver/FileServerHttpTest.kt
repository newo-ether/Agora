package com.newoether.agora.fileserver

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileServerHttpTest {

    @Test
    fun safeRelativePathStripsQueryDotAndParentSegments() {
        assertEquals("a/b", FileServerHttp.safeRelativePath("/a/b?x=1"))
        assertEquals("a/b", FileServerHttp.safeRelativePath("/a/./b"))
        // ".." segments are dropped entirely so a traversal cannot escape the root.
        assertEquals("etc/passwd", FileServerHttp.safeRelativePath("/../../etc/passwd"))
        assertEquals("", FileServerHttp.safeRelativePath("/"))
        assertEquals("", FileServerHttp.safeRelativePath("/./../"))
    }

    @Test
    fun guessMimeCoversCommonTypesAndFallsBack() {
        assertEquals("text/html", FileServerHttp.guessMime("index.html"))
        assertEquals("image/png", FileServerHttp.guessMime("a.PNG"))
        assertEquals("application/pdf", FileServerHttp.guessMime("doc.pdf"))
        assertEquals("application/vnd.android.package-archive", FileServerHttp.guessMime("app.apk"))
        assertEquals("application/octet-stream", FileServerHttp.guessMime("mystery.xyz"))
        assertEquals("application/octet-stream", FileServerHttp.guessMime("noext"))
    }

    @Test
    fun formatSizeScalesUnits() {
        assertEquals("512 B", FileServerHttp.formatSize(512))
        assertEquals("1 KB", FileServerHttp.formatSize(1024))
        assertEquals("1.0 MB", FileServerHttp.formatSize(1024L * 1024))
        assertEquals("1.0 GB", FileServerHttp.formatSize(1024L * 1024 * 1024))
    }

    @Test
    fun directoryListingHtmlEscapesStructureAndShowsParentLink() {
        val dir = File("/tmp/share")
        val files = listOf(File("/tmp/share/sub"), File("/tmp/share/readme.txt"))
        val html = FileServerHttp.directoryListingHtml(dir, "/share", files)
        assertTrue(html.contains("Agora File Server"))
        assertTrue(html.contains("readme.txt"))
        // A non-root path offers an "up" link.
        assertTrue(html.contains("\u2B06\uFE0F .."))
        assertTrue(html.contains("multipart/form-data"))
        assertTrue(html.contains("2 items"))
    }

    @Test
    fun directoryListingHtmlOmitsParentLinkAtRoot() {
        val html = FileServerHttp.directoryListingHtml(File("/tmp/share"), "/", emptyList())
        assertFalse(html.contains(".."))
        assertTrue(html.contains("0 items"))
    }

    @Test
    fun pinPromptRendersLockGlyphNotLiteralEscape() {
        val out = java.io.ByteArrayOutputStream()
        val buffered = java.io.BufferedOutputStream(out)
        FileServerHttp.sendPinPrompt(buffered)
        buffered.flush()
        val body = out.toString(Charsets.UTF_8.name())
        assertTrue("title present", body.contains("PIN Required"))
        // The raw-string lock must be an HTML entity (or real glyph), never the literal \\u escape.
        assertFalse("no literal unicode escape leaks", body.contains("\\uD83D"))
        assertTrue("lock entity rendered", body.contains("&#128274;"))
        assertTrue("401 status", body.startsWith("HTTP/1.1 401"))
    }
}
