package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import com.newoether.agora.model.ConversationTodoItem
import com.newoether.agora.R
import com.newoether.agora.data.SubagentCapsuleDisplayModes
import com.newoether.agora.model.SubagentSummary
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class ConversationGroupedSubagentsTest {
    @get:Rule val compose = createComposeRule()

    private fun agent(id: String, status: String = "idle", parent: String = "parent") =
        SubagentSummary(id, "Worker $id", "model", parent, status)

    @Test fun zeroHidesSingleNavigatesAndMultipleOpenOrderedRealAgents() {
        var agents by mutableStateOf(emptyList<SubagentSummary>())
        var selected: String? = null
        compose.setContent {
            MaterialTheme {
                ConversationToolsRow(
                    "parent", emptyList(), agents, { selected = it }, {},
                    subagentCapsuleDisplayMode = SubagentCapsuleDisplayModes.GROUPED,
                )
            }
        }
        compose.onNodeWithTag("subagents-capsule:parent").assertDoesNotExist()
        compose.runOnIdle { agents = listOf(agent("z")) }
        compose.onNodeWithText("Worker z").performClick()
        assertEquals("z", selected)
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { agents = listOf(agent("z", "failed"), agent("a", "stopped")) }
        compose.onAllNodesWithTag("subagents-capsule:parent").assertCountEquals(1)
        compose.onNodeWithText("Worker z").assertDoesNotExist()
        compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
        compose.onNodeWithTag("subagents-capsule:parent").performClick()
        compose.onNode(isDialog()).assertExists()
        val nameLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Worker z", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(nameLayouts) }
        assertEquals(16f, nameLayouts.single().layoutInput.style.fontSize.value, 0.01f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.SemiBold, nameLayouts.single().layoutInput.style.fontWeight)
        val first = compose.onNodeWithTag("subagents-sheet-agent:z").fetchSemanticsNode()
        val second = compose.onNodeWithTag("subagents-sheet-agent:a").fetchSemanticsNode()
        assertTrue("Rows consume the canonical creation order, not alphabetical order",
            first.boundsInRoot.top < second.boundsInRoot.top)
        assertFalse(first.config[SemanticsProperties.Selected])
        assertFalse(second.config[SemanticsProperties.Selected])
        compose.onNodeWithTag("subagents-sheet-status:z", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("subagents-sheet-agent:a").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals("a", selected)
    }

    @Test fun currentChildActivatesGroupAndItsRowWhileParentHasNoSelectedRow() {
        var selected by mutableStateOf("parent")
        val selections = mutableListOf<String>()
        val agents = listOf(agent("a"), agent("b"))
        compose.setContent {
            MaterialTheme {
                ConversationToolsRow(
                    selected, emptyList(), agents, { selections += it; selected = it }, {},
                    familyId = "parent", subagentCapsuleDisplayMode = SubagentCapsuleDisplayModes.GROUPED,
                )
            }
        }
        val capsule = compose.onNodeWithTag("subagents-capsule:parent")
        val initialId = capsule.fetchSemanticsNode().id
        assertFalse(capsule.fetchSemanticsNode().config[SemanticsProperties.Selected])
        capsule.performClick()
        listOf("a", "b").forEach {
            assertFalse(compose.onNodeWithTag("subagents-sheet-agent:$it").fetchSemanticsNode()
                .config[SemanticsProperties.Selected])
        }
        compose.onNodeWithTag("subagents-sheet-agent:b").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(listOf("b"), selections)
        assertTrue(capsule.fetchSemanticsNode().config[SemanticsProperties.Selected])
        assertEquals(initialId, capsule.fetchSemanticsNode().id)
        capsule.performClick()
        assertTrue(compose.onNodeWithTag("subagents-sheet-agent:b").fetchSemanticsNode()
            .config[SemanticsProperties.Selected])
        assertFalse(compose.onNodeWithTag("subagents-sheet-agent:a").fetchSemanticsNode()
            .config[SemanticsProperties.Selected])
        compose.onNodeWithTag("subagents-sheet-agent:a").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(listOf("b", "a"), selections)
        compose.runOnIdle { selected = "parent" }
        assertFalse(capsule.fetchSemanticsNode().config[SemanticsProperties.Selected])
        assertEquals(initialId, capsule.fetchSemanticsNode().id)
    }

    @Test fun anyRunningUsesProgressAndAllTerminalStatesAggregateToTree() {
        var agents by mutableStateOf(listOf(agent("a", "failed"), agent("b", "running")))
        lateinit var idle: String
        lateinit var failed: String
        lateinit var stopped: String
        val progress = SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
        )
        compose.setContent {
            idle = androidx.compose.ui.res.stringResource(R.string.subagent_idle)
            failed = androidx.compose.ui.res.stringResource(R.string.subagent_failed)
            stopped = androidx.compose.ui.res.stringResource(R.string.subagent_stopped)
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(true)) {
                MaterialTheme {
                    ConversationToolsRow("parent", emptyList(), agents, {}, {},
                        subagentCapsuleDisplayMode = SubagentCapsuleDisplayModes.GROUPED)
                }
            }
        }
        compose.onNode(progress, useUnmergedTree = true).assertExists()
        val slot = compose.onNodeWithTag("subagent-status:group:parent", useUnmergedTree = true)
        val initial = slot.fetchSemanticsNode()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { agents = listOf(agent("a", "failed"), agent("b", "stopped")) }
        advance(96)
        compose.onNode(progress, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(idle, useUnmergedTree = true).assertExists()
        assertEquals(initial.id, slot.fetchSemanticsNode().id)
        assertEquals(initial.boundsInRoot, slot.fetchSemanticsNode().boundsInRoot)
        advance(300)
        compose.onNode(progress, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(idle, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(failed, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(stopped, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { agents = listOf(agent("a", "running"), agent("b", "stopped")) }
        advance(300)
        compose.onNode(progress, useUnmergedTree = true).assertExists()
    }

    @Test fun runningLabelCountsOnlyRunningChildrenAndReturnsToTheIdleTotal() {
        var agents by mutableStateOf((1..14).map { index ->
            agent("$index", if (index == 1) "running" else listOf("idle", "completed", "failed", "stopped")[index % 4])
        })
        val progress = SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
        )
        compose.setContent {
            MaterialTheme {
                ConversationToolsRow("parent", emptyList(), agents, {}, {},
                    subagentCapsuleDisplayMode = SubagentCapsuleDisplayModes.GROUPED)
            }
        }
        compose.onNodeWithText("1 Running").assertExists()
        compose.onNodeWithText("14 Subagents").assertDoesNotExist()
        compose.onNode(progress, useUnmergedTree = true).assertExists()
        val capsule = compose.onNodeWithTag("subagents-capsule:parent")
        val identity = capsule.fetchSemanticsNode().id
        compose.runOnIdle { agents = agents.map { if (it.id == "2") it.copy(status = "running") else it } }
        compose.onNodeWithText("2 Running").assertExists()
        assertEquals(identity, capsule.fetchSemanticsNode().id)
        compose.runOnIdle { agents = agents.map { it.copy(status = "idle") } }
        compose.onNodeWithText("14 Subagents").assertExists()
        compose.onNodeWithText("2 Running").assertDoesNotExist()
        compose.onNode(progress, useUnmergedTree = true).assertDoesNotExist()
        assertEquals(identity, capsule.fetchSemanticsNode().id)
    }

    @Test fun singleGroupedChildKeepsItsNameAcrossEveryStatusAndRetainsDirectNavigation() {
        var child by mutableStateOf(agent("a", "running"))
        var selected: String? = null
        compose.setContent {
            MaterialTheme {
                ConversationToolsRow("parent", emptyList(), listOf(child), { selected = it }, {},
                    subagentCapsuleDisplayMode = SubagentCapsuleDisplayModes.GROUPED)
            }
        }
        val capsule = compose.onNodeWithTag("subagents-capsule:parent")
        val identity = capsule.fetchSemanticsNode().id
        for (status in listOf("running", "idle", "completed", "failed", "stopped")) {
            compose.runOnIdle { child = child.copy(title = "Worker $status", status = status); selected = null }
            compose.onNodeWithText("Worker $status").performClick()
            assertEquals("a", selected)
            assertEquals(identity, capsule.fetchSemanticsNode().id)
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNodeWithText("1 Running").assertDoesNotExist()
            compose.onNodeWithText("1 Subagent").assertDoesNotExist()
        }
    }

    @Test fun invalidatingSheetNeverReopensStaleFamilyOrKeepsPendingNavigation() {
        var agents by mutableStateOf(listOf(agent("a"), agent("b")))
        var family by mutableStateOf<String?>("parent")
        var selected by mutableStateOf<String?>("parent")
        var ready by mutableStateOf(true)
        var mode by mutableStateOf(SubagentCapsuleDisplayModes.GROUPED)
        val selections = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ConversationToolsRow(selected, emptyList(), agents, { selections += it }, {},
                        familyId = family, contentReady = ready, subagentCapsuleDisplayMode = mode)
                }
            }
        }
        val invalidations: List<() -> Unit> = listOf(
            { agents = agents.take(1) },
            { agents = emptyList() },
            { family = null; selected = null },
            { family = "other"; selected = "other"; agents = listOf(agent("x", parent = "other"), agent("y", parent = "other")) },
            { mode = SubagentCapsuleDisplayModes.INDIVIDUAL },
            { ready = false },
        )
        invalidations.forEach { invalidate ->
            compose.onNodeWithTag("subagents-capsule:parent").performClick()
            compose.onNode(isDialog()).assertExists()
            compose.runOnIdle { invalidate() }
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.runOnIdle {
                selected = "parent"
                family = "parent"
                agents = listOf(agent("a"), agent("b"))
                mode = SubagentCapsuleDisplayModes.GROUPED
                ready = true
            }
            compose.onNodeWithTag("subagents-capsule:parent").assertExists()
            compose.onNode(isDialog()).assertDoesNotExist()
        }
        assertTrue(selections.isEmpty())
    }

    @Test fun outgoingAggregateIsInertDuringUnrelatedFamilyReplacement() {
        var family by mutableStateOf("parent")
        var agents by mutableStateOf(listOf(agent("a"), agent("b")))
        var selected: String? = null
        compose.setContent {
            MaterialTheme {
                ConversationToolsRow(family, emptyList(), agents, { selected = it }, {},
                    familyId = family, subagentCapsuleDisplayMode = SubagentCapsuleDisplayModes.GROUPED)
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            family = "other"
            agents = listOf(agent("x", parent = "other"), agent("y", parent = "other"))
        }
        advance(64)
        compose.onNodeWithTag("subagents-capsule:parent").assertIsNotEnabled().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertNull(selected)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("subagents-capsule:parent").assertDoesNotExist()
        compose.onNodeWithTag("subagents-capsule:other").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithTag("subagents-sheet-agent:x").assertExists()
        compose.onNodeWithTag("subagents-sheet-agent:a").assertDoesNotExist()
    }

    @Test fun countAndTitleCrossfadesRetainTheSameCapsuleAcrossInterruptedUpdates() =
        verifyCountCrossfade(false)

    @Test fun reducedMotionKeepsCountAndTitleCrossfades() = verifyCountCrossfade(true)

    private fun verifyCountCrossfade(reduced: Boolean) {
        var agents by mutableStateOf(listOf(agent("a")))
        val one = "Worker a"
        lateinit var two: String
        lateinit var three: String
        var selections = 0
        compose.setContent {
            two = androidx.compose.ui.res.pluralStringResource(R.plurals.subagents_count, 2, 2)
            three = androidx.compose.ui.res.pluralStringResource(R.plurals.subagents_count, 3, 3)
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduced)) {
                MaterialTheme {
                    ConversationToolsRow("parent", emptyList(), agents, { selections++ }, {},
                        subagentCapsuleDisplayMode = SubagentCapsuleDisplayModes.GROUPED)
                }
            }
        }
        val capsule = compose.onNodeWithTag("subagents-capsule:parent")
        val initial = capsule.fetchSemanticsNode()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { agents = listOf(agent("a"), agent("b")) }
        advance(96)
        compose.onNodeWithText(one, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(two, useUnmergedTree = true).assertExists()
        assertEquals(initial.id, capsule.fetchSemanticsNode().id)
        assertEquals(initial.boundsInRoot.height, capsule.fetchSemanticsNode().boundsInRoot.height)
        assertFalse(capsule.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
        compose.runOnIdle { agents = agents + agent("c") }
        advance(96)
        compose.onNodeWithText(two, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(three, useUnmergedTree = true).assertExists()
        assertEquals(initial.id, capsule.fetchSemanticsNode().id)
        advance(400)
        compose.onNodeWithText(one, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(two, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(three, useUnmergedTree = true).assertExists()
        compose.runOnIdle { agents = listOf(agent("a").copy(title = "Renamed worker")) }
        advance(96)
        compose.onNodeWithText(three, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(one, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Renamed worker", useUnmergedTree = true).assertExists()
        assertEquals(initial.id, capsule.fetchSemanticsNode().id)
        advance(400)
        compose.onNodeWithText(three, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(one, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Renamed worker", useUnmergedTree = true).assertExists()
        capsule.performClick()
        assertEquals(1, selections)
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun groupedFamilyNavigationPreservesTheRightScrolledAnchor() = verifyGroupedAnchor(false)

    @Test fun reducedMotionPreservesTheGroupedFamilyAnchor() = verifyGroupedAnchor(true)

    private fun verifyGroupedAnchor(reduced: Boolean) {
        var selected by mutableStateOf("parent")
        var density = 1f
        val agents = listOf(agent("a"), agent("b"))
        compose.setContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduced)) {
                MaterialTheme {
                    ConversationToolsRow(selected,
                        if (selected == "parent") listOf(ConversationTodoItem("Keep this item")) else emptyList(),
                        agents, { selected = it }, {}, modifier = Modifier.width(110.dp),
                        familyId = "parent", subagentCapsuleDisplayMode = SubagentCapsuleDisplayModes.GROUPED)
                }
            }
        }
        val row = compose.onNodeWithTag("conversation-tools-row")
        row.performSemanticsAction(SemanticsActions.ScrollBy) { it(10000f, 0f) }
        val capsule = compose.onNodeWithTag("subagents-capsule:parent")
        val initial = capsule.fetchSemanticsNode()
        val range = row.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue(range.value() > 0f)
        compose.mainClock.autoAdvance = false
        for (next in listOf("a", "parent")) {
            compose.runOnIdle { selected = next }
            repeat(30) {
                advance(16)
                val current = capsule.fetchSemanticsNode()
                assertEquals(initial.id, current.id)
                assertEquals("Grouped family anchor must not jump", initial.boundsInRoot.left,
                    current.boundsInRoot.left, 1.5f * density)
            }
        }
        compose.mainClock.autoAdvance = true
    }

    private fun advance(millis: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }
}
