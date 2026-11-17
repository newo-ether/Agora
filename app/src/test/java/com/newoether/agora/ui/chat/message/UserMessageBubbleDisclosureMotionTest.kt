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

    private fun host(reduceMotion: Boolean = false, sizeAnimationReady: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = reduceMotion)) {
                MaterialTheme {
                    Column {
                        // The wrap-content host measures the production bubble's animated outer size.
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
                            )
                        }
                        Spacer(Modifier.width(1.dp).height(1.dp).testTag("following-message"))
                    }
                }
            }
        }
        compose.waitForIdle()
        // Overflow is published during measurement; its disclosure needs the next recomposition.
        compose.mainClock.advanceTimeUntil(timeoutMillis = 1_000) {
            compose.onAllNodesWithText("Expand").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun height(): Float {
        val bubble = compose.onNodeWithTag("bubble-size").fetchSemanticsNode().boundsInRoot
        val following = compose.onNodeWithTag("following-message").fetchSemanticsNode().boundsInRoot
        assertEquals("Following content tracks the bubble's occupied layout height",
            bubble.bottom, following.top, 0.1f)
        return bubble.height
    }

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds, ignoreFrameDuration = true)
        compose.waitForIdle()
    }

    private fun disclose(label: String) {
        compose.onNodeWithText(label).performClick()
        Snapshot.sendApplyNotifications()
        // One frame recomposes the new target; measurement then schedules the size animation.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        // The following frame establishes play time zero rather than consuming tween time.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test
    fun bothDirectionsUseEaseInAndContinuouslyMoveFollowingContentWithTheOccupiedHeight() {
        host()
        val firstLayout = height()
        advance(600)
        val collapsed = height()
        assertEquals("A ready bubble still starts at its final collapsed height", firstLayout, collapsed, 0.1f)
        disclose("Expand")
        assertEquals("Expansion begins at its existing occupied height", collapsed, height(), 1f)
        advance(100)
        val expandingEarly = height()
        advance(150)
        val expandingMidpoint = height()
        advance(150)
        val expandingLate = height()
        advance(200)
        val expanded = height()
        assertTrue("Occupied height grows continuously through expansion",
            collapsed < expandingEarly && expandingEarly < expandingMidpoint &&
                expandingMidpoint < expandingLate && expandingLate < expanded)
        assertTrue("Expansion must reveal the full twenty-line body", expanded > collapsed + 100f)
        assertEquals("Ease-in has completed about 32% of expansion at 250ms",
            0.3154f, (expandingMidpoint - collapsed) / (expanded - collapsed), 0.055f)
        val body = compose.onNodeWithText(raw, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val footer = compose.onNode(hasClickAction() and hasText("Collapse")).fetchSemanticsNode().boundsInRoot
        assertTrue("Expanded disclosure follows all original text", footer.top >= body.bottom)
        assertTrue("Full expanded text remains inside the bubble", expanded >= body.height + footer.height)
        advance(500)
        assertEquals("Expansion finishes at a stable full height", expanded, height(), 0.1f)

        disclose("Collapse")
        assertEquals("Collapse begins at its existing occupied height", expanded, height(), 1f)
        advance(100)
        val collapsingEarly = height()
        advance(150)
        val collapsingMidpoint = height()
        advance(150)
        val collapsingLate = height()
        assertTrue("Occupied height shrinks continuously through collapse",
            expanded > collapsingEarly && collapsingEarly > collapsingMidpoint &&
                collapsingMidpoint > collapsingLate && collapsingLate > collapsed)
        assertEquals("Ease-in has completed about 32% of collapse at 250ms",
            0.3154f, (expanded - collapsingMidpoint) / (expanded - collapsed), 0.055f)
        advance(200)
        assertEquals("Collapse returns to its original viewport height", collapsed, height(), 0.1f)
        compose.onNodeWithText(raw, useUnmergedTree = true).assertExists()
        advance(500)
        assertEquals("Collapse remains settled", collapsed, height(), 0.1f)
    }

    @Test
    fun reducedMotionSnapsBothDisclosureDirectionsWithoutIntermediateHeight() {
        host(reduceMotion = true)
        val collapsed = height()
        disclose("Expand")
        val expanded = height()
        assertTrue(expanded > collapsed + 100f)
        advance(250)
        assertEquals("Reduced Motion is already fully expanded", expanded, height(), 0.1f)
        advance(350)
        assertEquals(expanded, height(), 0.1f)
        disclose("Collapse")
        assertEquals("Reduced Motion is immediately collapsed", collapsed, height(), 0.1f)
        advance(250)
        assertEquals(collapsed, height(), 0.1f)
        advance(350)
        assertEquals(collapsed, height(), 0.1f)
    }

    @Test
    fun firstLayoutHasItsFinalCollapsedHeightAndKeepsDisclosureUnanimatedUntilReady() {
        host(sizeAnimationReady = false)
        val initial = height()
        assertTrue("First layout already contains the 320dp preview", initial >= 320f)
        compose.onNodeWithText("Expand").assertIsDisplayed()
        advance(250)
        assertEquals("Initial layout must not grow from zero", initial, height(), 0.1f)
        advance(350)
        assertEquals(initial, height(), 0.1f)
        disclose("Expand")
        val expanded = height()
        assertTrue(expanded > initial + 100f)
        advance(250)
        assertEquals("Before size readiness there is no disclosure animation", expanded, height(), 0.1f)
        disclose("Collapse")
        assertEquals(initial, height(), 0.1f)
    }
}
