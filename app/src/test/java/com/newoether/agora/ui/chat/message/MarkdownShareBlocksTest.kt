package com.newoether.agora.ui.chat.message

import org.junit.Assert.*
import org.junit.Test

class MarkdownShareBlocksTest {
    private val source="Here is your draft.\n\n# Plan\n\nKeep **bold** and [link](https://example.com).\n\n- First\n- Second\n\n```md\n# literal\n\n- code\n```\n\nWould you like more?"
    @Test fun allSelectionIsByteForByteOriginalAndOmitsNoMarkdown() {
        val blocks=markdownShareBlocks(source)
        assertEquals(source,selectedMarkdown(source,blocks,blocks.indices.toSet(),emptyMap()))
        assertTrue(blocks.any{source.substring(it.start,it.end).startsWith("```md")})
        assertEquals(1,blocks.count{it.headingLevel==1})
        assertTrue(blocks.zipWithNext().all{(a,b)->a.end<=b.start})
    }
    @Test fun prefixAndSuffixCanBeRemovedWithoutTouchingCodeOrListSyntax() {
        val blocks=markdownShareBlocks(source)
        val selected=(1 until blocks.lastIndex).toSet()
        val text=selectedMarkdown(source,blocks,selected,emptyMap())
        assertFalse(text.contains("Here is"));assertFalse(text.contains("Would you"))
        assertTrue(text.startsWith("# Plan"));assertTrue(text.contains("- First\n- Second"))
        assertTrue(text.contains("```md\n# literal\n\n- code\n```"))
    }
    @Test fun sectionsStopAtPeerHeadingAndEditsAffectOnlyTheCopy() {
        val text="# One\n\nA\n\n## Child\n\nB\n\n# Two\n\nC"
        val blocks=markdownShareBlocks(text)
        assertEquals((0..3).toList(),markdownSectionIndices(blocks,0).toList())
        assertEquals((2..3).toList(),markdownSectionIndices(blocks,2).toList())
        assertEquals("Changed",selectedMarkdown(text,blocks,setOf(1),mapOf(1 to "Changed")))
        assertEquals("",selectedMarkdown(text,blocks,emptySet(),emptyMap()))
        assertEquals("A",text.substring(blocks[1].start,blocks[1].end))
    }
    @Test fun keepsTablesLinksEscapesAndCrLfAcrossSelection() {
        val text="Intro\r\n\r\n| A | B |\r\n|---|---|\r\n| x | y |\r\n\r\n\\*literal\\* [ref][id]\r\n\r\n[id]: https://example.com\r\n"
        val blocks=markdownShareBlocks(text)
        assertEquals(text,selectedMarkdown(text,blocks,blocks.indices.toSet(),emptyMap()))
        val selected=selectedMarkdown(text,blocks,(1..blocks.lastIndex).toSet(),emptyMap())
        assertTrue("blocks=$blocks selected=$selected",selected.contains("|---|---|"));assertTrue("blocks=$blocks selected=$selected",selected.contains("[id]: https://example.com"));assertFalse(selected.startsWith("Intro"))
    }
}
