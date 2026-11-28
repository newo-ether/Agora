package com.newoether.agora.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.StableMessageList
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/** Exercises the production list, hydration, disclosure callbacks and mutation anchor together. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h1100dp-mdpi")
class MessageListDisclosureMotionTest {
    @get:Rule val compose = createComposeRule()
    private val raw = (1..20).joinToString("\n") { "Retained input line $it" }
    private val followingText = "The following message stays mounted"
    private lateinit var state: LazyListState
    private var bodyIdentity = 0
    private var followingIdentity = 0
    private var bodySize = IntSize.Zero
    private var bodyLayoutHeight = 0
    private var previewHeight = 0
    private var footerHeight = 0
    private val revealHeight get() = bodyLayoutHeight - previewHeight + footerHeight

    private data class Frame(
        val height: Int,
        val offset: Int,
        val followingOffset: Int,
        val followingTextTop: Float,
        val firstKey: Any,
        val firstOffset: Int,
    )

    private fun mount() {
        val rows = (0 until 6).map { index ->
            ChatMessage(id = "before-$index", text = "Earlier message $index", participant = Participant.USER)
        } + listOf(
            ChatMessage(id = "disclosure", text = raw, participant = Participant.USER),
            ChatMessage(id = "following", text = followingText, participant = Participant.USER),
        ) + (0 until 18).map { index ->
            ChatMessage(id = "after-$index", text = "Later message $index", participant = Participant.USER)
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            state = rememberLazyListState(initialFirstVisibleItemIndex = 6, initialFirstVisibleItemScrollOffset = 24)
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = false)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).height(800.dp).testTag("disclosure-list")) {
                        MessageList(
                            messages = StableMessageList(rows),
                            conversationId = "disclosure-owner",
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            state = state,
                            viewportHeight = 800,
                            initialMessage = { id -> rows.firstOrNull { it.id == id } },
                            observeMessage = { id -> flowOf(rows.firstOrNull { it.id == id }) },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        // Measurement publishes overflow before the next composition adds the controls.
        compose.mainClock.advanceTimeUntil(timeoutMillis = 1_000) {
            compose.onAllNodes(hasText("Expand") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        advance(800)
        bodyIdentity = body().fetchSemanticsNode().id
        followingIdentity = following().fetchSemanticsNode().id
        bodySize = textLayout().size
        val bodyLayout = requireNotNull(body().fetchSemanticsNode().layoutInfo.parentInfo)
        val viewport = requireNotNull(bodyLayout.parentInfo)
        bodyLayoutHeight = bodyLayout.height
        previewHeight = viewport.height
        // The hidden footer still has a layout node beneath its cleared semantics.
        footerHeight = compose.onAllNodes(
            SemanticsMatcher("The footer is the other direct child of the body viewport") {
                it.layoutInfo.parentInfo == viewport && it.layoutInfo != bodyLayout
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().map { it.layoutInfo }.distinct().single().height
        assertEquals("The physical preview is 320dp at mdpi", 320, previewHeight)
        assertTrue("The complete body has a measured Collapse footer", footerHeight > 0)
        println("Disclosure geometry: text=${bodySize.height}, body=$bodyLayoutHeight, " +
            "preview=$previewHeight, footer=$footerHeight")
        val initial = frame()
        assertEquals("The fixture starts inside the real disclosure turn", "disclosure", initial.firstKey)
        assertEquals("The initial reader offset is nonzero", 24, initial.firstOffset)
        assertTrue("The complete text really overflows the physical preview", bodySize.height > 420)
    }

    private fun body() = compose.onNodeWithText(raw, useUnmergedTree = true)
    private fun following() = compose.onNodeWithText(followingText, useUnmergedTree = true)

    private fun textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        body().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun frame(): Frame {
        val textNode = body().fetchSemanticsNode()
        val followingNode = following().fetchSemanticsNode()
        assertEquals("Disclosure never replaces the original text node", bodyIdentity, textNode.id)
        assertEquals("The following row never remounts during disclosure", followingIdentity, followingNode.id)
        val layout = textLayout()
        assertEquals("The entire original text remains mounted", raw, layout.layoutInput.text.text)
        assertEquals("The mounted body keeps stable final text constraints", bodySize, layout.size)
        return compose.runOnIdle {
            val items = state.layoutInfo.visibleItemsInfo
            val target = items.single { it.key == "disclosure" }
            val next = items.single { it.key == "following" }
            assertEquals("The next lazy item follows this frame's occupied height",
                target.offset + target.size, next.offset)
            Frame(target.size, target.offset, next.offset, followingNode.boundsInRoot.top,
                items.first { it.index == state.firstVisibleItemIndex }.key,
                state.firstVisibleItemScrollOffset)
        }
    }

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds, ignoreFrameDuration = true)
        compose.waitForIdle()
    }

    private fun nextFrame(): Frame {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        return frame()
    }

    private fun disclose(label: String): Frame {
        val before = frame()
        val beforeTime = compose.mainClock.currentTime
        // Android performClick injects touch events and advances their event timeline.
        compose.onNode(hasText(label) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        val afterInput = frame()
        assertEquals("The semantic callback does not advance the animation clock",
            beforeTime, compose.mainClock.currentTime)
        assertEquals("Changing the target cannot synchronously jump occupied height", before.height, afterInput.height)
        assertAnchor(before, afterInput)
        // Observe each startup frame too, including bounded momentum during a reversal.
        var previous = afterInput
        repeat(2) {
            val current = nextFrame()
            assertAnchor(before, current)
            assertFollowingTracks(previous, current)
            assertTrue("Target recomposition retains continuous frame-sized movement",
                abs(current.height - previous.height) <= revealHeight * 0.2f + 2f)
            previous = current
        }
        return previous
    }

    private fun assertAnchor(expected: Frame, actual: Frame) {
        assertEquals("The mutation keeps the retained first-visible turn", expected.firstKey, actual.firstKey)
        assertEquals("Disclosure never rebases the reader's scroll offset", expected.firstOffset, actual.firstOffset)
        assertEquals("The retained turn stays at its pre-mutation screen position", expected.offset, actual.offset)
    }

    private fun assertFollowingTracks(before: Frame, after: Frame) {
        assertEquals("The following text moves by the measured occupied-height change",
            (after.followingOffset - before.followingOffset).toFloat(),
            after.followingTextTop - before.followingTextTop, 1f)
    }

    private fun sampleTransition(start: Frame, targetHeight: Int, anchor: Frame): List<Frame> {
        val result = mutableListOf(start)
        val direction = if (targetHeight > start.height) 1 else -1
        // At mdpi the 400ms tween spans 25 normal 16ms Compose display frames.
        repeat(25) { index ->
            val current = nextFrame()
            val previous = result.last()
            assertAnchor(anchor, current)
            assertFollowingTracks(previous, current)
            assertTrue("No opposite movement at display frame $index",
                direction * (current.height - previous.height) >= 0)
            assertTrue("No target-height jump at display frame $index",
                abs(current.height - previous.height) <= abs(targetHeight - start.height) * 0.2f + 2f)
            result += current
        }
        assertEquals("The reveal reaches its final height at 400ms", targetHeight, result.last().height)
        return result
    }

    private fun sampleInterruptedTransition(start: Frame, targetHeight: Int, anchor: Frame): Frame {
        var previous = start
        var stableFrames = 0
        var elapsedFrames = 0
        // Thinking's interrupted tween retains velocity through its fallback spring.
        while (stableFrames < 4 && elapsedFrames < 100) {
            val current = nextFrame()
            assertAnchor(anchor, current)
            assertFollowingTracks(previous, current)
            assertTrue("Reversal keeps continuous bounded motion at frame $elapsedFrames",
                abs(current.height - previous.height) <= revealHeight * 0.2f + 2f)
            stableFrames = if (current.height == targetHeight) stableFrames + 1 else 0
            previous = current
            elapsedFrames++
        }
        assertEquals("The interrupted reveal settles at the measured endpoint", 4, stableFrames)
        return previous
    }

    @Test
    fun realListMovesFollowingRowsThroughBoth400msDirectionsWithoutReplacingTextOrJumpingScroll() {
        mount()
        val collapsed = frame()
        // The target uses the independently measured full body and Collapse footer.
        val expandedHeight = collapsed.height + revealHeight
        val expandStart = disclose("Expand")
        assertEquals("Expansion starts from the occupied preview", collapsed.height.toFloat(), expandStart.height.toFloat(), 2f)
        assertAnchor(collapsed, expandStart)
        val expanding = sampleTransition(expandStart, expandedHeight, collapsed)
        assertTrue("There is real intermediate expansion", expanding[6].height > collapsed.height)
        assertTrue("Expansion has not already jumped to its target", expanding[12].height < expandedHeight)
        assertEquals("Thinking's FastOutSlowIn reveal is about 78% complete near 200ms",
            0.7756f, (expanding[12].height - collapsed.height).toFloat() / (expandedHeight - collapsed.height), 0.065f)
        val expanded = expanding.last()
        repeat(8) { assertEquals("Settling adds no final correction", expanded, nextFrame()) }

        val collapseStart = disclose("Collapse")
        assertEquals("Collapse starts from the full occupied height", expandedHeight.toFloat(), collapseStart.height.toFloat(), 2f)
        val collapsing = sampleTransition(collapseStart, collapsed.height, collapsed)
        assertTrue("There is real intermediate collapse", collapsing[6].height < expandedHeight)
        assertTrue("Collapse has not already jumped to the preview", collapsing[12].height > collapsed.height)
        assertEquals("Collapse uses the same Thinking vertical curve",
            0.7756f, (expandedHeight - collapsing[12].height).toFloat() / (expandedHeight - collapsed.height), 0.065f)
        repeat(8) { assertEquals("The released mutation never pulls the list back", collapsed, nextFrame()) }
    }

    @Test
    fun reversingInEitherDirectionKeepsTheCurrentHeightAndOriginalReaderAnchor() {
        mount()
        val collapsed = frame()
        val expandedHeight = collapsed.height + revealHeight
        disclose("Expand")
        var partial = collapsed
        repeat(10) {
            val current = nextFrame()
            assertAnchor(collapsed, current)
            assertFollowingTracks(partial, current)
            partial = current
        }
        assertTrue("Reverse expansion while it is genuinely between endpoints",
            partial.height in (collapsed.height + 1) until expandedHeight)
        val reverseDown = disclose("Collapse")
        assertAnchor(collapsed, reverseDown)
        assertTrue("Reversal begins within bounded frame-sized travel of the partial reveal",
            abs(reverseDown.height - partial.height) <= revealHeight * 0.2f + 2f)
        sampleInterruptedTransition(reverseDown, collapsed.height, collapsed)
        repeat(4) { assertEquals(collapsed, nextFrame()) }

        sampleTransition(disclose("Expand"), expandedHeight, collapsed)
        disclose("Collapse")
        repeat(8) {
            val current = nextFrame()
            assertAnchor(collapsed, current)
            partial = current
        }
        assertTrue("Reverse collapse before it reaches the preview",
            partial.height in (collapsed.height + 1) until expandedHeight)
        val reverseUp = disclose("Expand")
        assertAnchor(collapsed, reverseUp)
        assertTrue("Reverse expansion retains bounded travel from the partially collapsed geometry",
            abs(reverseUp.height - partial.height) <= revealHeight * 0.2f + 2f)
        val expanded = sampleInterruptedTransition(reverseUp, expandedHeight, collapsed)
        repeat(8) { assertEquals("Stale settled callbacks cannot cause a later correction", expanded, nextFrame()) }
    }

    @Test
    fun actualReaderDragDuringDisclosureReleasesTheOldMutationAnchor() {
        mount()
        val original = frame()
        disclose("Expand")
        repeat(8) { nextFrame() }
        val list = compose.onNodeWithTag("disclosure-list")
        // Start outside the right-aligned text bubble, keeping the gesture with LazyColumn.
        list.performTouchInput {
            down(Offset(width * 0.04f, height * 0.7f))
            moveBy(Offset(0f, -120f))
        }
        repeat(2) { nextFrame() }
        list.performTouchInput {
            // Stop velocity before release so no fling obscures a stale anchor correction.
            advanceEventTime(200)
            up()
        }
        repeat(2) { nextFrame() }
        val dragged = frame()
        assertEquals("The drag keeps the disclosure turn in view", original.firstKey, dragged.firstKey)
        assertTrue("The real gesture actually changes the list scroll offset",
            dragged.firstOffset > original.firstOffset + 40)
        compose.runOnIdle { assertFalse("The held release ends without a fling", state.isScrollInProgress) }
        repeat(36) {
            val current = nextFrame()
            assertAnchor(dragged, current)
        }
        assertTrue("Disclosure still completes after the reader takes over", frame().height > original.height + 100)
    }
}
