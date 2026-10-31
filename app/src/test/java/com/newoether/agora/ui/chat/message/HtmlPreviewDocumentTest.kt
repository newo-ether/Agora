package com.newoether.agora.ui.chat.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlPreviewDocumentTest {
    @Test
    fun allowsOnlyStaticHttpsLibrariesAndNumericMapTiles() {
        listOf(
            "https://cdnjs.cloudflare.com/ajax/libs/three.js/0.170.0/three.min.js",
            "https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.js",
            "https://unpkg.com/pkg@1.0.0/module.mjs",
            "https://esm.sh/pkg@1.0.0/style.css",
            "https://cdn.jsdelivr.net/npm/pkg/font.woff",
            "https://unpkg.com/pkg/font.woff2",
            "https://cdnjs.cloudflare.com/fonts/font.ttf",
            "https://tile.openstreetmap.org/12/1234/2345.png",
        ).forEach { assertTrue("Rejected approved resource: $it", allowedHtmlResource(it)) }

        listOf(
            "http://cdn.jsdelivr.net/npm/pkg/index.js",
            "https://cdn.jsdelivr.net:444/npm/pkg/index.js",
            "https://user:secret@cdn.jsdelivr.net/npm/pkg/index.js",
            "https://cdn.jsdelivr.net/npm/pkg/index.js?token=secret",
            "https://cdn.jsdelivr.net/npm/pkg/index.js#fragment",
            "https://cdn.jsdelivr.net.evil.example/index.js",
            "https://evil.example/cdn.jsdelivr.net/index.js",
            "https://127.0.0.1/index.js",
            "https://cdn.jsdelivr.net/npm/pkg",
            "https://unpkg.com/pkg/index.html",
            "https://esm.sh/pkg/image.png",
            "https://cdnjs.cloudflare.com/payload.svg",
            "https://tile.openstreetmap.org/1/2/3.png?secret=x",
            "https://a.tile.openstreetmap.org/1/2/3.png",
            "https://tile.openstreetmap.org/1/two/3.png",
            "https://tile.openstreetmap.org/1/2/3.jpg",
            "https://tile.openstreetmap.org/1/2/3.png/extra",
            "file:///tmp/index.js",
            "content://provider/index.js",
            "javascript:alert(1)",
            "//cdn.jsdelivr.net/index.js",
        ).forEach { assertFalse("Accepted forbidden resource: $it", allowedHtmlResource(it)) }
    }

    @Test
    fun tokenizerSkipsCommentsRawTextAndNonSourceAttributes() {
        val source = """
            <!-- <img src="shell://Quantum/F:/comment.png"> -->
            <script>const text = '<img src="shell://Quantum/F:/script.png">';</script>
            <style>p::after { content: '<img src="shell://Quantum/F:/style.png">'; }</style>
            <textarea><img src="shell://Quantum/F:/textarea.png"></textarea>
            <title><img src="shell://Quantum/F:/title.png"></title>
            <img data-src="shell://Quantum/F:/lazy.png" srcset="shell://Quantum/F:/set.png 2x">
            <img-custom src='shell://Quantum/F:/custom.png'>
            <img:custom src='shell://Quantum/F:/namespaced.png'>
            <IMG alt="x > y and &gt;" data-src="ignored" SRC = 'shell://Quantum/F:/real.png'>
        """.trimIndent()
        val seen = mutableListOf<String>()

        val rewritten = rewriteHtmlPreviewImages(source) {
            seen += it
            "https://capability.invalid/image/0"
        }

        assertEquals(listOf("shell://Quantum/F:/real.png"), seen)
        assertEquals(
            source.replace("SRC = 'shell://Quantum/F:/real.png'", "SRC = 'https://capability.invalid/image/0'"),
            rewritten,
        )
    }

    @Test
    fun quotedSourceEntitiesAreDecodedBeforeResolvingTheImagePath() {
        val seen = mutableListOf<String>()
        val source = """<img src="shell://Quantum/F:/a&gt;b&amp;c&#32;d.png">"""

        val rewritten = rewriteHtmlPreviewImages(source) {
            seen += it
            "https://capability.invalid/image/0"
        }

        assertEquals(listOf("shell://Quantum/F:/a>b&c d.png"), seen)
        assertEquals("""<img src="https://capability.invalid/image/0">""", rewritten)
    }

    @Test
    fun unquotedOrDuplicateSourceCannotGrantAnUnusedImageCapability() {
        val source = """
            <img src=shell://Quantum/F:/unquoted.png src="shell://Quantum/F:/ignored.png">
            <img src="https://example.com/remote.png" src="shell://Quantum/F:/duplicate.png">
            <img src="shell://Quantum/F:/first.png" src="shell://Quantum/F:/second.png">
        """.trimIndent()
        val document = HtmlPreviewDocument(source)

        assertEquals(listOf("shell://Quantum/F:/first.png"), document.images.values.toList())
        assertTrue(document.html.contains("src=shell://Quantum/F:/unquoted.png"))
        assertTrue(document.html.contains("src=\"shell://Quantum/F:/duplicate.png\""))
        assertTrue(document.html.contains("src=\"shell://Quantum/F:/second.png\""))
    }

    @Test
    fun deduplicatesAndCapsImageCapabilitiesWithoutChangingTheOriginalSource() {
        val source = buildString {
            append("<h1>Original</h1>\n")
            repeat(HtmlPreviewDocument.MAX_IMAGES + 2) { index ->
                append("<img src=\"shell://Quantum/F:/image-$index.png\">\n")
            }
            append("<img src='shell://Quantum/F:/image-0.png'>")
        }
        val original = source.toByteArray(Charsets.UTF_8)
        val document = HtmlPreviewDocument(source)
        val first = document.images.entries.first()

        assertEquals(HtmlPreviewDocument.MAX_IMAGES, document.images.size)
        assertEquals("shell://Quantum/F:/image-0.png", first.value)
        assertEquals(2, Regex(Regex.escape(first.key)).findAll(document.html).count())
        assertTrue(document.html.contains("shell://Quantum/F:/image-16.png"))
        assertTrue(document.html.contains("shell://Quantum/F:/image-17.png"))
        assertTrue(original.contentEquals(source.toByteArray(Charsets.UTF_8)))
        assertTrue(source.contains("shell://Quantum/F:/image-0.png"))
        assertNotEquals(document.origin, HtmlPreviewDocument(source).origin)
    }

    @Test
    fun rejectsOversizedSourceAndKeepsNonShellImageMarkupIntact() {
        assertThrows(IllegalArgumentException::class.java) {
            HtmlPreviewDocument("x".repeat(HtmlPreviewDocument.MAX_SOURCE_CHARS + 1))
        }
        val source = """<img src="https://example.com/a.png"><img src="shell://Quantum/%GG.png">"""
        val document = HtmlPreviewDocument(source)
        assertEquals(source, document.html)
        assertTrue(document.images.isEmpty())
    }

    @Test
    fun languageRecognitionIsLimitedToTheTwoHtmlFenceKinds() {
        listOf("html", "HTML", "html-preview", "HTML-PREVIEW").forEach {
            assertTrue(htmlPreviewLanguage(it))
        }
        listOf(null, "", "xml", "javascript", "html-preview-extra", "html preview").forEach {
            assertFalse(htmlPreviewLanguage(it))
        }
    }
}
