package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunUiProjectionTest {
    @Test
    fun eachUserStartsANewGenerationAndOnlyItsLastAssistantGetsActions() {
        val messages = listOf(
            message("u0", "start", Participant.USER, "run-a", 0),
            message("m0", "first", Participant.MODEL, "run-a", 1),
            message("u1", "steer", Participant.USER, "run-a", 2),
            message("m1", "final", Participant.MODEL, "run-a", 3),
        )

        val projected = RunUiProjection.project(messages, messages)

        assertTrue(projected.getValue("u0").showActions)
        assertEquals("start", projected.getValue("u0").copyText)
        assertEquals("u0", projected.getValue("u0").deleteTargetMessageId)
        assertTrue(projected.getValue("m0").showActions)
        assertTrue(projected.getValue("u1").showActions)
        assertTrue(projected.getValue("m1").showActions)
        assertEquals("final", projected.getValue("m1").copyText)
        assertEquals("m1", projected.getValue("m1").deleteTargetMessageId)
    }

    @Test
    fun emptyStoppedOutput_keepsActionsWithoutCopy() {
        val messages = listOf(
            message("u0", "start", Participant.USER, "run-a", 0),
            message("m0", "", Participant.MODEL, "run-a", 1, MessageStatus.STOPPED),
        )

        val projected = RunUiProjection.project(messages, messages)

        assertTrue(projected.getValue("m0").showActions)
        assertNull(projected.getValue("m0").copyText)
    }

    @Test
    fun editBranch_isOnlyOnUser_andRegenerationBranchIsOnlyOnAssistant() {
        val left = listOf(
            message("u-left", "left", Participant.USER, "run-left", 0, parentId = "parent"),
            message("m-left", "answer", Participant.MODEL, "run-left", 1, parentId = "u-left"),
        )
        val right = listOf(
            message("u-right", "right", Participant.USER, "run-right", 0, parentId = "parent", timestamp = 2),
            message("m-right", "answer", Participant.MODEL, "run-right", 1, parentId = "u-right", timestamp = 3),
        )
        val regenerated = message(
            "m-regenerated",
            "new answer",
            Participant.MODEL,
            "run-regenerated",
            0,
            parentId = "u-right",
            timestamp = 4,
        )

        val projected = RunUiProjection.project(
            visibleMessages = listOf(right.first(), regenerated),
            allMessages = left + right + regenerated,
        )

        val input = projected.getValue("u-right")
        assertTrue(input.showBranchSelector)
        assertEquals(1, input.branchIndex)
        assertEquals(2, input.totalBranches)
        assertEquals("parent", input.branchAnchorParentId)
        assertEquals("u-right", input.branchAnchorMessageId)
        assertEquals("u-right", input.deleteTargetMessageId)
        val output = projected.getValue("m-regenerated")
        assertTrue(output.showBranchSelector)
        assertEquals(1, output.branchIndex)
        assertEquals(2, output.totalBranches)
        assertEquals("u-right", output.branchAnchorParentId)
        assertEquals("m-regenerated", output.branchAnchorMessageId)
        assertEquals("m-regenerated", output.deleteTargetMessageId)
    }

    @Test
    fun regenerationAlone_doesNotCreateAnEditSelectorOnSharedUser() {
        val input = message("u0", "prompt", Participant.USER, "source", 0)
        val original = message("m0", "answer", Participant.MODEL, "source", 1, parentId = "u0")
        val regenerated = message(
            "m1",
            "new answer",
            Participant.MODEL,
            "regeneration",
            0,
            parentId = "u0",
            timestamp = 2,
        )

        val projected = RunUiProjection.project(
            visibleMessages = listOf(input, regenerated),
            allMessages = listOf(input, original, regenerated),
        )

        assertFalse(projected.getValue("u0").showBranchSelector)
        assertTrue(projected.getValue("m1").showBranchSelector)
    }

    @Test
    fun duplicateUiRowsWithSameId_neverCreateFalseBranches() {
        val input = message("u0", "prompt", Participant.USER, "run", 0)
        val output = message("m0", "answer", Participant.MODEL, "run", 1, parentId = "u0")

        val projected = RunUiProjection.project(
            visibleMessages = listOf(input, output),
            allMessages = listOf(input, input, output, output),
        )

        assertFalse(projected.getValue("u0").showBranchSelector)
        assertEquals(1, projected.getValue("u0").totalBranches)
        assertFalse(projected.getValue("m0").showBranchSelector)
        assertEquals(1, projected.getValue("m0").totalBranches)
    }

    @Test
    fun legacyAssistantSiblingsInOneRun_remainIndependentRegenerationBranches() {
        val input = message("u0", "prompt", Participant.USER, "legacy-run", 0)
        val original = message(
            "m0",
            "answer",
            Participant.MODEL,
            "legacy-run",
            1,
            parentId = "u0",
            timestamp = 1,
        )
        val regenerated = message(
            "m1",
            "new answer",
            Participant.MODEL,
            "legacy-run",
            2,
            parentId = "u0",
            timestamp = 2,
        )

        val projected = RunUiProjection.project(
            visibleMessages = listOf(input, regenerated),
            allMessages = listOf(input, original, regenerated),
        )
        val output = projected.getValue("m1")

        assertTrue(output.showBranchSelector)
        assertEquals(1, output.branchIndex)
        assertEquals(2, output.totalBranches)
        assertEquals("m1", output.branchAnchorMessageId)
    }

    @Test
    fun syntheticToolRows_neverBecomeBoundariesOrCopyText() {
        val messages = listOf(
            message("u0", "start", Participant.USER, "run-a", 0),
            message("m0", "answer", Participant.MODEL, "run-a", 1),
            message("tool_x", "hidden", Participant.MODEL, "run-a", 2),
            message("result_x", "hidden result", Participant.USER, "run-a", 3),
        )

        val projected = RunUiProjection.project(messages, messages)

        assertTrue(projected.getValue("m0").showActions)
        assertEquals("answer", projected.getValue("m0").copyText)
        assertFalse(projected.getValue("tool_x").showActions)
        assertFalse(projected.getValue("result_x").showActions)
    }

    @Test
    fun adjacentAssistantsFromDifferentRuns_keepIndependentActions() {
        val messages = listOf(
            message("u0", "prompt", Participant.USER, "run-a", 0),
            message("m0", "partial", Participant.MODEL, "run-a", 1, parentId = "u0"),
            message("m1", "final", Participant.MODEL, "run-b", 0, parentId = "m0"),
        )

        val projected = RunUiProjection.project(messages, messages)

        assertTrue(projected.getValue("u0").showActions)
        assertTrue(projected.getValue("m0").showActions)
        assertEquals("m0", projected.getValue("m0").deleteTargetMessageId)
        assertTrue(projected.getValue("m1").showActions)
        assertEquals("m1", projected.getValue("m1").deleteTargetMessageId)
    }

    @Test
    fun malformedAssistantRoot_stillExposesTerminalActionsWithoutInventingAnInput() {
        val messages = listOf(
            message("m0", "orphan start", Participant.MODEL, "run-a", 3),
            message("m1", "orphan end", Participant.MODEL, "run-b", 0, parentId = "m0"),
        )

        val projected = RunUiProjection.project(messages, messages)

        assertTrue(projected.getValue("m0").showActions)
        assertEquals("m0", projected.getValue("m0").deleteTargetMessageId)
        assertTrue(projected.getValue("m1").showActions)
        assertEquals("m1", projected.getValue("m1").deleteTargetMessageId)
        assertFalse(projected.getValue("m1").showBranchSelector)
    }

    @Test
    fun compactMessage_isNeverAnAssistantActionBoundary() {
        val messages = listOf(
            message("u0", "prompt", Participant.USER, "run-a", 0),
            message("m0", "answer", Participant.MODEL, "run-a", 1, parentId = "u0"),
            message("${com.newoether.agora.model.ModelConstants.COMPACT_MSG_PREFIX}0", "summary", Participant.MODEL, "compact", 0, parentId = "m0"),
        )

        val projected = RunUiProjection.project(messages, messages)

        assertTrue(projected.getValue("m0").showActions)
        assertFalse(projected.getValue("${com.newoether.agora.model.ModelConstants.COMPACT_MSG_PREFIX}0").showActions)
    }

    private fun message(
        id: String,
        text: String,
        participant: Participant,
        runId: String,
        sequence: Long,
        status: MessageStatus = MessageStatus.SUCCESS,
        parentId: String? = null,
        timestamp: Long = sequence,
    ) = ChatMessage(
        id = id,
        parentId = parentId,
        text = text,
        participant = participant,
        status = status,
        timestamp = timestamp,
        runId = runId,
        runSequence = sequence,
    )
}
