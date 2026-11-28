package com.newoether.agora.ui.chat.message

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.model.markdownAnnotator
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The owner's link checklist (2026-11-23), rendered through the production annotator path: bare
 * URLs written in Chinese prose end where the prose punctuation begins.
 */
class MarkdownBareUrlProseTest {
    private val gh = "https://github.com/ccch1mneyyyy/dsh-Go/releases/tag/v1.2.0"
    private val wiki = "https://en.wikipedia.org/wiki/Foo_(bar)"

    private fun render(markdown: String): AnnotatedString =
        buildCitationAwareMarkdownAnnotatedString(
            content = markdown,
            textNode = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown).children.first(),
            style = TextStyle.Default,
            annotatorSettings = DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = SpanStyle()),
                codeSpanStyle = SpanStyle(),
                annotator = markdownAnnotator(),
            ),
            citationTokens = emptyMap(),
        )

    /** Each link as (visible text, opened URL). */
    private fun links(markdown: String): List<Pair<String, String>> {
        val rendered = render(markdown)
        return rendered.getLinkAnnotations(0, rendered.length).map { range ->
            rendered.text.substring(range.start, range.end) to (range.item as LinkAnnotation.Url).url
        }
    }

    private fun assertBare(markdown: String, vararg urls: String) {
        assertEquals(markdown, urls.map { it to it }, links(markdown))
    }

    @Test
    fun bareUrlsStopAtChineseProsePunctuation() {
        assertBare("全角括号包裹（原始问题）：（$gh）— 高亮和点击都应止于 `0`，`）` 不算链接。", gh)
        assertBare("中文句号收尾：新版在这里 $gh。 — `。` 不算链接。", gh)
        assertBare("中文逗号接后文：https://example.com/a，然后还有字 — `，` 不算链接。", "https://example.com/a")
        assertBare("全角感叹号：https://example.com/b！ — `！` 不算链接。", "https://example.com/b")
        assertBare("全角问号：是这个吗 https://example.com/j？ — `？` 不算链接。", "https://example.com/j")
        assertBare("直角引号：「https://example.com/g」 — `」` 不算链接。", "https://example.com/g")
        assertBare("书名号：《https://example.com/h》 — `》` 不算链接。", "https://example.com/h")
        assertBare("带查询参数的： （https://example.com/k?x=1&y=2） — 参数完整，`）` 被裁。", "https://example.com/k?x=1&y=2")
    }

    @Test
    fun asciiAndFullWidthBracketsOutsideTheUrlAreDropped() {
        assertBare("半角圆括号（不平衡）：(https://example.com/d) — 尾部 `)` 不算链接。", "https://example.com/d")
        assertBare(
            "方括号：［https://example.com/e］ 与 [https://example.com/f] — `］`/`]` 都不算链接。",
            "https://example.com/e",
            "https://example.com/f",
        )
    }

    @Test
    fun balancedBracketsInsideTheUrlSurvive() {
        assertBare("对照：平衡括号要保留：$wiki — 整个含 `(bar)` 都是链接，点开是 Wiki 页面。", wiki)
        assertBare("最难的一条：括号里套平衡括号：（$wiki）。 — 内部 `(bar)` 保留，外层 `）` 和 `。` 都裁掉。", wiki)
        assertBare("行尾裸链接（对照组）： https://example.com/i", "https://example.com/i")
    }

    @Test
    fun explicitLinkKeepsItsLabelAndDestination() {
        val markdown = "显式 Markdown 链接：dsh-Go 1.2.0 下载页在 [这里]($gh)，后面直接跟中文。"
        assertEquals(listOf("这里" to gh), links(markdown))
    }
}
