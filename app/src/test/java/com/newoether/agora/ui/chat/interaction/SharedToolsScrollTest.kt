package com.newoether.agora.ui.chat.interaction

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PauseCircleOutline
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedToolsScrollTest {
    @Test fun insertionBeforeReaderPreservesKeyAndWithinItemOffset() {
        val before = listOf("todo", "a", "b")
        val anchor = sharedToolsAnchor(before, mapOf("todo" to 100, "a" to 100, "b" to 100), 125)
        assertEquals("a" to 25, anchor)
        assertEquals(205, sharedToolsScrollTarget(anchor, before,
            listOf("interaction", "todo", "a", "b"),
            mapOf("interaction" to 80, "todo" to 100, "a" to 100, "b" to 100), 125, 300))
    }

    @Test fun deletingAnchorChoosesNextSurvivorAndClampsToNewRange() {
        val before = listOf("todo", "a", "b")
        assertEquals(100, sharedToolsScrollTarget("a" to 25, before, listOf("todo", "b"),
            mapOf("todo" to 100, "b" to 100), 125, 200))
        assertEquals(40, sharedToolsScrollTarget("b" to 80, before, listOf("todo", "b"),
            mapOf("todo" to 100, "b" to 100), 280, 40))
        assertEquals(0, sharedToolsScrollTarget("a" to 25, before, emptyList(), emptyMap(), 125, 0))
    }

    @Test fun leavingAgentsKeepTheirOriginalPlacementUntilCollapsed() {
        fun agent(id: String) = com.newoether.agora.model.SubagentSummary(id, id, "model", "parent", "idle")
        val shown = listOf("a", "b", "c", "d").map(::agent)
        assertEquals(listOf("a", "b", "c", "d"),
            retainLeavingSubagents(shown, listOf(agent("c"), agent("d"))).map { it.id })
        assertEquals(listOf("a", "b", "c", "new", "d"),
            retainLeavingSubagents(shown, listOf(agent("a"), agent("c"), agent("new"))).map { it.id })
    }

    @Test fun animatedInsertionAndRemovalPreserveAnchorAtEveryGeometryStep() {
        var previous = listOf("a", "b", "c")
        var widths = mapOf("a" to 100, "b" to 120, "c" to 140)
        var scroll = 125
        var anchor = sharedToolsAnchor(previous, widths, scroll)
        for (leadingWidth in listOf(1, 20, 60, 80, 60, 20, 1, 0)) {
            val next = if (leadingWidth == 0) previous.filter { it != "interaction" }
                else listOf("interaction", "a", "b", "c")
            widths = widths + ("interaction" to leadingWidth)
            scroll = sharedToolsScrollTarget(anchor, previous, next, widths, scroll, 400)
            assertEquals(leadingWidth + 125, scroll)
            anchor = sharedToolsAnchor(next, widths, scroll)
            assertEquals("b" to 25, anchor)
            previous = next
        }
    }

    @Test fun statusIconsKeepRuntimeMeaningAndIdleUsesSourceGlyph() {
        assertEquals(Icons.Rounded.AccountTree, subagentStatusIcon("idle"))
        assertEquals(Icons.Rounded.AccountTree, subagentStatusIcon("completed"))
        assertEquals(Icons.Rounded.ErrorOutline, subagentStatusIcon("failed"))
        assertEquals(Icons.Rounded.PauseCircleOutline, subagentStatusIcon("stopped"))
    }
}
