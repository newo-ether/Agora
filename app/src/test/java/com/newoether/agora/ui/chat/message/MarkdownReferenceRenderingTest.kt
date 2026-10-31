package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownReferenceRenderingTest {
    @get:Rule val compose = createComposeRule()
    private enum class Mode { MESSAGE, PREPARED, LAZY }
    private val content = mutableStateOf("")
    private val streaming = mutableStateOf(false)
    private val opened = mutableListOf<String>()
    private var lazyState: LazyListState? = null
    private val definitions = (1..48).joinToString("\n") { "[Z$it]: https://example.com/$it" }

    @Test fun streamingDefinitionsResolveStableTextAndKeepHeightThroughTerminalization() {
        content.value = "Answer [Z1].\n\n"
        streaming.value = true
        host()
        awaitText("Answer")
        val baseline = height()
        compose.runOnIdle { content.value += "[Z1]: https://example.com/" }
        awaitLink("https://example.com/")
        compose.runOnIdle { content.value = "Answer [Z1].\n\n$definitions\n" }
        awaitLink("https://example.com/1")
        assertEquals(baseline, height(), 0.1f)
        clickReference("https://example.com/1")
        compose.runOnIdle { streaming.value = false }
        compose.waitForIdle()
        assertEquals(baseline, height(), 0.1f)
        awaitLink("https://example.com/1")
        assertNoDefinitionText()
    }

    @Test fun historicalMessageKeepsParagraphSpacingWith48Definitions() = verifyStatic(Mode.MESSAGE)
    @Test fun preparedMessageUsesDefinitionsWithoutRenderingTheirRows() = verifyStatic(Mode.PREPARED)
    @Test fun virtualizedDetailSkipsDefinitionItemsAndKeepsParagraphSpacing() = verifyStatic(Mode.LAZY)

    @Test fun duplicateDefinitionUsesFirstDestinationAndInlineLinkCannotReplaceIt() {
        content.value = "Answer [Z1] and [inline](https://inline.example).\n\n" +
            "[Z1]: https://first.example\n[Z1]: https://second.example\n"
        host()
        awaitLink("https://first.example")
        clickReference("https://first.example")
        assertNoDefinitionText()
    }

    @Test fun nestedQuoteAndListDefinitionsStayInvisibleAndResolveBodyReferences() {
        content.value = "Answer [Z1].\n\n> Quoted.\n>\n> [Z1]: https://example.com/quote\n\n" +
            "- Listed.\n\n  [Z2]: https://example.com/list\n"
        host()
        awaitLink("https://example.com/quote")
        assertNoDefinitionText()
        assertTrue(texts().any { "Quoted." in it.text })
        assertTrue(texts().any { "Listed." in it.text })
    }

    @Test fun definitionLookingTextInFenceRemainsVisibleCodeAndDoesNotDefineLink() {
        content.value = "Answer [Z1].\n\n```\n[Z1]: https://example.com/code\n```"
        host()
        awaitText("https://example.com/code")
        assertTrue(texts().any { "[Z1]: https://example.com/code" in it.text })
        assertTrue(texts().all { it.getLinkAnnotations(0, it.length).isEmpty() })
    }

    private fun verifyStatic(mode: Mode) {
        val body = "Answer [Z1].\n\nSecond paragraph."
        content.value = body
        host(mode)
        awaitText("Second paragraph.")
        val baseline = height()
        val secondTop = compose.onNodeWithText("Second paragraph.", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top
        val items = lazyState?.layoutInfo?.totalItemsCount
        compose.runOnIdle { content.value = "$body\n\n$definitions\n" }
        awaitLink("https://example.com/1")
        assertEquals(baseline, height(), 0.1f)
        assertEquals(secondTop, compose.onNodeWithText("Second paragraph.", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top, 0.1f)
        if (mode == Mode.LAZY) assertEquals(items, lazyState?.layoutInfo?.totalItemsCount)
        clickReference("https://example.com/1")
        assertNoDefinitionText()
    }

    private fun host(mode: Mode = Mode.MESSAGE) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened += uri }
                }) {
                    val text = content.value
                    val prepared = remember(text, mode) {
                        if (mode == Mode.PREPARED) mapOf(text to State.Success(
                            node = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text),
                            content = text, linksLookedUp = false,
                            referenceLinkHandler = ReferenceLinkHandlerImpl(),
                        )) else emptyMap()
                    }
                    val assets = rememberChatMarkdownAssets(
                        textColor = MaterialTheme.colorScheme.onSurface, preparedMarkdown = prepared,
                    )
                    Box(Modifier.width(360.dp).testTag("message")) {
                        if (mode == Mode.LAZY) {
                            val list = rememberLazyListState().also { lazyState = it }
                            LazyMarkdownTextContent(text, assets.renderContext, list,
                                modifier = Modifier.width(360.dp).heightIn(max = 600.dp))
                        } else {
                            StreamingMarkdownMessage(text, streaming.value, assets.renderContext)
                        }
                    }
                }
            }
        }
    }

    private fun texts(): List<AnnotatedString> = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true,
    ).fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }

    private fun awaitText(fragment: String) = compose.waitUntil(10_000) {
        texts().any { fragment in it.text }
    }

    private fun awaitLink(url: String) = compose.waitUntil(10_000) {
        texts().any { text -> text.getLinkAnnotations(0, text.length).any { (it.item as? LinkAnnotation.Url)?.url == url } }
    }

    private fun height(): Float {
        compose.waitForIdle()
        return compose.onNodeWithTag("message").fetchSemanticsNode().boundsInRoot.height
    }

    private fun clickReference(url: String) {
        val node = compose.onNodeWithText("Answer", substring = true, useUnmergedTree = true)
        val annotated = node.fetchSemanticsNode().config[SemanticsProperties.Text].first()
        val reference = annotated.getLinkAnnotations(0, annotated.length)
            .first { (it.item as? LinkAnnotation.Url)?.url == url }
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val point = layouts.single().getBoundingBox(reference.start).center
        node.performTouchInput { click(point) }
        compose.runOnIdle { assertEquals(url, opened.lastOrNull()) }
    }

    private fun assertNoDefinitionText() {
        assertFalse(texts().any { "]: https://" in it.text })
    }
}
