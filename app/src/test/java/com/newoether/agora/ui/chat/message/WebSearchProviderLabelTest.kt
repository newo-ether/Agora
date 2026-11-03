package com.newoether.agora.ui.chat.message

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The expanded search card names the backend beside the query it ran. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WebSearchProviderLabelTest {
    @get:Rule val compose = createComposeRule()
    private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

    private fun segment(result: String?, state: String? = null) = MessageSegment(
        type = "tool",
        toolName = "web_search",
        toolArgs = """{"query":"nix"}""",
        toolResult = result,
        toolState = state,
    )

    private val hit =
        """{"type":"web_search","query":"nix","provider":"Kagi","results":[{"title":"a","url":"https://a","description":"d"}]}"""

    @Test
    fun answeredProviderIsExposedToTheDetailCard() {
        val presentation = ToolPresentationResolver.resolve(segment(hit))

        assertEquals("Kagi", presentation.provider)
        assertEquals("Kagi", resources.toolDetailPresentation(segment(hit)).searchProvider)
    }

    @Test
    fun searchWithoutAProviderReportsNone() {
        val result = """{"type":"web_search","query":"nix","results":[{"title":"a","url":"https://a"}]}"""

        assertNull(resources.toolDetailPresentation(segment(result)).searchProvider)
    }

    @Test
    fun failedSearchKeepsTheBackendThatWasLastTried() {
        val failed = """{"type":"web_search","query":"nix","provider":"Kagi","error":"http_error","message":"HTTP 429"}"""
        val presentation = resources.toolDetailPresentation(
            segment(failed, ToolExecutionStates.FAILED),
        )

        assertEquals("Kagi", presentation.searchProvider)
        assertEquals(
            ToolDetailBody.Failed("HTTP 429", null),
            presentation.body,
        )
    }

    @Test
    fun cardRendersTheProviderBelowItsArguments() {
        compose.setContent { MaterialTheme { ToolDetailContent(segment(hit)) { _, _ -> } } }

        compose.onNodeWithText(resources.getString(R.string.arguments_label)).assertExists()
        compose.onNodeWithText(resources.getString(R.string.tool_search_provider, "Kagi")).assertExists()
    }

    @Test
    fun otherToolsIgnoreAProviderField() {
        val fetch = MessageSegment(
            type = "tool",
            toolName = "web_fetch",
            toolArgs = """{"url":"https://a"}""",
            toolResult = """{"type":"web_fetch","url":"https://a","text":"body","provider":"Kagi"}""",
        )

        assertNull(ToolPresentationResolver.resolve(fetch).provider)
        assertNull(resources.toolDetailPresentation(fetch).searchProvider)
    }
}
