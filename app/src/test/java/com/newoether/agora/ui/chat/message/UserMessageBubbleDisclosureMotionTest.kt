package com.newoether.agora.ui.chat.message

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h900dp-mdpi")
class UserMessageBubbleDisclosureMotionTest {
    @get:Rule val compose = createComposeRule()
    private val raw = (1..20).joinToString("\n") { "Input line $it" }
    private val starts = mutableListOf<Pair<String, Float>>()
    private val settlements = mutableListOf<String>()
    private var measuredHeight = 0f

    private fun host(reduceMotion: Boolean = false, sizeAnimationReady: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = reduceMotion)) {
                MaterialTheme {
                    Column {
                        Box(Modifier.width(360.dp).testTag("bubble-size")) {
                            UserMessageBubble(
                                message = ChatMessage(id = "motion-input", text = raw, participant = Participant.USER),
                                shape = UserBubbleShape(),
                                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                                textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                contextAlpha = Modifier,
                                isEditing = false,
                                sizeAnimationReady = sizeAnimationReady,
                                isLoading = false,
                                isEditingAllowed = true,
                                showActions = true,
                                actionCopyText = raw,
                                showBranchSelector = false,
                                branchIndex = 0,
                                totalBranches = 1,
                                onEdit = { _, _ -> },
                                onCancelEdit = {},
                                onStartEdit = {},
                                onSelectText = {},
                                onSwitchBranch = {},
                                onMediaClick = { _, _ -> },
                                onFileContentClick = null,
                                onPdfPagesClick = null,
                                onShowInfo = {},
                                onShowDelete = {},
                                searchHighlight = null,
                                onLayoutMutationStarted = { starts += it to measuredHeight },
                                onLayoutMutationSettled = { settlements += it },
                            )
                        }
                        Spacer(Modifier.width(1.dp).height(1.dp).testTag("following-message"))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeUntil(timeoutMillis = 1_000) {
            compose.onAllNodesWithText("Expand").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun height(): Float {
        val bubble = compose.onNodeWithTag("bubble-size").fetchSemanticsNode().boundsInRoot
        val following = compose.onNodeWithTag("following-message").fetchSemanticsNode().boundsInRoot
        assertEquals("Following content tracks actual occupied height", bubble.bottom, following.top, 0.1f)
        measuredHeight = bubble.height
        return bubble.height
    }

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds, ignoreFrameDuration = true)
        compose.waitForIdle()
    }

    private fun disclose(label: String) {
        height()
        compose.onNodeWithText(label).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test
    fun thinkingCurveMovesTheOccupiedHeightAndFollowingContentInBothDirections() {
        host()
        val firstLayout = height()
        advance(600)
        val collapsed = height()
        assertEquals("First layout is settled", firstLayout, collapsed, 0.1f)
        disclose("Expand")
        assertEquals("Anchor is captured before changing layout", collapsed, starts.single().second, 0.1f)
        assertEquals(collapsed, height(), 2f)
        advance(100)
        val early = height()
        advance(100)
        val midpoint = height()
        advance(100)
        val late = height()
        advance(100)
        val expanded = height()
        assertTrue(collapsed < early && early < midpoint && midpoint < late && late < expanded)
        assertTrue(expanded > collapsed + 100f)
        assertEquals("Thinking's 400ms default tween is about 78% complete at 200ms",
            0.7756f, (midpoint - collapsed) / (expanded - collapsed), 0.055f)
        val body = compose.onNodeWithText(raw, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val control = compose.onNode(hasClickAction() and hasText("Collapse")).fetchSemanticsNode().boundsInRoot
        assertTrue("Collapse occupies the row after the full body", control.top >= body.bottom)
        advance(100)
        assertEquals(listOf("user-disclosure:motion-input"), settlements)
        assertEquals("No outer size animation trails the reveal", expanded, height(), 0.1f)

        disclose("Collapse")
        assertEquals(expanded, height(), 2f)
        advance(100)
        val shrinkingEarly = height()
        advance(100)
        val shrinkingMidpoint = height()
        advance(100)
        val shrinkingLate = height()
        assertTrue(expanded > shrinkingEarly && shrinkingEarly > shrinkingMidpoint &&
            shrinkingMidpoint > shrinkingLate && shrinkingLate > collapsed)
        assertEquals(0.7756f, (expanded - shrinkingMidpoint) / (expanded - collapsed), 0.055f)
        advance(200)
        assertEquals(collapsed, height(), 0.1f)
        assertEquals(2, settlements.size)
        compose.onNodeWithText(raw, useUnmergedTree = true).assertExists()
    }

    @Test
    fun reversalUsesCurrentHeightAndKeepsTheMutationOpenUntilTheFinalTarget() {
        host()
        val collapsed = height()
        disclose("Expand")
        advance(160)
        val interrupted = height()
        assertTrue(interrupted > collapsed + 20f)
        assertTrue(settlements.isEmpty())
        compose.onNodeWithText("Collapse").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertEquals("Retargeting must not synchronously jump to either endpoint", interrupted, height(), 1f)
        advance(120)
        val reversed = height()
        assertTrue(reversed < interrupted && reversed > collapsed)
        advance(500)
        assertEquals(collapsed, height(), 0.1f)
        assertEquals(listOf("user-disclosure:motion-input", "user-disclosure:motion-input"), starts.map { it.first })
        assertEquals("One release after interrupted motion", listOf("user-disclosure:motion-input"), settlements)
    }

    @Test
    fun reducedMotionSnapsSpatialChangesWhileOverlayTransitionStillSettles() {
        host(reduceMotion = true)
        val collapsed = height()
        disclose("Expand")
        val expanded = height()
        assertTrue(expanded > collapsed + 100f)
        assertTrue("Opacity is still transitioning", settlements.isEmpty())
        advance(200)
        assertEquals(expanded, height(), 0.1f)
        advance(300)
        assertEquals(1, settlements.size)
        disclose("Collapse")
        assertEquals(collapsed, height(), 0.1f)
        advance(500)
        assertEquals(collapsed, height(), 0.1f)
        assertEquals(2, settlements.size)
    }

    @Test
    fun firstLayoutHasItsFinalCollapsedHeightAndSnapsUntilSizeReadiness() {
        host(sizeAnimationReady = false)
        val initial = height()
        assertTrue(initial >= 320f)
        advance(600)
        assertEquals(initial, height(), 0.1f)
        disclose("Expand")
        val expanded = height()
        assertTrue(expanded > initial + 100f)
        advance(500)
        assertEquals(expanded, height(), 0.1f)
        disclose("Collapse")
        assertEquals(initial, height(), 0.1f)
    }
}
