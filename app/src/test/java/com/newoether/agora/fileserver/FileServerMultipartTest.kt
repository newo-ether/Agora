package com.newoether.agora.fileserver

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileServerMultipartTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun body(boundary: String, vararg parts: Pair<String, String>): File {
        val sb = StringBuilder()
        for ((name, content) in parts) {
            sb.append(boundary).append("\r\n")
            sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\n")
            sb.append("Content-Type: application/octet-stream\r\n\r\n")
            sb.append(content).append("\r\n")
        }
        sb.append(boundary).append("--\r\n")
        return temp.newFile().apply { writeText(sb.toString()) }
    }

    @Test
    fun parsesMultipleFilePartsIntoUploadDir() {
        val boundary = "----agoraBoundary123"
        val spool = body(boundary, "a.txt" to "hello", "b.txt" to "world")
        val dest = temp.newFolder("uploads")

        val saved = FileServerMultipart.parseInto(spool, boundary, dest)

        assertEquals(2, saved)
        assertEquals("hello", File(dest, "a.txt").readText())
        assertEquals("world", File(dest, "b.txt").readText())
    }

    @Test
    fun sanitizesTraversalFilenameToLastSegment() {
        val boundary = "----agoraBoundaryTrav"
        val spool = body(boundary, "../../evil.sh" to "payload")
        val dest = temp.newFolder("uploads2")

        val saved = FileServerMultipart.parseInto(spool, boundary, dest)

        assertEquals(1, saved)
        // Written as the last path segment inside the upload dir, never outside it.
        assertTrue(File(dest, "evil.sh").exists())
        assertFalse(File(dest.parentFile, "evil.sh").exists())
    }

    @Test
    fun createsUploadDirectoryWhenMissing() {
        val boundary = "----agoraBoundaryMk"
        val spool = body(boundary, "note.txt" to "x")
        val dest = File(temp.root, "nested/new/dir")
        assertFalse(dest.exists())

        val saved = FileServerMultipart.parseInto(spool, boundary, dest)

        assertEquals(1, saved)
        assertEquals("x", File(dest, "note.txt").readText())
    }

    @Test
    fun preservesExactBinaryContentAcrossParsing() {
        val boundary = "----agoraBoundaryBin"
        val content = (0..255).joinToString("") { it.toChar().toString() }
        val spool = body(boundary, "data.bin" to content)
        val dest = temp.newFolder("uploads3")

        FileServerMultipart.parseInto(spool, boundary, dest)

        assertEquals(content, File(dest, "data.bin").readText())
    }
}
