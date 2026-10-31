package com.newoether.agora.ui.chat.message

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.runBlocking
import androidx.test.core.app.ApplicationProvider
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.newoether.agora.R
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.ChatType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h900dp-mdpi",
    shadows = [HtmlPreviewVisualStateShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("DEPRECATION")
class HtmlPreviewCodeContentTest {
    @get:Rule val compose = createComposeRule()
    private val code = mutableStateOf("<button>Count &gt; 0</button>\n<img src=\"shell://Quantum/F:/original.png\">")
    private val language = mutableStateOf<String?>("html-preview")
    private val eligible = mutableStateOf(true)
    private val searching = mutableStateOf(false)
    private val streaming = mutableStateOf(false)
    private lateinit var drawingView: View
    private lateinit var clipboard: ClipboardManager
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val runLabel get() = context.getString(R.string.html_preview_run)
    private val sourceLabel get() = context.getString(R.string.html_preview_source)
    private val copyLabel get() = context.getString(R.string.copy)
    private val moreLabel get() = context.getString(R.string.more)

    @Test
    fun htmlPreviewAutomaticallyMountsTheBrowserAndSourceToggleReleasesIt() {
        host()
        compose.onNodeWithTag(SOURCE).assertDoesNotExist()
        compose.onNodeWithContentDescription(sourceLabel).assertIsEnabled()
        compose.onNodeWithContentDescription(runLabel).assertDoesNotExist()
        assertActionOrder(listOf(sourceLabel, copyLabel, context.getString(R.string.html_preview_save), moreLabel)
            .map { compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot })
        val browser = awaitBrowser()

        compose.onNodeWithContentDescription(sourceLabel).performClick()

        compose.onNodeWithTag(SOURCE).assertIsDisplayed().assertTextEquals(code.value)
        compose.onNodeWithContentDescription(runLabel).assertIsEnabled()
        compose.runOnIdle {
            assertTrue(previewViews().isEmpty())
            assertTrue(shadowOf(browser).wasDestroyCalled())
        }
    }

    @Test
    fun ordinaryHtmlStartsAsSourceAndRequiresRunBeforeMountingTheBrowser() {
        language.value = "html"
        host()
        compose.onNodeWithTag(SOURCE).assertIsDisplayed().assertTextEquals(code.value)
        compose.onNodeWithContentDescription(runLabel).assertIsEnabled()
        compose.onNodeWithContentDescription(sourceLabel).assertDoesNotExist()
        compose.runOnIdle { assertTrue(previewViews().isEmpty()) }

        compose.onNodeWithContentDescription(runLabel).performClick()

        compose.onNodeWithTag(SOURCE).assertDoesNotExist()
        compose.onNodeWithContentDescription(sourceLabel).assertIsEnabled()
        awaitBrowser()
    }

    @Test
    fun ineligibleHtmlKeepsSourceAndCopyWithoutPreviewControlsOrBrowser() {
        eligible.value = false
        host()
        compose.onNodeWithTag(SOURCE).assertIsDisplayed().assertTextEquals(code.value)
        compose.onNodeWithContentDescription(runLabel).assertDoesNotExist()
        compose.onNodeWithContentDescription(sourceLabel).assertDoesNotExist()
        compose.onNodeWithContentDescription(moreLabel).assertDoesNotExist()
        compose.onAllNodesWithContentDescription(copyLabel).assertCountEquals(1)
        compose.onNodeWithContentDescription(copyLabel).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(code.value, clipboard.getText()?.text)
            assertTrue(previewViews().isEmpty())
        }
    }

    @Test
    fun searchingStartsOnExactSourceAndDisablesRunEvenForAutomaticPreview() {
        searching.value = true
        host()
        compose.onNodeWithTag(SOURCE).assertIsDisplayed().assertTextEquals(code.value)
        compose.onNodeWithContentDescription(runLabel).assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription(sourceLabel).assertDoesNotExist()
        compose.onNodeWithContentDescription(copyLabel).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(code.value, clipboard.getText()?.text)
            assertTrue(previewViews().isEmpty())
        }
    }

    @Test
    fun enteringSearchReleasesThePreviewAndLeavingSearchRestoresItsMode() {
        host()
        val initial = awaitBrowser()
        compose.runOnIdle { searching.value = true }
        compose.onNodeWithTag(SOURCE).assertIsDisplayed().assertTextEquals(code.value)
        compose.onNodeWithContentDescription(runLabel).assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(previewViews().isEmpty())
            assertTrue(shadowOf(initial).wasDestroyCalled())
        }

        compose.runOnIdle { searching.value = false }

        compose.onNodeWithTag(SOURCE).assertDoesNotExist()
        compose.onNodeWithContentDescription(sourceLabel).assertIsEnabled()
        assertNotSame(initial, awaitBrowser())
    }

    @Test
    fun sourceToggleAndCopyAlwaysUseTheOriginalHtmlIncludingShellReferences() {
        val original = code.value
        host()
        awaitBrowser()
        compose.onNodeWithContentDescription(copyLabel).performClick()
        compose.runOnIdle { assertEquals(original, clipboard.getText()?.text) }

        compose.onNodeWithContentDescription(sourceLabel).performClick()
        compose.onNodeWithTag(SOURCE).assertTextEquals(original)
        compose.onNodeWithContentDescription(copyLabel).performClick()
        compose.runOnIdle { assertEquals(original, clipboard.getText()?.text) }

        compose.onNodeWithContentDescription(runLabel).performClick()
        awaitBrowser()
        compose.onNodeWithContentDescription(copyLabel).performClick()
        compose.runOnIdle {
            assertEquals(original, clipboard.getText()?.text)
            assertEquals(original, code.value)
            assertFalse(clipboard.getText()!!.text.contains("preview.agora.invalid"))
        }
    }

    @Test
    fun losingEligibilityDisposesTheMountedBrowserAndRemovesPreviewControls() {
        host()
        val browser = awaitBrowser()
        compose.runOnIdle { eligible.value = false }

        compose.onNodeWithTag(SOURCE).assertTextEquals(code.value)
        compose.onNodeWithContentDescription(runLabel).assertDoesNotExist()
        compose.onNodeWithContentDescription(sourceLabel).assertDoesNotExist()
        compose.onNodeWithContentDescription(moreLabel).assertDoesNotExist()
        compose.onAllNodesWithContentDescription(copyLabel).assertCountEquals(1)
        compose.runOnIdle {
            assertTrue(previewViews().isEmpty())
            assertTrue(shadowOf(browser).wasDestroyCalled())
        }
    }

    @Test
    fun inlineBrowserHasTheFixedViewportAfterImageFreeContentLoads() {
        code.value = "<h1>Visible preview</h1>"
        host()
        val browser = awaitBrowser()
        compose.runOnIdle {
            assertTrue(browser.isAttachedToWindow)
            // Robolectric's WebView provider does not implement setFrame. Verify the real
            // Compose holder's layout and the measure specs delivered to the WebView instead.
            val holder = browser.parent as View
            assertEquals(360, holder.width)
            assertEquals(360, holder.height)
            assertEquals(holder.width, browser.measuredWidth)
            assertEquals(holder.height, browser.measuredHeight)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, browser.layoutParams.width)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, browser.layoutParams.height)
            assertFalse(shadowOf(browser).wasDestroyCalled())
        }
    }

    @Test
    fun fullscreenMountsASizedBrowserAndClosingRestoresTheInlineBrowser() {
        code.value = "<h1>Visible preview</h1>"
        host()
        val inline = awaitBrowser()
        compose.onNodeWithContentDescription(moreLabel).performClick()
        compose.onNodeWithText(context.getString(R.string.html_preview_fullscreen)).performClick()
        var fullscreen: HtmlPreviewWebView? = null
        compose.waitUntil(5_000) {
            compose.runOnIdle {
                val root = ShadowDialog.getLatestDialog()?.window?.decorView
                fullscreen = root?.let { previewViews(it).singleOrNull() }
            }
            fullscreen != null
        }
        compose.runOnIdle {
            assertTrue(shadowOf(inline).wasDestroyCalled())
            assertTrue(requireNotNull(fullscreen).isAttachedToWindow)
            val browser = requireNotNull(fullscreen)
            val holder = browser.parent as View
            assertTrue(holder.width > 0)
            assertTrue(holder.height > 360)
            assertEquals(holder.width, browser.measuredWidth)
            assertEquals(holder.height, browser.measuredHeight)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, browser.layoutParams.width)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, browser.layoutParams.height)
        }
        assertActionOrder(listOf(sourceLabel, context.getString(R.string.html_preview_reload), copyLabel,
            context.getString(R.string.html_preview_save), context.getString(R.string.provider_close))
            .map { label ->
                compose.onNode(hasContentDescription(label) and hasAnyAncestor(isDialog()))
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            })
        compose.onNodeWithContentDescription(context.getString(R.string.provider_close)).performClick()
        assertNotSame(inline, awaitBrowser())
        compose.runOnIdle { assertTrue(shadowOf(requireNotNull(fullscreen)).wasDestroyCalled()) }
    }

    @Test
    fun fullscreenHeaderDoesNotJoinTheUnderlyingMessageSelection() {
        code.value = "<h1>Visible preview</h1>"
        host { content ->
            com.newoether.agora.util.NoAutoScrollSelectionContainer {
                Column(Modifier.width(360.dp)) { content() }
            }
        }
        awaitBrowser()
        compose.onNodeWithContentDescription(moreLabel).performClick()
        compose.onNodeWithText(context.getString(R.string.html_preview_fullscreen)).performClick()
        compose.onNode(
            androidx.compose.ui.test.hasText("HTML-PREVIEW") and hasAnyAncestor(isDialog()),
        ).performTouchInput { longClick() }
        compose.onNodeWithContentDescription(context.getString(R.string.provider_close)).performClick()
        compose.onNodeWithContentDescription(sourceLabel).performClick()
        compose.onNodeWithTag(SOURCE).assertIsDisplayed().assertTextEquals(code.value)
        compose.onNodeWithContentDescription(copyLabel).performClick()
        compose.runOnIdle { assertEquals(code.value, clipboard.getText()?.text) }
    }

    @Test
    fun previewMenuLabelsDoNotJoinTheUnderlyingMessageSelection() {
        host { content ->
            com.newoether.agora.util.NoAutoScrollSelectionContainer {
                Column(Modifier.width(360.dp)) { content() }
            }
        }
        awaitBrowser()
        compose.onNodeWithContentDescription(moreLabel).performClick()
        compose.onNodeWithText(context.getString(R.string.html_preview_fullscreen))
            .performTouchInput { longClick() }
    }

    @Test
    fun fullscreenFallbackDoesNotJoinTheUnderlyingMessageSelection() {
        code.value = "<h1>Visible preview</h1>"
        host { content ->
            com.newoether.agora.util.NoAutoScrollSelectionContainer {
                Column(Modifier.width(360.dp)) { content() }
            }
        }
        awaitBrowser()
        compose.onNodeWithContentDescription(moreLabel).performClick()
        compose.onNodeWithText(context.getString(R.string.html_preview_fullscreen)).performClick()
        var browser: HtmlPreviewWebView? = null
        compose.waitUntil(5_000) {
            compose.runOnIdle {
                browser = ShadowDialog.getLatestDialog()?.window?.decorView
                    ?.let { previewViews(it).singleOrNull() }
            }
            browser != null
        }
        compose.runOnIdle {
            val shadow = shadowOf(requireNotNull(browser))
            shadow.webChromeClient.onReceivedTitle(browser, HtmlPreviewProbe.COMPLETE_TITLE)
            requireNotNull(shadow.lastEvaluatedJavascriptCallback).onReceiveValue("\"1,1:1,1\"")
        }
        compose.onNodeWithText(context.getString(R.string.html_preview_webview_required))
            .assertIsDisplayed().performTouchInput { longClick() }
        compose.onNodeWithContentDescription(context.getString(R.string.provider_close)).performClick()
        awaitBrowser()
    }

    @Test
    fun scrollingOutOfTheClippedViewportReleasesAndReentryRecreatesTheBrowser() {
        lateinit var scroll: ScrollState
        host { content ->
            scroll = rememberScrollState()
            Column(Modifier.width(360.dp).height(440.dp).verticalScroll(scroll)) {
                content()
                Spacer(Modifier.height(900.dp))
            }
        }
        val initial = awaitBrowser()
        compose.runOnIdle { runBlocking { scroll.scrollTo(600) } }
        compose.waitUntil(5_000) {
            var empty = false
            compose.runOnIdle { empty = previewViews().isEmpty() }
            empty
        }
        compose.runOnIdle {
            assertTrue(shadowOf(initial).wasDestroyCalled())
            runBlocking { scroll.scrollTo(0) }
        }
        assertNotSame(initial, awaitBrowser())
    }

    @Test
    fun backgroundLifecycleReleasesAndStartedLifecycleRecreatesTheBrowser() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        host { content ->
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Column(Modifier.width(360.dp)) { content() }
            }
        }
        val initial = awaitBrowser()
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        compose.waitUntil(5_000) {
            var empty = false
            compose.runOnIdle { empty = previewViews().isEmpty() }
            empty
        }
        compose.runOnIdle {
            assertTrue(shadowOf(initial).wasDestroyCalled())
            owner.lifecycle.currentState = Lifecycle.State.STARTED
        }
        assertNotSame(initial, awaitBrowser())
    }

    @Test
    fun completedFenceMountsThroughTheRealMarkdownBackgroundAndSelectionHost() {
        code.value = "Before preview.\n\n```html-preview\n<h1>Visible preview</h1>\n```\n\nAfter preview."
        host(markdown = true)
        val browser = awaitBrowser()
        compose.onNodeWithContentDescription(sourceLabel).assertIsDisplayed()
        compose.runOnIdle {
            val holder = browser.parent as View
            assertTrue(holder.width > 0)
            assertEquals(360, holder.height)
            assertEquals(holder.width, browser.measuredWidth)
            assertEquals(holder.height, browser.measuredHeight)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, browser.layoutParams.width)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, browser.layoutParams.height)
        }
        compose.onNodeWithContentDescription(sourceLabel).performClick()
        compose.onNodeWithText("<h1>Visible preview</h1>", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertTrue(shadowOf(browser).wasDestroyCalled()) }
    }

    @Test
    fun streamingFenceWaitsForTheClosingLineBeforeMountingThroughMarkdown() {
        code.value = "```html-preview\n<h1>Visible preview</h1>\n"
        streaming.value = true
        host(markdown = true)
        // The parser publishes from Dispatchers.Default, outside Compose's idle tracking.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("<h1>Visible preview</h1>", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("<h1>Visible preview</h1>", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(sourceLabel).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(previewViews().isEmpty())
            code.value += "```\n"
        }
        awaitBrowser()
        compose.onNodeWithContentDescription(sourceLabel).assertIsDisplayed()
        compose.runOnIdle { streaming.value = false }
        awaitBrowser()
        compose.onNodeWithContentDescription(sourceLabel).assertIsDisplayed()
    }

    @Test
    fun rejectedProbeReplacesTheMountedBrowserWithVisibleUpdateGuidance() {
        code.value = "<h1>Visible preview</h1>"
        host()
        val browser = awaitBrowser()
        compose.runOnIdle {
            val shadow = shadowOf(browser)
            shadow.webChromeClient.onReceivedTitle(browser, HtmlPreviewProbe.COMPLETE_TITLE)
            requireNotNull(shadow.lastEvaluatedJavascriptCallback).onReceiveValue("\"1,1:1,1\"")
        }
        compose.onNodeWithText(context.getString(R.string.html_preview_webview_required)).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(previewViews().isEmpty())
            assertTrue(shadowOf(browser).wasDestroyCalled())
        }
    }
    @Test
    fun clippingAncestorExpandingFromZeroHeightMountsTheVisibleBrowser() {
        val height = mutableStateOf(0.dp)
        code.value = "<h1>Visible preview</h1>"
        host { content ->
            Column(Modifier.width(360.dp).height(height.value).verticalScroll(rememberScrollState())) {
                content()
            }
        }
        compose.runOnIdle {
            assertTrue(previewViews().isEmpty())
            height.value = 440.dp
        }
        val browser = awaitBrowser()
        compose.runOnIdle {
            assertTrue(browser.isAttachedToWindow)
            assertEquals(360, browser.measuredHeight)
        }
    }
    @Test
    fun compactHeaderUsesTheDefaultLabelFontAndKeepsCodeMonospaced() {
        code.value = "```python\nprint(42)\n```"
        host(markdown = true)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("PYTHON").fetchSemanticsNodes().isNotEmpty()
        }
        val labelLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("PYTHON")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(labelLayouts) }
        assertEquals(MaterialThemeDefaultLabelFont, labelLayouts.single().layoutInput.style.fontFamily)
        val codeLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("print(42)")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(codeLayouts) }
        assertEquals(ChatType.code.fontFamily, codeLayouts.single().layoutInput.style.fontFamily)
        val action = compose.onNodeWithContentDescription(copyLabel)
            .assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
            .fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithText("print(42)").fetchSemanticsNode().boundsInRoot
        assertEquals(48f, body.top - action.top, 0.1f)
        compose.onNodeWithContentDescription(copyLabel).performClick()
        compose.runOnIdle { assertEquals("print(42)", clipboard.getText()?.text?.trimEnd()) }
    }

    @Test
    fun narrowRtlLargeFontHeaderKeepsEveryActionReachableAndCopyExact() {
        language.value = "html"
        host { content ->
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(1f, 2f),
            ) {
                Column(Modifier.width(144.dp)) { content() }
            }
        }
        val actions = listOf(runLabel, copyLabel, context.getString(R.string.html_preview_save), moreLabel)
        val bounds = actions.map { label ->
            val node = compose.onNodeWithContentDescription(label)
                .assertIsDisplayed().assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
            node.fetchSemanticsNode().boundsInRoot
        }
        assertActionOrder(bounds, rtl = true)
        bounds.forEachIndexed { index, rect ->
            assertTrue(rect.left >= 0f && rect.right <= 144f)
            bounds.drop(index + 1).forEach { other ->
                assertFalse("Independent code actions must not overlap", rect.overlaps(other))
            }
        }
        compose.onNodeWithContentDescription(copyLabel).performClick()
        compose.runOnIdle { assertEquals(code.value, clipboard.getText()?.text) }
        compose.onNodeWithContentDescription(moreLabel).performClick()
        compose.onNodeWithText(context.getString(R.string.html_preview_reload)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.html_preview_fullscreen)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.html_preview_share)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.html_preview_save)).assertDoesNotExist()
    }

    @Test
    fun standaloneCodeKeepsItsCopyGuardAndOptionalHeader() {
        val allowed = mutableStateOf(false)
        val showHeader = mutableStateOf(true)
        compose.setContent {
            clipboard = LocalClipboardManager.current
            MaterialTheme {
                ChatMarkdownCodeBlock(
                    code = code.value,
                    autoWrap = false,
                    showHeader = showHeader.value,
                    canCopy = { allowed.value },
                    modifier = Modifier.width(280.dp),
                )
            }
        }
        compose.runOnIdle { clipboard.setText(androidx.compose.ui.text.AnnotatedString("unchanged")) }
        compose.onNodeWithContentDescription(copyLabel).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("unchanged", clipboard.getText()?.text)
            allowed.value = true
        }
        compose.onNodeWithContentDescription(copyLabel).performClick()
        compose.runOnIdle {
            assertEquals(code.value, clipboard.getText()?.text)
            showHeader.value = false
        }
        compose.onNodeWithContentDescription(copyLabel).assertDoesNotExist()
        compose.onNodeWithText(code.value).assertIsDisplayed()
    }

    @Test
    fun loadingOverlayCoversTheCanvasUntilVisualReadyThenFadesAndReloadResetsIt() {
        code.value = "<h1>Visible preview</h1>"
        host()
        val browser = awaitBrowser()
        compose.onNodeWithTag(LOADING).assertIsDisplayed().assertHeightIsEqualTo(360.dp)
        compose.runOnIdle {
            assertEquals(View.INVISIBLE, browser.visibility)
            val shadow = shadowOf(browser)
            shadow.webChromeClient.onReceivedTitle(browser, HtmlPreviewProbe.COMPLETE_TITLE)
            requireNotNull(shadow.lastEvaluatedJavascriptCallback).onReceiveValue(HtmlPreviewProbe.ACCEPTED_RESULT)
            shadow.webViewClient.onPageFinished(browser, browser.url)
        }
        compose.onNodeWithTag(LOADING).assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { Shadow.extract<HtmlPreviewVisualStateShadow>(browser).complete() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag(LOADING).assertExists()
        compose.runOnIdle { assertEquals(View.VISIBLE, browser.visibility) }
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithTag(LOADING).assertDoesNotExist()
        compose.mainClock.autoAdvance = true

        compose.onNodeWithContentDescription(moreLabel).performClick()
        compose.onNodeWithText(context.getString(R.string.html_preview_reload)).performClick()
        val reloaded = awaitBrowser()
        assertNotSame(browser, reloaded)
        compose.onNodeWithTag(LOADING).assertIsDisplayed()
        compose.runOnIdle { assertEquals(View.INVISIBLE, reloaded.visibility) }
    }

    @Test
    fun loadingOverlayFadesBothDirectionsWithoutChangingCanvasBounds() {
        val loading = mutableStateOf(true)
        var clicks = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            drawingView = LocalView.current
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(surface = Color.Black)) {
                CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true)) {
                    Box(Modifier.width(180.dp).height(180.dp).background(Color.Red).testTag("canvas")) {
                        Box(Modifier.matchParentSize().clickable { clicks++ })
                        HtmlPreviewLoadingOverlay(loading.value)
                    }
                }
            }
        }
        fun red(): Float {
            compose.waitForIdle()
            val bounds = compose.onNodeWithTag("canvas").fetchSemanticsNode().boundsInRoot
            val bitmap = android.graphics.Bitmap.createBitmap(180, 180, android.graphics.Bitmap.Config.ARGB_8888)
            return try {
                compose.runOnUiThread {
                    // SDK 35 PixelCopy's redraw fence is unsupported by Robolectric.
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.translate(-bounds.left, -bounds.top)
                    drawingView.draw(canvas)
                }
                android.graphics.Color.red(bitmap.getPixel(4, 4)) / 255f
            } finally { bitmap.recycle() }
        }
        fun frame() {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertEquals(1f, red(), 0.02f)
        frame()
        compose.mainClock.advanceTimeBy(64)
        val entering = red()
        assertTrue("Entry must render intermediate opacity: $entering", entering > 0.02f && entering < 0.98f)
        compose.onNodeWithTag("canvas").performTouchInput { click() }
        assertEquals(0, clicks)
        compose.mainClock.advanceTimeBy(240)
        assertEquals(0f, red(), 0.02f)
        compose.onNodeWithTag(LOADING).assertWidthIsEqualTo(180.dp).assertHeightIsEqualTo(180.dp)
        compose.runOnIdle { loading.value = false }
        frame()
        compose.mainClock.advanceTimeBy(64)
        val exiting = red()
        assertTrue("Exit must render intermediate opacity: $exiting", exiting > 0.02f && exiting < 0.98f)
        compose.onNodeWithTag("canvas").performTouchInput { click() }
        assertEquals(0, clicks)
        compose.mainClock.advanceTimeBy(240)
        assertEquals(1f, red(), 0.02f)
        compose.onNodeWithTag(LOADING).assertDoesNotExist()
        compose.onNodeWithTag("canvas").performTouchInput { click() }
        assertEquals(1, clicks)
    }

    private fun host(
        markdown: Boolean = false,
        container: @Composable (@Composable () -> Unit) -> Unit = { content ->
            Column(Modifier.width(360.dp)) { content() }
        },
    ) = compose.setContent {
        drawingView = LocalView.current
        clipboard = LocalClipboardManager.current
        CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true)) {
            MaterialTheme {
                val assets = rememberChatMarkdownAssets(MaterialTheme.colorScheme.onSurface)
                val media = remember {
                    ShellMarkdownMedia(context, devices = { emptyList() }, sandboxEnabled = { false }, sandbox = { null })
                }
                CompositionLocalProvider(
                    LocalMarkdownColors provides assets.renderContext.colors,
                    LocalShellMarkdownMedia provides if (markdown) media else null,
                ) {
                    container {
                        if (markdown) {
                            IncrementalStreamingMarkdownContent(
                                content = code.value,
                                isStreaming = streaming.value,
                                renderContext = assets.renderContext,
                            )
                        } else {
                            HtmlPreviewCodeContent(
                                code = code.value,
                                language = language.value,
                                eligible = eligible.value,
                                searching = searching.value,
                            ) {
                                Text(code.value, modifier = Modifier.testTag(SOURCE))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun assertActionOrder(bounds: List<Rect>, rtl: Boolean = false) {
        bounds.zipWithNext().forEach { (before, after) ->
            assertTrue("Actions keep reading order across rows", before.bottom <= after.top ||
                (before.top == after.top && if (rtl) after.right <= before.left else before.right <= after.left))
        }
    }

    private fun awaitBrowser(): HtmlPreviewWebView {
        var browser: HtmlPreviewWebView? = null
        compose.waitUntil(5_000) {
            compose.runOnIdle { browser = previewViews().singleOrNull() }
            browser != null
        }
        return requireNotNull(browser)
    }

    private fun previewViews(root: View = drawingView.rootView): List<HtmlPreviewWebView> = buildList {
        fun visit(view: View) {
            if (view is HtmlPreviewWebView) add(view)
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        visit(root)
    }

    private companion object {
        val MaterialThemeDefaultLabelFont = androidx.compose.material3.Typography().labelMedium.fontFamily
        const val SOURCE = "html-preview-test-source"
        const val LOADING = "html-preview-loading-overlay"
    }
}
