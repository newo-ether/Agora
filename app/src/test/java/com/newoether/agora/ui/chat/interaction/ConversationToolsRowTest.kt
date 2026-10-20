package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ConversationTodoItem
import com.newoether.agora.model.SubagentSummary
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.AskUserController
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationToolsRowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w1000dp-h800dp-mdpi")
    fun sharedRowOrdersBottomAlignedItemsAndReportsMaximumHeight() {
        var height = 0f
        var expectedHeight = 0f
        val agents = listOf(SubagentSummary("child", "Worker", "Provider:model", "parent", "idle"))
        compose.setContent {
            val density = LocalDensity.current
            expectedHeight = with(density) { 120.dp.toPx() }
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(true)) {
                MaterialTheme {
                    ConversationToolsRow(
                        "parent", listOf(ConversationTodoItem("Task")), agents, {}, { height = it },
                        modifier = Modifier.width(900.dp), hasInteraction = true,
                    ) { modifier, report, _, _, _, reportTransition ->
                        SideEffect { report(expectedHeight); reportTransition("parent", true) }
                        Box(modifier.width(200.dp).height(120.dp)) { Text("Interaction") }
                    }
                }
            }
        }
        compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
        val interaction = compose.onNodeWithTag("tools-slot:interaction").fetchSemanticsNode().boundsInRoot
        val todo = compose.onNodeWithTag("tools-slot:todo").fetchSemanticsNode().boundsInRoot
        val agent = compose.onNodeWithTag("tools-slot:subagent:parent:child").fetchSemanticsNode().boundsInRoot
        assertTrue("Interaction, Todo and Subagent bounds must be ordered: $interaction; $todo; $agent",
            interaction.left < todo.left && todo.left < agent.left)
        assertEquals(interaction.bottom, todo.bottom, 1f)
        assertEquals(todo.bottom, agent.bottom, 1f)
        assertEquals(expectedHeight, height, 1f)
        compose.onNodeWithText("Subagent", useUnmergedTree = true).assertExists()
        val label = compose.onNodeWithText("Subagent", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Worker", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(label.top < title.top)
    }

    @Test fun wholeTrackScrollsWithoutDisposingInteraction() {
        val agents = (0..10).map { SubagentSummary("child-$it", "Worker $it", "model", "parent", "idle") }
        var composed = 0
        compose.setContent {
            MaterialTheme {
                ConversationToolsRow("parent", emptyList(), agents, {}, {},
                    modifier = Modifier.width(320.dp), hasInteraction = true) { modifier, report, _, _, _, reportTransition ->
                    SideEffect { composed++; report(48f); reportTransition("parent", true) }
                    Text("Interaction", modifier.width(180.dp).height(48.dp))
                }
            }
        }
        compose.onNodeWithText("Worker 10").performScrollTo()
        compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
        compose.onNodeWithText("Interaction").assertExists()
        assertTrue(composed > 0)
    }

    @Test fun insertingAndRemovingLeadingSlotKeepsVisibleScrollAnchor() {
        var interaction by mutableStateOf(false)
        var slotWidth = 0f
        val agents = (0..12).map { SubagentSummary("child-$it", "Worker $it", "model", "parent", "idle") }
        compose.setContent {
            slotWidth = with(LocalDensity.current) { 188.dp.toPx() }
            MaterialTheme {
                ConversationToolsRow("parent", emptyList(), agents, {}, {},
                    modifier = Modifier.width(320.dp), hasInteraction = interaction) { modifier, report, _, _, _, _ ->
                    // This width-only fixture has no content fade; let the slot own its width tween.
                    SideEffect { report(if (interaction) 56f else 0f) }
                    if (interaction) Box(modifier.width(180.dp).height(56.dp)) { Text("Interaction") }
                }
            }
        }
        assertEquals("Initial rendering does not auto-scroll", 0f, scrollValue(), 0.1f)
        compose.onNodeWithText("Worker 5").performScrollTo()
        val before = scrollValue()
        assertTrue(before > 0f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { interaction = true }
        advance(112)
        val during = scrollValue()
        assertTrue("Insertion follows occupied width: $before -> $during", during > before && during < before + slotWidth)
        advance(500)
        assertEquals(before + slotWidth, scrollValue(), 2f)
        compose.runOnIdle { interaction = false }
        advance(112)
        val leaving = scrollValue()
        assertTrue("Exit retains the shrinking slot in geometry", leaving > before && leaving < before + slotWidth)
        advance(500)
        assertEquals(before, scrollValue(), 2f)
    }

    @Test fun userDragCancelsRemainingAnchorTravelForCurrentInsertion() {
        var interaction by mutableStateOf(false)
        val agents = (0..12).map { SubagentSummary("child-$it", "Worker $it", "model", "parent", "idle") }
        compose.setContent {
            MaterialTheme {
                ConversationToolsRow("parent", emptyList(), agents, {}, {},
                    modifier = Modifier.width(320.dp), hasInteraction = interaction) { modifier, report, _, _, _, reportTransition ->
                    SideEffect { report(if (interaction) 56f else 0f); reportTransition("parent", interaction) }
                    if (interaction) Box(modifier.width(500.dp).height(56.dp)) { Text("Interaction") }
                }
            }
        }
        compose.onNodeWithText("Worker 5").performScrollTo()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { interaction = true }
        advance(80)
        val row = compose.onNodeWithTag("conversation-tools-row")
        row.performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.45f, 0f))
        }
        advance(32)
        val dragged = scrollValue()
        advance(112)
        assertEquals("Geometry does not fight an active user drag", dragged, scrollValue(), 1f)
        row.performTouchInput {
            // A stationary release has no fling; remaining changes belong only to slot layout.
            advanceEventTime(200)
            up()
        }
        val released = scrollValue()
        advance(500)
        assertEquals("Releasing does not restart the cancelled travel", released, scrollValue(), 1f)
    }

    @Test fun reducedMotionSnapsSlotWidthAndAnchorTogether() {
        var interaction by mutableStateOf(false)
        var expectedWidth = 0f
        val agents = (0..12).map { SubagentSummary("child-$it", "Worker $it", "model", "parent", "idle") }
        compose.setContent {
            expectedWidth = with(LocalDensity.current) { 188.dp.toPx() }
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(true)) {
                MaterialTheme {
                    ConversationToolsRow("parent", emptyList(), agents, {}, {},
                        modifier = Modifier.width(320.dp), hasInteraction = interaction) { modifier, report, _, _, _, reportTransition ->
                        SideEffect { report(if (interaction) 56f else 0f); reportTransition("parent", interaction) }
                        if (interaction) Box(modifier.width(180.dp).height(56.dp)) { Text("Interaction") }
                    }
                }
            }
        }
        compose.onNodeWithText("Worker 5").performScrollTo()
        val before = scrollValue()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { interaction = true }
        advance(32)
        assertEquals(before + expectedWidth, scrollValue(), 2f)
        compose.runOnIdle { interaction = false }
        advance(32)
        assertEquals(before, scrollValue(), 2f)
    }

    @Test fun eachToolKindReportsPresenceAndReturnsExactlyToZero() {
        var kind by mutableStateOf("none")
        var mounted by mutableStateOf(true)
        var height = 0f
        val agents = listOf(SubagentSummary("child", "Worker", "model", "parent", "idle"))
        compose.setContent {
            MaterialTheme {
                if (mounted) ConversationToolsRow(
                    "parent", if (kind == "todo") listOf(ConversationTodoItem("Task")) else emptyList(),
                    if (kind == "subagent") agents else emptyList(), {}, { height = it },
                    hasInteraction = kind == "interaction",
                ) { modifier, report, reportInset, ready, animateEntry, reportTransition ->
                    ProductionInteraction(kind == "interaction", modifier, report, reportInset, ready, animateEntry, reportTransition)
                }
            }
        }
        compose.runOnIdle { assertEquals(0f, height, 0f) }
        listOf("interaction", "todo", "subagent").forEach { tool ->
            compose.runOnIdle { kind = tool }
            compose.waitForIdle()
            compose.runOnIdle { assertTrue("$tool must reserve message-tail space", height > 0f) }
            compose.runOnIdle { kind = "none" }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals("$tool clear must release the inset", 0f, height, 0f) }
        }
        compose.runOnIdle { kind = "interaction" }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(height > 0f) }
        compose.runOnIdle { mounted = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Disposal releases the shared measurement", 0f, height, 0f) }
    }

    @Test fun clearingAllToolsKeepsAnimatedMaximumUntilEveryLiftReleases() {
        var visible by mutableStateOf(true)
        var height = 0f
        val reports = mutableListOf<Float>()
        val agents = listOf(SubagentSummary("child", "Worker", "model", "parent", "idle"))
        compose.setContent {
            MaterialTheme {
                ConversationToolsRow(
                    "parent", if (visible) listOf(ConversationTodoItem("Task")) else emptyList(),
                    if (visible) agents else emptyList(), {}, { height = it; reports += it },
                    hasInteraction = visible,
                ) { modifier, report, reportInset, ready, animateEntry, reportTransition ->
                    ProductionInteraction(visible, modifier, report, reportInset, ready, animateEntry, reportTransition)
                }
            }
        }
        compose.waitForIdle()
        val fullHeight = height
        assertTrue(fullHeight > 0f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { reports.clear(); visible = false }
        val leaving = buildList {
            repeat(5) {
                advance(16)
                add(height)
            }
        }
        assertTrue("Normal exit has intermediate lift values: $leaving", leaving.any { it > 0f && it < fullHeight })
        assertTrue("Shared lift does not grow or sum during exit: $leaving", leaving.all { it <= fullHeight })
        assertTrue("Exit is monotonic: $leaving", leaving.zipWithNext().all { (a, b) -> b <= a })
        advance(1_000)
        assertEquals(0f, height, 0f)
        assertTrue("A settled exit cannot leave stale measured height", reports.isNotEmpty() && reports.last() == 0f)
    }

    @Test fun measurementFeedbackSettlesWithoutRepeatedLayoutOrHeightReports() {
        var reportedHeight by mutableFloatStateOf(0f)
        val reports = mutableListOf<Float>()
        var measures = 0
        var compositions = 0
        var expectedHeight = 0f
        val agents = listOf(SubagentSummary("child", "Worker", "model", "parent", "idle"))
        compose.setContent {
            expectedHeight = with(LocalDensity.current) { 120.dp.toPx() }
            MaterialTheme {
                Box {
                    Text("Measured tools: $reportedHeight")
                    ConversationToolsRow(
                        "parent", listOf(ConversationTodoItem("Task")), agents, {},
                        { reportedHeight = it; reports += it }, hasInteraction = true,
                    ) { modifier, report, _, _, _, reportTransition ->
                        SideEffect { compositions++; reportTransition("parent", true) }
                        Box(modifier.width(160.dp).height(120.dp).layout { measurable, constraints ->
                            measures++
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                        }.onSizeChanged { report(it.height.toFloat()) })
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals("The maximum, not the sum, feeds the parent", expectedHeight, reportedHeight, 1f)
        val settledMeasures = measures
        val settledCompositions = compositions
        val settledReports = reports.size
        compose.mainClock.autoAdvance = false
        repeat(12) { advance(16) }
        assertEquals("Slot callbacks do not cause an idle measure loop", settledMeasures, measures)
        assertEquals("Slot callbacks do not cause an idle composition loop", settledCompositions, compositions)
        assertEquals("Stable geometry does not churn the message inset", settledReports, reports.size)
        assertEquals(expectedHeight, reportedHeight, 1f)
    }

    @Test fun familyNavigationDoesNotReplayWidthOrHeightAnimation() {
        var selected by mutableStateOf("parent")
        var height = 0f
        var inset = 0f
        val reports = mutableListOf<Float>()
        val insetReports = mutableListOf<Float>()
        val agents = listOf(
            SubagentSummary("a", "Worker A", "model", "parent", "idle"),
            SubagentSummary("b", "Worker B", "model", "parent", "idle"),
        )
        compose.setContent {
            MaterialTheme {
                ConversationToolsRow(selected, emptyList(), agents, {}, { height = it; reports += it },
                    familyId = "parent", onMessageInsetChanged = { inset = it; insetReports += it })
            }
        }
        val slot = compose.onNodeWithTag("tools-slot:subagent:parent:a")
        val initialNode = slot.fetchSemanticsNode()
        val fullHeight = height
        val fullInset = inset
        assertTrue(fullHeight > 0f && fullInset > fullHeight)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { reports.clear(); insetReports.clear() }
        listOf("a", "b", "parent").forEach { id ->
            compose.runOnIdle { selected = id }
            repeat(6) {
                advance(16)
                assertEquals(fullHeight, height, 0f)
                assertEquals(fullInset, inset, 0f)
                val current = slot.fetchSemanticsNode()
                assertEquals("Same-family navigation preserves the keyed slot", initialNode.id, current.id)
                assertEquals(initialNode.boundsInRoot, current.boundsInRoot)
            }
            assertEquals(id == "a", compose.onNodeWithText("Worker A").fetchSemanticsNode()
                .config[SemanticsProperties.Selected])
        }
        assertTrue("Selection alone must not republish or replay the height lift: $reports", reports.isEmpty())
        assertTrue("Selection alone must not replay message clearance: $insetReports", insetReports.isEmpty())
    }

    @Test fun chatAppUsesOneMeasuredToolsInsetForMessageGeometryAndTailPadding() {
        val source = chatAppSource().replace(Regex("\\s+"), " ")
        assertTrue("The shared row owns the sole measured height input", source.contains(
            "onHeightChanged = { interactionBarHeightPx = it }",
        ))
        assertTrue("Message clearance is reported from item progress rather than reconstructed in ChatApp",
            source.contains("onMessageInsetChanged = { messageToolsInsetPx = it }"))
        assertTrue("Message clearance uses the existing density conversion",
            source.contains("val messageToolsInset = with(density) { messageToolsInsetPx.toDp() }"))
        assertFalse("Final clearance must follow item progress, not hard-drop on a positive-height flag",
            Regex("""if\s*\(\s*interactionBarHeightPx\s*>\s*0f\s*\)""").containsMatchIn(source))
        assertEquals("The measured inset has one declaration and two message-list consumers", 3,
            Regex("\\bmessageToolsInset\\b").findAll(source).count())
        assertTrue("MessageList geometry receives the same inset as its trailing padding", source.contains(
            "bottomBarHeight = (if (readingMode.active) 0.dp else bottomBarHeight) + shareSelectionBarSpace + messageToolsInset,",
        ))
        assertTrue("Tail padding remains separate from tools clearance", source.contains(
            "bottom = (if (readingMode.active) 0.dp else bottomBarHeight) + shareSelectionBarSpace + messageToolsInset +",
        ))
        assertTrue("E-ink keeps its larger tail padding and ordinary mode keeps 8dp", source.contains(
            "(if (motionPolicy.eInkMode) 56.dp else 8.dp)",
        ))
        assertTrue("The scroll button keeps its existing measured row lift, without message-only clearance",
            source.contains("bottomObstruction = (if (readingMode.active) 0.dp else bottomBarHeight) + interactionBarHeight,"))
    }

    @Test fun finalToolClearanceFollowsTheSameExitProgressForEveryKind() {
        var kind by mutableStateOf("none")
        var height = 0f
        var inset = 0f
        val agents = listOf(SubagentSummary("child", "Worker", "model", "parent", "idle"))
        compose.setContent {
            MaterialTheme {
                ConversationToolsRow(
                    "parent", if (kind == "todo") listOf(ConversationTodoItem("Task")) else emptyList(),
                    if (kind == "subagent") agents else emptyList(), {}, { height = it },
                    hasInteraction = kind == "interaction", onMessageInsetChanged = { inset = it },
                ) { modifier, report, reportInset, ready, animateEntry, reportTransition ->
                    ProductionInteraction(kind == "interaction", modifier, report, reportInset, ready, animateEntry, reportTransition)
                }
            }
        }
        listOf("interaction", "todo", "subagent").forEach { tool ->
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { kind = tool }
            compose.waitForIdle()
            val fullHeight = height
            val fullInset = inset
            assertTrue("$tool has measured space plus clearance", fullHeight > 0f && fullInset > fullHeight)
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { kind = "none" }
            var intermediate = false
            var previous = fullInset
            repeat(14) {
                advance(0)
                if (height > 0f && height < fullHeight) intermediate = true
                assertEquals("$tool clearance follows the same progress at every frame",
                    fullInset * height / fullHeight, inset, 0.25f)
                assertTrue("$tool exit is monotonic", inset <= previous + 0.25f)
                previous = inset
            }
            assertTrue("$tool must have intermediate frames", intermediate)
            advance(500)
            assertEquals(0f, height, 0f)
            assertEquals("$tool releases the last clearance without a remaining 8dp step", 0f, inset, 0f)
        }
    }

    @Test fun interruptedFinalToolExitRetargetsFromItsCurrentInset() {
        var kind by mutableStateOf("none")
        var height = 0f
        var inset = 0f
        val agents = listOf(SubagentSummary("child", "Worker", "model", "parent", "idle"))
        compose.setContent {
            MaterialTheme {
                ConversationToolsRow(
                    "parent", if (kind == "todo") listOf(ConversationTodoItem("Task")) else emptyList(),
                    if (kind == "subagent") agents else emptyList(), {}, { height = it },
                    hasInteraction = kind == "interaction", onMessageInsetChanged = { inset = it },
                ) { modifier, report, reportInset, ready, animateEntry, reportTransition ->
                    ProductionInteraction(kind == "interaction", modifier, report, reportInset, ready, animateEntry, reportTransition)
                }
            }
        }
        listOf("interaction", "todo", "subagent").forEach { tool ->
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { kind = tool }
            compose.waitForIdle()
            val fullHeight = height
            val fullInset = inset
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { kind = "none" }
            advance(48)
            val interrupted = inset
            assertTrue(interrupted > 0f && interrupted < fullInset)
            compose.runOnIdle { kind = tool }
            advance(0)
            assertTrue("$tool re-entry must not reset its current progress", inset > 0f && inset < fullInset)
            repeat(6) {
                advance(0)
                assertEquals("Interrupted $tool clearance stays coupled to height",
                    fullInset * height / fullHeight, inset, 0.25f)
            }
            advance(1_000)
            assertEquals(fullInset, inset, 0.25f)
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { kind = "none" }
            compose.waitForIdle()
        }
    }

    @Test fun reducedMotionSnapsFinalClearanceForEveryKindAndDisposal() {
        var kind by mutableStateOf("none")
        var mounted by mutableStateOf(true)
        var height = 0f
        var inset = 0f
        val agents = listOf(SubagentSummary("child", "Worker", "model", "parent", "idle"))
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(true)) {
                MaterialTheme {
                    if (mounted) ConversationToolsRow(
                        "parent", if (kind == "todo") listOf(ConversationTodoItem("Task")) else emptyList(),
                        if (kind == "subagent") agents else emptyList(), {}, { height = it },
                        hasInteraction = kind == "interaction", onMessageInsetChanged = { inset = it },
                    ) { modifier, report, reportInset, ready, animateEntry, reportTransition ->
                        ProductionInteraction(kind == "interaction", modifier, report, reportInset, ready, animateEntry, reportTransition)
                    }
                }
            }
        }
        listOf("interaction", "todo", "subagent").forEach { tool ->
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { kind = tool }
            compose.waitForIdle()
            assertTrue(height > 0f && inset > height)
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { kind = "none" }
            advance(0)
            assertEquals("$tool height snaps", 0f, height, 0f)
            assertEquals("$tool clearance snaps with the height", 0f, inset, 0f)
            advance(1_000)
        }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { kind = "todo" }
        compose.waitForIdle()
        assertTrue(inset > 0f)
        compose.runOnIdle { mounted = false }
        compose.waitForIdle()
        assertEquals(0f, height, 0f)
        assertEquals(0f, inset, 0f)
    }

    @Test fun removingTallInteractionKeepsTodoAndAgentSpaceThroughItsExit() {
        var visible by mutableStateOf(true)
        var height = 0f
        var inset = 0f
        var gap = 0f
        val agents = listOf(SubagentSummary("child", "Worker", "model", "parent", "idle"))
        compose.setContent {
            gap = with(LocalDensity.current) { 8.dp.toPx() }
            MaterialTheme {
                ConversationToolsRow(
                    "parent", listOf(ConversationTodoItem("Task")), agents, {}, { height = it },
                    hasInteraction = visible, onMessageInsetChanged = { inset = it },
                ) { modifier, report, reportInset, ready, animateEntry, reportTransition ->
                    ProductionInteraction(visible, modifier, report, reportInset, ready, animateEntry, reportTransition, minimized = false)
                }
            }
        }
        val remainingHeight = compose.onNodeWithTag("tools-slot:todo").fetchSemanticsNode().boundsInRoot.height
        val fullHeight = height
        val fullInset = inset
        assertTrue("Expanded production interaction is taller than a capsule", fullHeight > remainingHeight)
        assertEquals(fullHeight + gap, fullInset, 1f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visible = false }
        var intermediate = false
        repeat(14) {
            advance(0)
            if (height > remainingHeight && height < fullHeight) intermediate = true
            assertTrue("Remaining cards prevent a zero lift", height >= remainingHeight - 1f)
            assertTrue("Remaining cards keep their own clearance", inset >= remainingHeight + gap - 1f)
            assertTrue("Shared inset remains a maximum, never a sum", inset <= fullInset + 1f)
        }
        assertTrue(intermediate)
        advance(500)
        assertEquals(remainingHeight, height, 1f)
        assertEquals(remainingHeight + gap, inset, 1f)
    }

    @Test fun removingOneAgentUsesItsOwnProgressAndPreservesRemainingSpace() {
        val tall = SubagentSummary("tall", "Tall worker", "model", "parent", "idle")
        val short = SubagentSummary("short", "Short worker", "model", "parent", "idle")
        var agents by mutableStateOf(listOf(tall, short))
        var height = 0f
        var inset = 0f
        var gap = 0f
        compose.setContent {
            gap = with(LocalDensity.current) { 8.dp.toPx() }
            MaterialTheme {
                ConversationToolsRow("parent", emptyList(), agents, {}, { height = it },
                    onMessageInsetChanged = { inset = it })
            }
        }
        val shortHeight = compose.onNodeWithTag("tools-slot:subagent:parent:short")
            .fetchSemanticsNode().boundsInRoot.height
        // Equal-height siblings still reserve the maximum while either capsule remains.
        val fullHeight = height
        assertEquals(shortHeight, fullHeight, 1f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { agents = listOf(short) }
        repeat(14) {
            advance(0)
            assertEquals("A sibling's space remains while another agent leaves", shortHeight, height, 1f)
            assertEquals(shortHeight + gap, inset, 1f)
        }
        advance(500)
        compose.onNodeWithText("Tall worker").assertDoesNotExist()
        compose.onNodeWithText("Short worker").assertExists()
        assertEquals(shortHeight + gap, inset, 1f)
        compose.runOnIdle { agents = emptyList() }
        advance(64)
        assertTrue("The last individual capsule now releases its own inset", inset > 0f && inset < shortHeight + gap)
        advance(1_000)
        assertEquals(0f, height, 0f)
        assertEquals(0f, inset, 0f)
    }

    @Test fun cancelledAgentWaitsForOutgoingSiblingBeforeItReleasesMaximum() {
        var tall by mutableStateOf(true)
        var height = 0f
        var inset = 0f
        var mounted by mutableStateOf(true)
        val agents = listOf(SubagentSummary("child", "Worker", "model", "parent", "idle"))
        compose.setContent {
            MaterialTheme {
                if (mounted) ConversationToolsRow(
                    "parent", emptyList(), if (tall) agents else emptyList(), {}, { height = it },
                    onMessageInsetChanged = { inset = it },
                )
            }
        }
        compose.waitForIdle()
        assertTrue(height > 0f && inset > height)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { tall = false }
        advance(64)
        assertTrue(height > 0f && inset > 0f)
        compose.runOnIdle { mounted = false }
        advance(0)
        compose.onNodeWithText("Worker").assertDoesNotExist()
        assertEquals("Disposing during an active exit releases FAB lift", 0f, height, 0f)
        assertEquals("Disposing during an active exit releases message clearance", 0f, inset, 0f)
        advance(500)
        assertEquals(0f, inset, 0f)
    }

    @Composable
    private fun ProductionInteraction(
        visible: Boolean,
        modifier: Modifier,
        report: (Float) -> Unit,
        reportInset: (Float) -> Unit,
        ready: Boolean,
        animateEntry: Boolean,
        reportTransition: (String?, Boolean) -> Unit,
        minimized: Boolean = true,
    ) {
        val waiting = remember {
            userInteractions("parent", listOf(AskUserController.Request(
                id = 1, conversationId = "parent", question = "Question?", options = listOf("A", "B"),
                allowMultiple = false, blocking = false,
            )), null)
        }
        UserInteractionBar(
            conversationId = "parent", interactions = if (visible) waiting else emptyList(),
            autoWrapCodeBlocks = false, onSubmitQuestions = {}, onSkipQuestion = {},
            onShellDecision = { _, _, _ -> }, onHeightChanged = report,
            minimizedIn = if (minimized) setOf("parent") else emptySet(),
            onMinimizedChange = { _, _ -> }, drafts = remember { QuestionDrafts() },
            pageIn = emptyMap(), onPageChange = { _, _ -> }, onCardGone = {}, modifier = modifier,
            inSharedRow = true, onMessageInsetChanged = reportInset,
            appearanceReady = ready, animateEntry = animateEntry, onTransitionChanged = reportTransition,
        )
    }

    private fun chatAppSource(): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf("app/src/main/java", "src/main/java").forEach { root ->
                val file = File(directory, "$root/com/newoether/agora/ui/chat/ChatApp.kt")
                if (file.isFile) return file.readText()
            }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate ChatApp.kt")
    }

    private fun scrollValue(): Float = compose.onNodeWithTag("conversation-tools-row")
        .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()

    private fun advance(millis: Long) {
        // Deliver external state writes before the manually clocked recomposition frame.
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    @Test fun removingOneAgentDisablesItImmediatelyAndCollapsesItsSlot() {
        var agents by mutableStateOf(listOf(
            SubagentSummary("a", "Worker A", "model", "parent", "idle"),
            SubagentSummary("b", "Worker B", "model", "parent", "idle"),
        ))
        compose.setContent { MaterialTheme { ConversationToolsRow("parent", emptyList(), agents, {}, {}) } }
        val before = compose.onNodeWithTag("tools-slot:subagent:parent:a").fetchSemanticsNode().boundsInRoot.width
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { agents = agents.drop(1) }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("Worker A").assertIsNotEnabled()
        val widths = buildList {
            repeat(38) {
                Snapshot.sendApplyNotifications()
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val nodes = compose.onAllNodesWithTag("tools-slot:subagent:parent:a").fetchSemanticsNodes()
                add(nodes.singleOrNull()?.boundsInRoot?.width ?: 0f)
            }
        }
        assertTrue("Fade retains the natural slot before collapse: $widths", widths.first() == before)
        assertTrue("Removal occupies intermediate widths after fade: $widths", widths.any { it > 0f && it < before })
        assertTrue("A removed slot never regrows: $widths", widths.zipWithNext().all { (a, b) -> b <= a })
        assertEquals("A completed exit releases the slot", 0f, widths.last(), 0f)
        compose.onNodeWithText("Worker A").assertDoesNotExist()
        compose.onNodeWithText("Worker B").assertExists()
    }
}
