package com.newoether.agora.ui.chat.message

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgoraMarkdownFlavourTest {
    private fun parse(text: String): ASTNode =
        MarkdownParser(AgoraMarkdownFlavour()).buildMarkdownTreeFromString(text)

    private fun children(node: ASTNode): List<ASTNode> = node.children

    private fun types(node: ASTNode): List<String> = buildList {
        add(node.type.toString())
        children(node).forEach { addAll(types(it)) }
    }

    @Test
    fun `CJK punctuation closes strong emphasis before more Chinese text`() {
        // The reported shape: the closing run follows CJK punctuation and a CJK letter follows it.
        assertTrue(types(parse("比如**中文。**不会")).contains(MarkdownElementTypes.STRONG.toString()))
        assertTrue(types(parse("**一句中文。**不会")).contains(MarkdownElementTypes.STRONG.toString()))
        assertTrue(types(parse("前**中文，**后")).contains(MarkdownElementTypes.STRONG.toString()))
    }

    @Test
    fun `CJK punctuation closes emphasis and opens the next run`() {
        val tree = types(parse("**中文。**不会**下一句**。"))
        assertEquals(2, tree.count { it == MarkdownElementTypes.STRONG.toString() })
    }

    @Test
    fun `Latin intra-word delimiters and arithmetic stay literal`() {
        assertFalse(types(parse("snake_case_name")).contains(MarkdownElementTypes.EMPH.toString()))
        assertFalse(types(parse("2 * 3 * 4")).contains(MarkdownElementTypes.EMPH.toString()))
        // A Latin letter after the run is not the CJK continuation the fix targets.
        assertFalse(types(parse("比如**中文。**abc")).contains(MarkdownElementTypes.STRONG.toString()))
    }

    @Test
    fun `ordinary Latin emphasis still pair`() {
        assertTrue(types(parse("**bold** text")).contains(MarkdownElementTypes.STRONG.toString()))
        assertTrue(types(parse("*italic* text")).contains(MarkdownElementTypes.EMPH.toString()))
    }
}
