package com.newoether.agora.ui.chat.message

import android.content.res.Resources
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Card titles are plain [Resources] functions so the WebUI can build them off the UI. They must
 * give the same text as the Composable call sites, in the configured language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PresentationStringsTest {
    @get:Rule val compose = createComposeRule()

    private val resources: Resources
        get() = ApplicationProvider.getApplicationContext<android.app.Application>().resources

    private val thought = MessageSegment(type = "thought", content = "plan", durationMs = 12_000)
    private val tool = MessageSegment(type = "tool", toolName = "code_execution")
    private val message = ChatMessage(
        id = "m",
        text = "",
        participant = Participant.MODEL,
        status = MessageStatus.SUCCESS,
    )

    @Test
    fun englishTitlesComeFromResources() {
        assertEquals("Thought for 12s, called 1 tools", resources.compactSegmentTitle(listOf(thought, tool), message, false))
        assertEquals("Thinking for 2m 5s...", resources.thinkingDurationBreakdownTitle(125, live = true))
        assertEquals(resources.getString(R.string.code_execution), resources.toolDisplayName(tool))
    }

    @Test
    @Config(qualifiers = "zh")
    fun chineseTitlesFollowTheConfiguredLanguage() {
        assertEquals("思考了 12 秒，调用 1 个工具", resources.compactSegmentTitle(listOf(thought, tool), message, false))
        assertEquals("已思考 2 分 5 秒...", resources.thinkingDurationBreakdownTitle(125, live = true))
    }

    @Test
    @Config(qualifiers = "zh")
    fun composableTitlesMatchTheResourcesFunctions() {
        val segments = listOf(thought, tool)
        var composed: List<String> = emptyList()
        compose.setContent {
            composed = listOf(
                compactSegmentTitle(segments, message, useLiveStatus = false),
                compactSegmentDisplayTitle(segments, message, useLiveStatus = false),
                segmentDetailTitle(tool, segments, 1),
                toolDisplayName(tool),
                toolSummary(tool),
                transcriptionLabel(segments, 0),
            )
        }
        compose.waitForIdle()
        assertEquals(
            listOf(
                resources.compactSegmentTitle(segments, message, false),
                resources.compactSegmentTitle(segments, message, false),
                resources.segmentDetailTitle(tool, segments, 1),
                resources.toolDisplayName(tool),
                resources.toolSummary(tool),
                resources.transcriptionLabel(segments, 0),
            ),
            composed,
        )
    }

    @Test
    fun liveThoughtTimerResumesFromTheGenerationClock() {
        val sampled = thought.copy(
            durationMs = 30_000L,
            durationSampledAtElapsedRealtimeMs = 100_000L,
        )
        val thinking = message.copy(status = MessageStatus.THINKING)
        val state = resources.compactSegmentTitleState(listOf(sampled), thinking, useLiveStatus = true)

        assertEquals(30_000L, state.liveBaseMs)
        assertEquals(100_000L, state.liveSampledAtElapsedRealtimeMs)
        // Re-entering the screen 15s later continues at 45s instead of restarting at 30s.
        assertEquals(45_000L, liveThoughtDurationMs(30_000L, 100_000L, 115_000L))
        // A stale monotonic anchor never subtracts from the settled duration.
        assertEquals(30_000L, liveThoughtDurationMs(30_000L, 115_000L, 100_000L))
    }

    @Test
    fun liveTimerRunsOnlyForAStreamingThoughtWithTheDefaultTitle() {
        val thinking = message.copy(status = MessageStatus.THINKING)
        assertTrue(isLiveThinkingGroup(listOf(thought), thinking, useLiveStatus = true))
        assertFalse(isLiveThinkingGroup(listOf(thought), thinking, useLiveStatus = false))
        assertFalse(isLiveThinkingGroup(listOf(tool), thinking, useLiveStatus = true))
        assertTrue(resources.usesDefaultThinkingTitle(thinking))
        assertTrue(resources.usesDefaultThinkingTitle(thinking.copy(thoughtTitle = resources.getString(R.string.thinking_ellipsis))))
        assertFalse(resources.usesDefaultThinkingTitle(thinking.copy(thoughtTitle = "Planning the reply")))
    }
}
