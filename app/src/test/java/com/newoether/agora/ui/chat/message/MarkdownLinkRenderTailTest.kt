package com.newoether.agora.ui.chat.message

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.model.markdownAnnotator
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real renderer path, not a hand-built AnnotatedString. */
class MarkdownLinkRenderTailTest {
    private val url = "https://github.com/ccch1mneyyyy/dsh-Go/releases/tag/v1.2.0"
    private val blue = Color(0xFF0000FF)

    private fun render(markdown: String) =
        buildCitationAwareMarkdownAnnotatedString(
            content = markdown,
            textNode = MarkdownParser(GFMFlavourDescriptor())
                .buildMarkdownTreeFromString(markdown)
                .children
                .first(),
            style = TextStyle.Default,
            annotatorSettings = DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = SpanStyle(color = blue)),
                codeSpanStyle = SpanStyle(),
                annotator = markdownAnnotator(),
            ),
            citationTokens = emptyMap(),
        )

    @Test
    fun renderedLinkDropsTheFullWidthBracketAndPeriodItNeverOwned() {
        val markdown = "见（$url）。"
        val rendered = render(markdown)
        val urlStart = rendered.text.indexOf(url)
        assertEquals(markdown, rendered.text)

        val bracket = rendered.text.indexOf('）')
        val period = rendered.text.indexOf('。')
        val covering = rendered.getLinkAnnotations(bracket, period + 1)
        assertTrue("link annotations still cover the trailing marks: $covering", covering.isEmpty())
        assertEquals(
            url,
            markdownLinkAt(rendered, urlStart + 4),
        )

        val colored = rendered.spanStyles.filter {
            it.item.color == blue && it.start < period + 1 && it.end > bracket
        }
        assertTrue(
            "link-colored spans still cover the trailing marks: $colored; all spans=" +
                rendered.spanStyles.joinToString { "${it.start}-${it.end}:${it.item.color}" },
            colored.isEmpty(),
        )
    }

    @Test
    fun balancedBracketInsideTheUrlSurvives() {
        val wiki = "https://en.wikipedia.org/wiki/Foo_(bar)"
        val rendered = render(wiki)
        assertEquals(wiki, rendered.text)
        assertEquals(wiki, markdownLinkAt(rendered, 4))
    }
}
