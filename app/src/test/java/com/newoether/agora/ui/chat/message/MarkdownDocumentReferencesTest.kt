package com.newoether.agora.ui.chat.message

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.*
import org.junit.Test

class MarkdownDocumentReferencesTest {
    private val flavour = GFMFlavourDescriptor()
    private fun parse(source: String) = MarkdownParser(flavour).buildMarkdownTreeFromString(source)

    @Test fun laterSplitDefinitionsResolveStableBodyWithoutReplacingSourceOrTree() {
        val document = IncrementalMarkdownDocument(flavour)
        val body = "Answer [Z1].\n\n"
        val first = document.update(body, body, true)
        val stable = first.stableBlocks.single()
        val incomplete = body + "[Z1]:\n"
        assertTrue(document.update(incomplete, incomplete, true).references.isEmpty())
        // A non-append rewrite resets, then a destination can grow inside the live definition.
        val partial = body + "[Z1]: https://"
        val reset = document.update(partial, partial, true)
        val resetStable = reset.stableBlocks.single()
        assertEquals("https://", MarkdownDocumentReferenceHandler(reset.references).find("[z1]"))
        val complete = partial + "example.com/path\n"
        val resolved = document.update(complete, complete, true)
        assertSame(resetStable, resolved.stableBlocks.single())
        assertSame(resetStable.root, resolved.stableBlocks.single().root)
        assertEquals("https://example.com/path", MarkdownDocumentReferenceHandler(resolved.references).find("[Z1]"))
        assertEquals(complete, resolved.stableBlocks.joinToString("") { it.sourceContent } + resolved.tail)
        assertEquals(body, stable.sourceContent)
        val terminal = document.update(complete, complete, false)
        assertSame(resolved.liveBlock, terminal.liveBlock)
        assertEquals(resolved.references, terminal.references)
    }

    @Test fun appendOnlyDefinitionArrivalKeepsEarlierSnapshotImmutableAndFirstDefinitionWins() {
        val document = IncrementalMarkdownDocument(flavour)
        val body = "Answer [Z1].\n\n"
        val before = document.update(body, body, true)
        val first = body + "[Z1]: <https://example.com/a?x=1&y=2>\n\n"
        val defined = document.update(first, first, true)
        val duplicate = first + "[z1]: https://wrong.example\n"
        val after = document.update(duplicate, duplicate, true)
        assertSame(before.stableBlocks.single(), after.stableBlocks.first())
        assertTrue(before.references.isEmpty())
        val handler = MarkdownDocumentReferenceHandler(after.references)
        assertEquals("https://example.com/a?x=1&y=2", handler.find("[Z1]"))
        handler.store("[Z1]", "https://inline.example")
        assertEquals("https://example.com/a?x=1&y=2", handler.find("[z1]"))
        assertEquals(defined.references, after.references)
        val replacement = "Different [Z1]."
        assertTrue(document.update(replacement, replacement, false).references.isEmpty())
    }

    @Test fun labelsNormalizeWhitespaceAndEscapesWhileFencedDefinitionsStayCode() {
        val source = "[A   B]: https://example.com/a\\(b\\)\n\n```\n[ignored]: https://wrong.example\n```"
        val handler = MarkdownDocumentReferenceHandler(markdownReferenceDefinitions(parse(source), source))
        assertEquals("https://example.com/a(b)", handler.find("[a\n b]"))
        assertEquals("", handler.find("[ignored]"))
    }

    @Test fun definitionsAddNoNodesOrTrailingSeparatorsButNormalParagraphSpacingRemains() {
        val body = "First.\n\nSecond."
        val baseline = markdownVisibleBlocks(parse(body))
        val definitions = (1..48).joinToString("\n") { "[Z$it]: https://example.com/$it" }
        val source = "$body\n\n$definitions\n"
        val nodes = markdownVisibleBlocks(parse(source))
        assertEquals(baseline.map { it.type }, nodes.map { it.type })
        assertEquals(2, nodes.count { it.type == MarkdownElementTypes.PARAGRAPH })
        assertEquals(2, nodes.count { it.type == MarkdownTokenTypes.EOL })
        assertTrue(markdownVisibleBlocks(parse(definitions)).isEmpty())
    }

    @Test fun nestedDefinitionsAreMetadataAndRenderProjectionPreservesOriginalParentsAndOffsets() {
        val source = "> Answer [Z1].\n>\n> [Z1]: https://example.com\n"
        val root = parse(source)
        val quote = root.children.first()
        val originalChildren = quote.children.toList()
        val visible = markdownVisibleBlocks(root).single()
        assertEquals("https://example.com", MarkdownDocumentReferenceHandler(markdownReferenceDefinitions(root, source)).find("[Z1]"))
        assertFalse(visible.containsDefinition())
        assertEquals(originalChildren, quote.children)
        val paragraph = visible.children.first { it.type == MarkdownElementTypes.PARAGRAPH }
        assertSame(quote, paragraph.parent)
        assertEquals(2, paragraph.startOffset)
        assertFalse(visible.children.last().type == MarkdownTokenTypes.EOL)
        assertTrue(markdownVisibleBlocks(parse("> [Z1]: https://example.com\n")).isEmpty())
        assertTrue(markdownVisibleBlocks(parse("- [Z1]: https://example.com\n")).isEmpty())
    }

    private fun ASTNode.containsDefinition(): Boolean =
        type == MarkdownElementTypes.LINK_DEFINITION || children.any { it.containsDefinition() }
}
