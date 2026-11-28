package com.newoether.agora.ui.chat.message

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlPreviewFenceTest {
    @Test
    fun openHtmlFencesNeverCompleteEvenAfterTheStreamTerminates() {
        listOf("```html-preview\n<button>Run</button>", "~~~html\n<p>Source</p>\n").forEach { source ->
            val fence = fence(source)
            assertFalse(completedHtmlFence(source, fence, streaming = true))
            assertFalse(completedHtmlFence(source, fence, streaming = false))
        }
    }

    @Test
    fun closingFenceAtLiveEofWaitsUntilItsLineIsCommitted() {
        listOf("```", "~~~~").forEach { delimiter ->
            val source = "$delimiter html-preview\n<button>Run</button>\n$delimiter"
            val fence = fence(source)
            assertTrue(completedHtmlFence(source, fence, streaming = false))
            assertFalse(completedHtmlFence(source, fence, streaming = true))

            val committed = source + "\n"
            assertTrue(completedHtmlFence(committed, fence(committed), streaming = true))
        }
    }

    @Test
    fun carriageReturnRemainsFenceContentInTheExistingMarkdownFlavour() {
        val source = "```html-preview\n<p>Content</p>\n```\r\n"
        val fence = fence(source)
        // This GFM parser does not normalize CRLF before looking for a closing delimiter.
        assertEquals(null, fence.findChildOfType(MarkdownTokenTypes.CODE_FENCE_END))
        assertFalse(completedHtmlFence(source, fence, streaming = true))
        assertFalse(completedHtmlFence(source, fence, streaming = false))
    }

    @Test
    fun textAfterAnUncommittedClosingDelimiterMakesItAnOpenFenceAgain() {
        val source = "```html-preview\n<p>Content</p>\n```not-a-close\n"
        val fence = fence(source)
        assertFalse(completedHtmlFence(source, fence, streaming = true))
        assertFalse(completedHtmlFence(source, fence, streaming = false))
    }

    @Test
    fun followingParagraphDoesNotRevokeACommittedClosingFence() {
        val source = "Before\n\n```html-preview\n<p>HTML</p>\n```\n\nAfter"
        val fence = fence(source)
        val originalStart = fence.startOffset
        val originalEnd = fence.endOffset
        val originalSlice = source.substring(originalStart, originalEnd)

        assertTrue(completedHtmlFence(source, fence, streaming = true))
        assertTrue(completedHtmlFence(source, fence, streaming = false))
        assertEquals(originalStart, fence.startOffset)
        assertEquals(originalEnd, fence.endOffset)
        assertEquals(originalSlice, source.substring(fence.startOffset, fence.endOffset))
        assertEquals("html-preview", fence.findChildOfType(MarkdownTokenTypes.FENCE_LANG)!!.let {
            source.substring(it.startOffset, it.endOffset).trim()
        })
    }

    @Test
    fun nestedQuoteUsesTheSameJetBrainsFenceOffsets() {
        val source = "> ```html-preview\n> <p>Quoted</p>\n> ```\n\nTail"
        val fence = fence(source)
        assertTrue(completedHtmlFence(source, fence, streaming = true))
        assertTrue(completedHtmlFence(source, fence, streaming = false))
    }

    private fun fence(source: String): ASTNode {
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        fun find(node: ASTNode): ASTNode? {
            if (node.type == MarkdownElementTypes.CODE_FENCE) return node
            return node.children.firstNotNullOfOrNull(::find)
        }
        return requireNotNull(find(root)) { "Fixture did not parse as a Markdown fence: $source" }
    }
}
