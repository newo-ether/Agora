package com.newoether.agora.ui.chat.message

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownCodeBlockSourceContractTest {
    @Test
    fun `standalone code block provides markdown dimensions before rendering`() {
        val source = File(
            locateMainSourceRoot(),
            "com/newoether/agora/ui/chat/message/MessageBubbleAssets.kt",
        ).readText()
        val body = extractFunctionBody(source, "internal fun ChatMarkdownCodeBlock(")
        val provider = body.indexOf("LocalMarkdownDimens provides markdownDimens()")
        val dimensionsRead = body.indexOf("LocalMarkdownDimens.current")
        val codeBackground = body.indexOf("MarkdownCodeBackground(")

        assertTrue("standalone code blocks must provide default Markdown dimensions", provider >= 0)
        assertTrue(
            "Markdown dimensions must be provided before they are read",
            dimensionsRead >= 0 && provider < dimensionsRead,
        )
        assertTrue(
            "Markdown dimensions must be provided before dimension-dependent elements render",
            codeBackground >= 0 && provider < codeBackground,
        )
        assertFalse("standalone code blocks must not synthesize Markdown", "Markdown(" in body)
        assertTrue("standalone code blocks must continue rendering raw code", "AnnotatedString(code)" in body)
    }

    private fun extractFunctionBody(source: String, signature: String): String {
        val signatureStart = source.indexOf(signature)
        require(signatureStart >= 0) { "Unable to locate $signature" }
        val bodyStart = source.indexOf('{', signatureStart)
        require(bodyStart >= 0) { "Unable to locate body for $signature" }

        var depth = 0
        for (cursor in bodyStart until source.length) {
            when (source[cursor]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, cursor + 1)
                }
            }
        }
        error("Unable to locate closing brace for $signature")
    }

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
