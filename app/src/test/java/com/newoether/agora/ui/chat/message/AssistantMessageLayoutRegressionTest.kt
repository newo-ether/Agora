package com.newoether.agora.ui.chat.message

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantMessageLayoutRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

    private fun host(message: ChatMessage, mode: String = ToolCallDisplayModes.DEFAULT) {
        compose.setContent {
            val context = LocalContext.current
            val mediaScope = rememberCoroutineScope()
            val media = remember(context, mediaScope) {
                ShellMarkdownMedia(context, devices = { emptyList() }, sandboxEnabled = { false },
                    sandbox = { null }, scope = mediaScope)
            }
            CompositionLocalProvider(
                LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true),
                LocalShellMarkdownMedia provides media,
            ) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).testTag("message")) {
                        MessageItem(
                            message = message,
                            onEdit = { _, _ -> },
                            segmentAppearanceRegistry = remember { SegmentAppearanceRegistry() },
                            toolCallDisplayMode = mode,
                            thinkingSegmentDisplayMode = ThinkingSegmentDisplayModes.CARD,
                            showActions = false,
                        )
                    }
                }
            }
        }
    }

    private fun textBounds(text: String): Rect {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        compose.waitForIdle()
        return compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    }

    private fun assertNoMessageDisclosure() {
        compose.onNodeWithText(resources.getString(R.string.expand)).assertDoesNotExist()
        compose.onNodeWithText(resources.getString(R.string.collapse)).assertDoesNotExist()
    }

    @Test
    fun stoppedLabelFollowsTheCompleteOrdinaryAnswerWithoutOverlap() {
        val first = "First answer paragraph"
        val last = "Last answer paragraph"
        val answer = "$first\n\nSecond answer paragraph\n\n$last"
        host(ChatMessage(id = "stopped-answer", text = answer, participant = Participant.MODEL,
            status = MessageStatus.STOPPED))
        textBounds(first)
        val body = textBounds(last)
        val terminal = textBounds(resources.getString(R.string.generation_stopped))
        assertTrue("Stopped must be below the entire answer: $body -> $terminal", terminal.top >= body.bottom)
        assertEquals(8f, terminal.top - body.bottom, 0.5f)
        assertNoMessageDisclosure()
    }

    @Test
    fun stoppedTimelineKeepsEachAnswerAndItsTerminalLabelInSeparateRows() {
        val first = "Answer before thinking"
        val last = "Answer after thinking"
        host(ChatMessage(id = "stopped-timeline", text = "$first\n\n$last", participant = Participant.MODEL,
            status = MessageStatus.STOPPED, segments = listOf(
                MessageSegment("answer", first),
                MessageSegment("thought", "Intermediate reasoning", durationMs = 1000),
                MessageSegment("answer", last),
            )), ToolCallDisplayModes.TIMELINE)
        val firstBounds = textBounds(first)
        val lastBounds = textBounds(last)
        val terminal = textBounds(resources.getString(R.string.generation_stopped))
        assertTrue("Timeline cards must retain their space", lastBounds.top > firstBounds.bottom)
        assertTrue("Stopped must follow the final timeline answer", terminal.top >= lastBounds.bottom)
        assertEquals(8f, terminal.top - lastBounds.bottom, 0.5f)
        assertNoMessageDisclosure()
    }

    @Test
    fun longAssistantAnswerRetainsItsFullHeightWithoutUserDisclosure() {
        val paragraphs = (1..15).map { "Assistant answer paragraph $it" }
        val answer = paragraphs.joinToString("\n\n")
        host(ChatMessage(id = "long-assistant", text = answer, participant = Participant.MODEL))
        val first = textBounds(paragraphs.first())
        val last = textBounds(paragraphs.last())
        assertTrue("The assistant answer exceeds the user preview limit", last.bottom - first.top > 320f)
        val message = compose.onNodeWithTag("message").fetchSemanticsNode().boundsInRoot
        assertTrue("The full answer remains inside the assistant message", message.bottom >= last.bottom)
        assertNoMessageDisclosure()
    }

    @Test
    fun assistantHtmlToolbarAfterLongProseIsVisibleWithoutMessageExpansion() {
        val paragraphs = (1..12).map { "Assistant introduction paragraph $it" }
        val prose = paragraphs.joinToString("\n\n")
        val html = "<p>Full assistant HTML</p>\n"
        host(ChatMessage(id = "assistant-html", text = "$prose\n\n```html-preview\n$html```",
            participant = Participant.MODEL))
        val body = textBounds(paragraphs.last())
        val toolbar = compose.onNodeWithContentDescription(resources.getString(R.string.html_preview_fullscreen))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(resources.getString(R.string.html_preview_fullscreen))
                .fetchSemanticsNodes().isNotEmpty()
        }
        toolbar.assertIsDisplayed()
        assertTrue("HTML follows all preceding assistant prose",
            toolbar.fetchSemanticsNode().boundsInRoot.top >= body.bottom)
        assertNoMessageDisclosure()
    }
}
