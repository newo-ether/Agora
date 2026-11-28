package com.newoether.agora.ui.chat.interaction

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ConversationTodoItem
import com.newoether.agora.model.SubagentSummary
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.ShellConfirmationController
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil
import kotlin.math.floor

/** Real card fades, natural slot geometry and the production row's single ScrollState. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class,
    qualifiers = "w412dp-h800dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedToolsOwnerReplacementTest {
    @get:Rule val compose = createComposeRule()

    private var selected by mutableStateOf("old")
    private var initialContentReady by mutableStateOf(true)
    private var interactionTransition: Pair<String?, Boolean> = null to false
    private val transitions = mutableListOf<Pair<String?, Boolean>>()
    private val gone = mutableListOf<String>()
    private val drafts = QuestionDrafts()
    private var pages by mutableStateOf(emptyMap<String, String>())
    private var height = 0f
    private var inset = 0f
    private var densityScale = 1f
    private lateinit var drawingView: View

    @Test fun initialToolsMeasureWhileHiddenAndEnterOnlyAfterContentIsReady() {
        initialContentReady = false
        mount(productionHost = true)
        val key = "subagent:old:old-0"
        val identity = compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id
        compose.mainClock.autoAdvance = false
        repeat(30) {
            frame()
            assertEquals(0, redPixels("tools-slot:interaction"))
            assertEquals(0, redPixels("tools-slot:todo"))
            assertEquals(0, redPixels("tools-slot:$key"))
        }
        assertTrue("Hidden tools finish their natural layout", slotWidth("interaction") > 0f)
        compose.runOnIdle { initialContentReady = true }
        repeat(30) { frame() }
        assertTrue(redPixels("tools-slot:interaction") > 0)
        assertTrue(redPixels("tools-slot:todo") > 0)
        // Scroll-to retries need clock advancement after the frame-controlled admission checks.
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription(text(R.string.interaction_minimize)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("old worker 5 investigation").performScrollTo()
        compose.waitForIdle()
        assertTrue(redPixels("tools-slot:subagent:old:old-5") > 0)
        assertEquals(identity, compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id)
    }

    @Test fun todoFirstLoadDoesNotPaintUntilEveryAgentIsReady() {
        assertInitialToolsArriveTogether(todoFirst = true)
    }

    @Test fun agentsFirstLoadDoesNotPaintUntilTodoIsReady() {
        assertInitialToolsArriveTogether(todoFirst = false)
    }

    private fun assertInitialToolsArriveTogether(todoFirst: Boolean) {
        var todoLoaded by mutableStateOf(false)
        var agentsLoaded by mutableStateOf(false)
        initialContentReady = false
        mount(width = 840.dp, productionHost = true, waiting = { false },
            todoCount = { if (todoLoaded) 1 else 0 }, agentCount = { if (agentsLoaded) 2 else 0 })
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            if (todoFirst) todoLoaded = true else agentsLoaded = true
        }
        repeat(30) {
            frame()
            assertEquals("A partial Todo snapshot stays invisible", 0, redPixels("tools-slot:todo"))
            assertEquals("A partial family snapshot stays invisible", 0, redPixels("tools-slot:subagent:old:old-0"))
        }
        compose.runOnIdle {
            todoLoaded = true
            agentsLoaded = true
            initialContentReady = true
        }
        var paintedFrames = 0
        val paintStrengths = mutableListOf<List<Int>>()
        repeat(60) { index ->
            frame()
            val todoPainted = redPixels("tools-slot:todo") > 0
            val firstPainted = redPixels("tools-slot:subagent:old:old-0") > 0
            val lastPainted = redPixels("tools-slot:subagent:old:old-1") > 0
            assertEquals("Todo and first agent share admission at frame $index", todoPainted, firstPainted)
            assertEquals("Every loaded agent shares admission at frame $index", firstPainted, lastPainted)
            if (todoPainted) {
                paintedFrames++
                paintStrengths += listOf("todo", "subagent:old:old-0", "subagent:old:old-1")
                    .map { strongestRed("tools-slot:$it") }
            }
        }
        assertTrue("The complete row eventually enters", paintedFrames > 0)
        assertTrue("Every surface uses the same entrance opacity: $paintStrengths",
            paintStrengths.all { it.max() - it.min() <= 2 })
    }

    @Test fun sameFamilyLoadingKeepsAdmittedSubagentWhileParentToolsWait() {
        mount(productionHost = true, family = { "family" },
            waiting = { it == "old" }, todoCount = { if (it == "old") 1 else 0 })
        compose.onNodeWithContentDescription(text(R.string.interaction_minimize)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("family worker 5 investigation").performScrollTo()
        val key = "subagent:family:family-5"
        val identity = compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { initialContentReady = false; selected = "family-0" }
        repeat(30) {
            frame()
            assertTrue(redPixels("tools-slot:$key") > 0)
            assertEquals(identity, compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id)
        }
        compose.runOnIdle { selected = "old" }
        repeat(30) {
            frame()
            assertEquals(0, redPixels("tools-slot:todo"))
            assertEquals(0, redPixels("tools-slot:interaction"))
            assertTrue(redPixels("tools-slot:$key") > 0)
        }
        compose.runOnIdle { initialContentReady = true }
        repeat(30) { frame() }
        assertEquals(identity, compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id)
    }

    @Test fun unrelatedReplacementSnapsHiddenWidthsAndDropsTheOldScrollAnchor() {
        assertReplacement(reducedMotion = false)
    }

    @Test fun reducedMotionKeepsExitOrderingAndSnapsDestinationGeometry() {
        assertReplacement(reducedMotion = true)
    }

    private fun assertReplacement(reducedMotion: Boolean) {
        mount(reducedMotion = reducedMotion, folded = setOf("new"))
        compose.onNodeWithText("old worker 5 investigation").performScrollTo()
        val oldScroll = scrollValue()
        val oldWidth = slotWidth("interaction")
        assertTrue(oldScroll > 0f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = "new" }
        val widths = mutableListOf<Float>()
        val paintedOwners = mutableListOf<String?>()
        var outgoingFrames = 0
        var incomingFrames = 0
        repeat(48) {
            frame()
            val width = slotWidth("interaction")
            widths += width
            paintedOwners += interactionTransition.first.takeIf { interactionTransition.second }
            if (interactionTransition == ("old" to true)) {
                outgoingFrames++
                assertEquals("The fading old editor keeps its reserved width", oldWidth, width, 0.1f)
                assertEquals("Owner replacement must not follow the old anchor", oldScroll, scrollValue(), 1f)
            }
            if (interactionTransition == ("new" to true)) {
                incomingFrames++
                assertTrue("Old card is disposed before incoming paint", "old" in gone)
                assertEquals("Incoming paint starts at the new logical start", 0f, scrollValue(), 0f)
            }
        }
        val finalWidth = slotWidth("interaction")
        assertTrue("Different fold state exercises a different natural width", finalWidth > 0f && finalWidth < oldWidth)
        assertTrue("The old fade is sampled", outgoingFrames > 0)
        assertTrue("The new entrance is sampled", incomingFrames > 0)
        assertTrue("Every painted old/new frame uses a natural width: $widths",
            widths.filterIndexed { index, _ -> paintedOwners[index] != null }.all {
                kotlin.math.abs(it - oldWidth) < 0.1f || kotlin.math.abs(it - finalWidth) < 0.1f
            })
        compose.onNodeWithText("old worker 0 investigation").assertDoesNotExist()
        compose.onNodeWithText("new worker 0 investigation").assertExists()
        assertTrue(height > 0f && inset > height)
        compose.onAllNodes(hasScrollAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).assertCountEquals(1)
    }

    @Test fun incomingTodoAndFamilyCannotPaintWhileTheOldInteractionStillFades() {
        mount(todoCount = { if (it == "new") 3 else 0 }, waiting = { it == "old" },
            agentCount = { if (it == "old") 0 else 3 })
        assertEquals("old" to true, interactionTransition)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = "new" }
        var outgoingSamples = 0
        var incomingSamples = 0
        repeat(40) {
            frame()
            val todoPaint = redPixels("tools-slot:todo")
            val familyPaint = redPixels("tools-slot:subagent:new:new-0")
            if (interactionTransition == ("old" to true)) {
                outgoingSamples++
                assertEquals("A newly introduced Todo waits for the old card's exit", 0, todoPaint)
                assertEquals("A newly introduced family waits for the old card's exit", 0, familyPaint)
            }
            if (todoPaint + familyPaint > 0) {
                incomingSamples++
                assertTrue("Group admission follows actual outgoing disposal", "old" in gone)
                assertFalse(interactionTransition == ("old" to true))
                assertEquals(0f, scrollValue(), 0f)
            }
        }
        assertTrue("The test observes the outgoing fade", outgoingSamples > 0)
        assertTrue("The production destination actually paints", incomingSamples > 0)
        assertEquals("Empty outgoing interaction leaves no ghost width", 0f, slotWidth("interaction"), 0f)
        assertEquals(12f, slotLeft("todo") - rowLeft(), 1f)
        compose.onAllNodes(hasScrollAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).assertCountEquals(1)
    }

    @Test fun rapidReplacementAdmitsOnlyTheLatestOwner() {
        mount(folded = setOf("middle", "latest"))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = "middle" }
        repeat(3) { frame() }
        assertFalse(transitions.contains("middle" to true))
        compose.runOnIdle { selected = "latest" }
        repeat(48) {
            frame()
            assertFalse("An interrupted destination never becomes visible", transitions.contains("middle" to true))
            compose.onNodeWithText("middle worker 0 investigation").assertDoesNotExist()
        }
        assertEquals("latest" to true, interactionTransition)
        assertEquals(0f, scrollValue(), 0f)
        compose.onNodeWithText("latest worker 0 investigation").assertExists()
        compose.onNodeWithText("old worker 0 investigation").assertDoesNotExist()
        assertTrue(gone.contains("old"))
    }

    @Test fun familySelectionKeepsRightScrolledSubagentAnchoredThroughPrefixRemoval() {
        mount(todoCount = { if (it == "old") 1 else 0 }, waiting = { it == "old" },
            family = { "family" })
        val key = "subagent:family:family-3"
        compose.onNodeWithText("family worker 3 investigation").performScrollTo()
        val identity = compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id
        val left = slotLeft(key)
        val oldScroll = scrollValue()
        val rowStart = rowLeft()
        assertTrue("Leading Todo is fully outside the viewport",
            slotLeft("todo") + slotWidth("todo") <= rowStart)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = "family-3" }
        val widths = mutableListOf<Float>()
        repeat(48) {
            frame()
            assertEquals(identity, compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id)
            assertEquals("Offscreen prefix changes cannot move the retained Subagent", left, slotLeft(key), 1f)
            assertTrue("The fixed screen anchor stays painted", redPixels("tools-slot:$key") > 0)
            widths += slotWidth("todo")
        }
        assertEquals(0f, slotWidth("interaction"), 0f)
        assertEquals(0f, widths.last(), 0f)
        assertTrue("The existing width animation remains", widths.any { it > 0f && it < widths.first() })
        assertTrue("Scroll compensates prefix removal rather than seeking logical start",
            scrollValue() > 0f && scrollValue() < oldScroll)
        val settledScroll = scrollValue()
        compose.runOnIdle { selected = "family-4" }
        repeat(20) {
            frame()
            assertEquals(left, slotLeft(key), 1f)
            assertEquals(settledScroll, scrollValue(), 1f)
            assertEquals(identity, compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id)
        }
    }

    @Test fun returningToParentRestoresTodoAndPendingQuestionWithoutMovingTheSubagent() {
        assertParentReturn(reducedMotion = false)
    }

    @Test fun reducedMotionParentReturnKeepsTheSameSubagentAnchor() {
        assertParentReturn(reducedMotion = true)
    }

    private fun assertParentReturn(reducedMotion: Boolean) {
        mount(reducedMotion = reducedMotion, todoCount = { if (it == "old") 1 else 0 },
            waiting = { it == "old" }, family = { "family" }, productionHost = true)
        compose.onNodeWithContentDescription(text(R.string.interaction_minimize)).performClick()
        compose.waitForIdle()
        val key = "subagent:family:family-3"
        compose.onNodeWithText("family worker 3 investigation").performScrollTo()
        val identity = compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id
        val left = slotLeft(key)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = "family-3" }
        repeat(48) {
            frame()
            assertEquals("Entering a child preserves the visible Subagent", left, slotLeft(key), 1f)
        }
        val childScroll = scrollValue()
        assertTrue(childScroll > 0f)
        assertEquals(0f, slotWidth("interaction"), 0f)
        assertEquals(0f, slotWidth("todo"), 0f)
        compose.runOnIdle { selected = "old" }
        val todoWidths = mutableListOf<Float>()
        repeat(48) {
            frame()
            assertEquals(identity, compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id)
            assertEquals("Returning to a pending parent cannot reveal its old question", left, slotLeft(key), 1f)
            assertTrue("The retained Subagent stays visible", redPixels("tools-slot:$key") > 0)
            assertTrue("Returning to the parent never seeks logical start", scrollValue() > 0f)
            todoWidths += slotWidth("todo")
        }
        assertTrue(slotWidth("interaction") > 0f)
        assertTrue(todoWidths.last() > 0f)
        assertTrue("The existing single scroller reserves the restored prefix", scrollValue() > childScroll)
        if (!reducedMotion) assertTrue("Restored Todo keeps its ordinary width animation",
            todoWidths.any { it > 0f && it < todoWidths.last() })
        compose.onAllNodes(hasScrollAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).assertCountEquals(1)
    }

    @Test fun sixteenAgentsWithOnlyTodoKeepTheReadingAnchorAcrossRepeatedParentReturns() {
        assertTodoOnlyParentReturn(atEnd = false, reducedMotion = false)
    }

    @Test fun sixteenAgentsAtTheRightEdgeDoNotJitterAcrossRepeatedParentReturns() {
        assertTodoOnlyParentReturn(atEnd = true, reducedMotion = false)
    }

    @Test fun reducedMotionKeepsSixteenAgentsAnchoredAtTheRightEdgeOnParentReturn() {
        assertTodoOnlyParentReturn(atEnd = true, reducedMotion = true)
    }

    private fun assertTodoOnlyParentReturn(atEnd: Boolean, reducedMotion: Boolean) {
        mount(reducedMotion = reducedMotion, todoCount = { if (it == "old") 1 else 0 },
            waiting = { false }, family = { "family" }, agentCount = { 16 },
            onSelect = { selected = it })
        val index = if (atEnd) 15 else 11
        val key = "subagent:family:family-$index"
        compose.onNodeWithText("family worker $index investigation").performScrollTo()
        compose.mainClock.autoAdvance = false
        val row = compose.onNodeWithTag("conversation-tools-row")
        row.performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.2f, 0f))
        }
        repeat(2) { frame() }
        row.performTouchInput {
            advanceEventTime(200)
            up()
        }
        repeat(2) { frame() }
        if (atEnd) {
            // Finish the semantic scroll before a tap, which otherwise only stops its animation.
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("conversation-tools-row").performSemanticsAction(SemanticsActions.ScrollBy) {
                it(100_000f, 0f)
            }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            val range = compose.onNodeWithTag("conversation-tools-row").fetchSemanticsNode()
                .config[SemanticsProperties.HorizontalScrollAxisRange]
            assertEquals("The fixture reaches the real ScrollState maximum", range.maxValue(), range.value(), 0f)
        }
        val identity = compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id
        val left = slotLeft(key)
        assertTrue("Todo starts fully offscreen", slotLeft("todo") + slotWidth("todo") < rowLeft())
        compose.mainClock.autoAdvance = false
        repeat(3) { cycle ->
            compose.onNodeWithText("family worker $index investigation").performClick()
            repeat(48) {
                frame()
                assertEquals("Child selection keeps the retained anchor in cycle $cycle", left, slotLeft(key), 1f)
            }
            assertEquals("The capsule actually opens its child", "family-$index", selected)
            assertEquals(0f, slotWidth("todo"), 0f)
            compose.runOnIdle { selected = "old" }
            repeat(48) { frameIndex ->
                frame()
                assertEquals("Parent return must not drift or jitter in cycle $cycle, frame $frameIndex",
                    left, slotLeft(key), 1f)
                assertEquals(identity, compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id)
            }
            assertTrue("Parent Todo is restored", slotWidth("todo") > 0f)
        }
    }

    @Test fun returningToParentAtLogicalStartAllowsNaturalPrefixInsertion() {
        mount(todoCount = { if (it == "old") 1 else 0 }, waiting = { false }, family = { "family" })
        val key = "subagent:family:family-0"
        val identity = compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = "family-0" }
        repeat(48) { frame() }
        val childLeft = slotLeft(key)
        assertEquals(0f, scrollValue(), 0f)
        compose.runOnIdle { selected = "old" }
        val lefts = mutableListOf<Float>()
        repeat(48) {
            frame()
            lefts += slotLeft(key)
            assertEquals(identity, compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id)
            assertEquals("Logical start stays at start during ordinary insertion", 0f, scrollValue(), 0f)
        }
        val parentLeft = lefts.last()
        assertTrue(parentLeft > childLeft)
        assertTrue("Insertion moves the retained capsule naturally", lefts.any { it > childLeft && it < parentLeft })
    }

    @Test fun interactionExpansionAndInterruptedFoldKeepTheRoundedCardAndNeighborInStep() {
        mount(todoCount = { 0 }, productionHost = true)
        val key = "subagent:old:old-0"
        val identity = compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id
        val fullWidth = slotWidth("interaction")
        val minimize = RuntimeEnvironment.getApplication().getString(R.string.interaction_minimize)
        val expand = RuntimeEnvironment.getApplication().getString(R.string.interaction_expand)
        compose.onNodeWithContentDescription(minimize).performClick()
        compose.waitForIdle()
        val foldedWidth = slotWidth("interaction")
        assertTrue("The real card morph has distinct endpoints", fullWidth > foldedWidth + 40f)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(expand).performClick()
        val widths = mutableListOf<Float>()
        repeat(26) {
            frame()
            widths += slotWidth("interaction")
            assertInteractionReservation(key, identity)
        }
        assertTrue("Real expansion has continuous intermediate widths",
            widths.any { it > foldedWidth + 2f && it < fullWidth - 2f })
        assertEquals(fullWidth, widths.last(), 1f)
        compose.onNodeWithContentDescription(minimize).performClick()
        repeat(8) { frame(); assertInteractionReservation(key, identity) }
        // During the morph, retained full-size descendants still have their original hit geometry.
        // Invoke the capsule's exact accessibility action rather than tapping an overlapping child.
        compose.onNodeWithContentDescription(expand).performSemanticsAction(SemanticsActions.OnClick) { click ->
            assertTrue("The interrupted expansion action is accepted", click())
        }
        // Manual-clock actions need an applied snapshot and a composition frame before reading
        // the new folded-state semantics, just like the framewise geometry assertions below.
        frame()
        assertInteractionReservation(key, identity)
        compose.onNodeWithContentDescription(minimize).assertExists()
        repeat(25) { frame(); assertInteractionReservation(key, identity) }
        assertEquals(fullWidth, slotWidth("interaction"), 1f)
        compose.onNodeWithText("Question old?").assertExists()
        compose.onAllNodes(hasScrollAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).assertCountEquals(0)
    }

    private fun assertInteractionReservation(key: String, identity: Int) {
        val card = compose.onNodeWithTag("interaction-card").getUnclippedBoundsInRoot()
        val slot = compose.onNodeWithTag("tools-slot:interaction").getUnclippedBoundsInRoot()
        val width = (card.right - card.left).value
        assertEquals("The slot follows the card's own measured width without lag", width + 8f,
            (slot.right - slot.left).value, 1f)
        assertEquals("The neighbor reserves the same changing width", slot.right.value, slotLeft(key), 1f)
        assertEquals(identity, compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id)
        assertEquals(0f, scrollValue(), 0f)
        val bounds = compose.onNodeWithTag("interaction-card").fetchSemanticsNode().boundsInRoot
        val host = compose.onNodeWithTag("replacement-host").fetchSemanticsNode().boundsInRoot
        val bitmap = Bitmap.createBitmap(ceil(host.width).toInt(), ceil(host.height).toInt(), Bitmap.Config.ARGB_8888)
        try {
            compose.runOnUiThread {
                val canvas = Canvas(bitmap)
                canvas.translate(-host.left, -host.top)
                drawingView.draw(canvas)
            }
            val x = (bounds.right - host.left - 2f * densityScale).toInt().coerceIn(0, bitmap.width - 1)
            val top = (bounds.top - host.top + 2f * densityScale).toInt().coerceIn(0, bitmap.height - 1)
            val center = (bounds.center.y - host.top - 4f * densityScale).toInt().coerceIn(0, bitmap.height - 1)
            val cornerGreen = Color(bitmap.getPixel(x, top)).green
            val endGreen = Color(bitmap.getPixel(x, center)).green
            assertTrue("The right edge retains the card's curved corner", cornerGreen > 0.8f)
            assertTrue("The rounded end still paints beneath the viewport edge fade: corner=$cornerGreen end=$endGreen",
                cornerGreen - endGreen > 0.08f)
            val bodyX = (minOf(bounds.center.x, host.right - 40f * densityScale) - host.left)
                .toInt().coerceIn(0, bitmap.width - 1)
            assertTrue("The card remains opaque away from the viewport edge fade",
                Color(bitmap.getPixel(bodyX, center)).green < 0.2f)
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun visibleTodoRemovalMovesRetainedFamilyLeftWithNaturalIntermediateFrames() {
        mount(todoCount = { if (it == "old") 1 else 0 }, waiting = { false },
            family = { "family" })
        val key = "subagent:family:family-0"
        val identity = compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id
        val before = slotLeft(key)
        assertTrue(redPixels("tools-slot:todo") > 0)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = "family-0" }
        val lefts = mutableListOf<Float>()
        repeat(48) {
            frame()
            lefts += slotLeft(key)
            assertEquals(identity, compose.onNodeWithTag("tools-slot:$key").fetchSemanticsNode().id)
            assertTrue("Selection never replays the family fade", redPixels("tools-slot:$key") > 0)
            assertEquals("Visible leading content keeps the viewport at logical start", 0f, scrollValue(), 0f)
        }
        val after = lefts.last()
        assertEquals(12f, after - rowLeft(), 1f)
        assertTrue("Visible removal moves naturally rather than jumping: $lefts",
            lefts.any { it < before - 1f && it > after + 1f })
        assertTrue("The removal travels only toward its new natural position: $lefts",
            lefts.zipWithNext().all { (a, b) -> b <= a + 1f })
        assertEquals(0f, slotWidth("todo"), 0f)
    }

    @Test fun outgoingTodoKeepsItsPositionUntilAnUnrelatedReplacementIsFullyHidden() {
        mount(todoCount = { if (it == "old") 1 else 0 }, waiting = { false },
            agentCount = { if (it == "old") 1 else 0 })
        val oldLeft = slotLeft("todo")
        val oldWidth = slotWidth("todo")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = "new" }
        var paintedFrames = 0
        repeat(36) {
            frame()
            if (redPixels("tools-slot:todo") > 0) {
                paintedFrames++
                assertEquals("Centering cannot move a fading outgoing Todo", oldLeft, slotLeft("todo"), 0.1f)
                assertEquals(oldWidth, slotWidth("todo"), 0.1f)
                assertEquals(0f, scrollValue(), 0f)
            }
        }
        assertTrue("Outgoing paint is actually sampled", paintedFrames > 0)
        assertEquals(0f, slotWidth("todo"), 0f)
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp-mdpi")
    fun fittingDestinationCentersOnceAndKeepsItsNaturalWidthsDuringEntrance() {
        mount(width = 900.dp, folded = setOf("old", "new"), agentCount = { 1 })
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = "new" }
        val incomingLefts = mutableListOf<Float>()
        val incomingWidths = mutableListOf<Float>()
        val todoWidths = mutableListOf<Float>()
        repeat(40) {
            frame()
            if (interactionTransition == ("new" to true)) {
                val bounds = compose.onNodeWithTag("tools-slot:interaction").fetchSemanticsNode().boundsInRoot
                incomingLefts += bounds.left / densityScale
                incomingWidths += bounds.width / densityScale
                todoWidths += compose.onNodeWithTag("tools-slot:todo").fetchSemanticsNode().boundsInRoot.width / densityScale
            }
        }
        assertTrue("The incoming fade is observed", incomingLefts.isNotEmpty())
        assertTrue("A fitting row is centered", incomingLefts.last() > rowLeft() + 12f)
        val row = compose.onNodeWithTag("conversation-tools-row").getUnclippedBoundsInRoot()
        val center = (row.left.value + row.right.value) / 2f
        val finalWidth = incomingWidths.last()
        incomingLefts.indices.forEach { index ->
            val scale = incomingWidths[index] / finalWidth
            val normalizedLeft = center + (incomingLefts[index] - center) / scale
            assertEquals("Only the shared bottom-center scale moves incoming paint",
                incomingLefts.last(), normalizedLeft, 0.1f)
            assertEquals("Both natural slot widths follow that same scale",
                todoWidths.last() * scale, todoWidths[index], 0.1f)
        }
        assertEquals(0f, scrollValue(), 0f)
        compose.onAllNodes(hasScrollAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).assertCountEquals(1)
    }

    @Test fun sameFamilyTodoReplacementReservesItsNaturalWidthBeforePaint() {
        mount(productionHost = true, family = { "family" }, waiting = { false },
            todoCount = { if (it == "old") 1 else 100 })
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = "family-0" }
        val paintedWidths = mutableListOf<Float>()
        repeat(64) {
            frame()
            val count = compose.onAllNodesWithText("0 / 100").fetchSemanticsNodes().singleOrNull()
            if (count != null && redPixels("tools-slot:todo") > 0) {
                val firstAgent = slotLeft("subagent:family:family-0")
                val countRight = count.boundsInRoot.right / densityScale
                assertTrue("Incoming Todo content must not overlap the retained first agent",
                    countRight <= firstAgent)
                paintedWidths += slotWidth("todo")
            }
        }
        assertTrue("The incoming Todo fade is sampled", paintedWidths.isNotEmpty())
        assertTrue("Incoming Todo paint starts only at its settled width: $paintedWidths",
            paintedWidths.all { kotlin.math.abs(it - paintedWidths.last()) < 0.1f })
    }

    @Test fun sameOwnerTodoInsertionStillAnimatesAndPreservesTheReadingAnchor() {
        var todo by mutableStateOf(false)
        mount(todoCount = { if (todo) 1 else 0 }, waiting = { false })
        compose.onNodeWithText("old worker 5 investigation").performScrollTo()
        val before = scrollValue()
        val identity = compose.onNodeWithTag("tools-slot:subagent:old:old-0").fetchSemanticsNode().id
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { todo = true }
        val widths = mutableListOf<Float>()
        repeat(38) {
            frame()
            widths += slotWidth("todo")
            assertEquals(identity,
                compose.onNodeWithTag("tools-slot:subagent:old:old-0").fetchSemanticsNode().id)
        }
        val finalWidth = widths.last()
        assertTrue(finalWidth > 0f)
        assertTrue("Same-owner insertion retains intermediate occupied widths: $widths",
            widths.any { it > 0f && it < finalWidth })
        assertEquals("The one shared scroller follows same-owner insertion", before + finalWidth * densityScale,
            scrollValue(), 2f)
        assertTrue("Same-owner insertion does not reset logical start", scrollValue() > before)
    }

    private fun mount(
        width: Dp = 412.dp,
        reducedMotion: Boolean = false,
        folded: Set<String> = emptySet(),
        todoCount: (String) -> Int = { 1 },
        waiting: (String) -> Boolean = { true },
        family: (String) -> String = { it },
        agentCount: (String) -> Int = { 6 },
        productionHost: Boolean = false,
        onSelect: (String) -> Unit = {},
    ) {
        val questions = MutableStateFlow(if (waiting("old")) listOf(AskUserController.Request(
            id = 1, conversationId = "old", question = "Question old?", options = listOf("A", "B"),
            allowMultiple = false, blocking = false,
        )) else emptyList())
        val askUser = mockk<AskUserController>(relaxed = true)
        val confirmations = mockk<ShellConfirmationController>(relaxed = true)
        val viewModel = mockk<ChatViewModel>(relaxed = true)
        every { viewModel.askUser } returns askUser
        every { viewModel.shellConfirmation } returns confirmations
        every { askUser.requests } returns questions
        every { confirmations.pendingShellCommand } returns
            MutableStateFlow<ShellConfirmationController.PendingShellCommand?>(null)
        compose.setContent {
            drawingView = LocalView.current
            densityScale = LocalDensity.current.density
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reducedMotion)) {
                MaterialTheme(colorScheme = lightColorScheme(
                    surfaceContainerHigh = Color.Red, secondaryContainer = Color.Red,
                    onSurface = Color.Black, onSurfaceVariant = Color.Black,
                )) {
                    Box(Modifier.width(width).height(480.dp).background(Color.White).testTag("replacement-host")) {
                        val owner = selected
                        val root = family(owner)
                        val requests = if (waiting(owner)) listOf(AskUserController.Request(
                            id = owner.hashCode().toLong(), conversationId = owner,
                            question = "Question $owner?", options = listOf("A", "B"),
                            allowMultiple = false, blocking = false,
                        )) else emptyList()
                        if (productionHost) {
                            ChatUserInteractionBarHost(viewModel, owner, false) { present, reveal, expanded, expansion, interaction ->
                                ConversationToolsRow(
                                    owner, List(todoCount(owner)) { ConversationTodoItem("Task $it") },
                                    List(agentCount(owner)) {
                                        SubagentSummary("$root-$it", "$root worker $it investigation", "model", root, "idle")
                                    }, onSelect, { height = it }, familyId = root, hasInteraction = present,
                                    onMessageInsetChanged = { inset = it }, interactionRevealKey = reveal,
                                    contentReady = initialContentReady,
                                    interactionExpanded = expanded, interactionExpansion = expansion,
                                ) { modifier, reportHeight, reportInset, ready, animateEntry, reportTransition ->
                                    interaction(modifier.testTag("interaction-card"), reportHeight, reportInset,
                                        ready, animateEntry, reportTransition)
                                }
                            }
                        } else ConversationToolsRow(
                            owner, List(todoCount(owner)) { ConversationTodoItem("Task $it") },
                            List(agentCount(owner)) {
                                SubagentSummary("$root-$it", "$root worker $it investigation", "model", root, "idle")
                            }, onSelect, { height = it }, familyId = root, hasInteraction = requests.isNotEmpty(),
                            onMessageInsetChanged = { inset = it },
                        ) { modifier, reportHeight, reportInset, ready, animateEntry, reportTransition ->
                            UserInteractionBar(
                                owner, userInteractions(owner, requests, null), false, {}, {}, { _, _, _ -> },
                                reportHeight, folded, { _, _ -> }, drafts, pages,
                                { cardOwner, page -> pages = pages + (cardOwner to page) }, { gone += it },
                                modifier = modifier, inSharedRow = true, onMessageInsetChanged = reportInset,
                                appearanceReady = ready, animateEntry = animateEntry,
                                onTransitionChanged = { cardOwner, visible ->
                                    interactionTransition = cardOwner to visible
                                    transitions += interactionTransition
                                    reportTransition(cardOwner, visible)
                                },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun frame() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun slotWidth(key: String): Float {
        val bounds = compose.onNodeWithTag("tools-slot:$key").getUnclippedBoundsInRoot()
        return (bounds.right - bounds.left).value
    }

    private fun slotLeft(key: String): Float = compose.onNodeWithTag("tools-slot:$key")
        .getUnclippedBoundsInRoot().left.value

    private fun rowLeft(): Float = compose.onNodeWithTag("conversation-tools-row")
        .getUnclippedBoundsInRoot().left.value

    private fun scrollValue(): Float = compose.onNodeWithTag("conversation-tools-row")
        .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()

    private fun redPixels(tag: String): Int = sampleRed(tag, strongest = false)

    private fun strongestRed(tag: String): Int = sampleRed(tag, strongest = true)

    private fun sampleRed(tag: String, strongest: Boolean): Int {
        val node = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull() ?: return 0
        val bounds = node.boundsInRoot
        if (bounds.width <= 0f || bounds.height <= 0f) return 0
        val host = compose.onNodeWithTag("replacement-host").fetchSemanticsNode().boundsInRoot
        val left = floor(bounds.left - host.left).toInt().coerceIn(0, ceil(host.width).toInt())
        val right = ceil(bounds.right - host.left).toInt().coerceIn(left, ceil(host.width).toInt())
        val top = floor(bounds.top - host.top).toInt().coerceIn(0, ceil(host.height).toInt())
        val bottom = ceil(bounds.bottom - host.top).toInt().coerceIn(top, ceil(host.height).toInt())
        if (right == left || bottom == top) return 0
        val bitmap = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        try {
            compose.runOnUiThread {
                val canvas = Canvas(bitmap)
                canvas.translate(-host.left - left, -host.top - top)
                drawingView.draw(canvas)
            }
            var count = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = Color(bitmap.getPixel(x, y))
                if (strongest) count = maxOf(count, ((pixel.red - pixel.green) * 255f).toInt())
                else if (pixel.red - pixel.green > 0.08f && pixel.red - pixel.blue > 0.08f) count++
            }
            return count
        } finally {
            bitmap.recycle()
        }
    }
}
