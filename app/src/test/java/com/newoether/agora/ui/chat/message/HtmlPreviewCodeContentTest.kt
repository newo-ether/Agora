package com.newoether.agora.ui.chat.message

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.newoether.agora.R
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("DEPRECATION")
class HtmlPreviewCodeContentTest {
    @get:Rule val compose = createComposeRule()
    private val code = mutableStateOf("<button>Count &gt; 0</button>\n<img src=\"shell://Quantum/F:/original.png\">")
    private val language = mutableStateOf<String?>("html-preview")
    private val eligible = mutableStateOf(true)
    private val searching = mutableStateOf(false)
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

    private fun host() = compose.setContent {
        drawingView = LocalView.current
        clipboard = LocalClipboardManager.current
        CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true)) {
            MaterialTheme {
                val assets = rememberChatMarkdownAssets(MaterialTheme.colorScheme.onSurface)
                CompositionLocalProvider(LocalMarkdownColors provides assets.renderContext.colors) {
                    Column(Modifier.width(360.dp)) {
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

    private fun awaitBrowser(): HtmlPreviewWebView {
        var browser: HtmlPreviewWebView? = null
        compose.waitUntil(5_000) {
            compose.runOnIdle { browser = previewViews().singleOrNull() }
            browser != null
        }
        return requireNotNull(browser)
    }

    private fun previewViews(): List<HtmlPreviewWebView> = buildList {
        fun visit(view: View) {
            if (view is HtmlPreviewWebView) add(view)
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        visit(drawingView.rootView)
    }

    private companion object {
        const val SOURCE = "html-preview-test-source"
    }
}
