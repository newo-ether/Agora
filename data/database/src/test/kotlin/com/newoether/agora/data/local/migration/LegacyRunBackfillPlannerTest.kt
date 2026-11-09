package com.newoether.agora.data.local.migration

import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyRunBackfillPlannerTest {

    @Test
    fun linearConversation_createsOneRunPerVisibleUserBoundary() {
        val plan = plan(
            message("u1", participant = Participant.USER, timestamp = 1),
            message("m1", parentId = "u1", timestamp = 2),
            message("u2", parentId = "m1", participant = Participant.USER, timestamp = 3),
            message("m2", parentId = "u2", timestamp = 4),
        )

        assertEquals(2, plan.runs.size)
        val firstRun = plan.runFor("u1")
        val secondRun = plan.runFor("u2")
        assertEquals(firstRun.id, secondRun.parentRunId)
        assertEquals(listOf("u1", "m1"), plan.messageIds(firstRun.id))
        assertEquals(listOf("u2", "m2"), plan.messageIds(secondRun.id))
        assertFalse(firstRun.legacyAmbiguous)
        assertFalse(secondRun.legacyAmbiguous)
    }

    @Test
    fun consecutiveUsers_remainSeparateRuns() {
        val plan = plan(
            message("u1", participant = Participant.USER, timestamp = 1),
            message("u2", parentId = "u1", participant = Participant.USER, timestamp = 2),
            message("m1", parentId = "u2", timestamp = 3),
        )

        val firstRun = plan.runFor("u1")
        val secondRun = plan.runFor("u2")
        assertEquals(2, plan.runs.size)
        assertEquals(firstRun.id, secondRun.parentRunId)
        assertEquals(listOf("u1"), plan.messageIds(firstRun.id))
        assertEquals(listOf("u2", "m1"), plan.messageIds(secondRun.id))
    }

    @Test
    fun syntheticToolResultChain_staysInSurroundingRun() {
        val plan = plan(
            message("u1", participant = Participant.USER, timestamp = 1),
            message("m1", parentId = "u1", timestamp = 2),
            message("tool_1", parentId = "m1", timestamp = 3),
            message(
                "result_1",
                parentId = "tool_1",
                participant = Participant.USER,
                timestamp = 4,
            ),
            message("m2", parentId = "result_1", timestamp = 5),
        )

        assertEquals(1, plan.runs.size)
        assertEquals(
            listOf("u1", "m1", "tool_1", "result_1", "m2"),
            plan.assignments.map { it.messageId },
        )
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), plan.assignments.map { it.runSequence })
        assertEquals(listOf(0, null, null, null, null), plan.assignments.map { it.consumedAtPass })
        assertFalse(plan.runs.single().legacyAmbiguous)
    }

    @Test
    fun legacyRegenerateSiblings_areKeptInOneAmbiguousRunWithoutClones() {
        val plan = plan(
            message("u1", participant = Participant.USER, timestamp = 1),
            message("m1", parentId = "u1", timestamp = 2),
            message("m2", parentId = "u1", timestamp = 3),
        )

        assertEquals(1, plan.runs.size)
        assertTrue(plan.runs.single().legacyAmbiguous)
        assertEquals(listOf("u1", "m1", "m2"), plan.assignments.map { it.messageId })
        assertEquals(3, plan.assignments.map { it.messageId }.distinct().size)
    }

    @Test
    fun userBranches_createSiblingRunsWithTheSameParentRun() {
        val plan = plan(
            message("u1", participant = Participant.USER, timestamp = 1),
            message("m1", parentId = "u1", timestamp = 2),
            message("u2", parentId = "m1", participant = Participant.USER, timestamp = 3),
            message("u3", parentId = "m1", participant = Participant.USER, timestamp = 4),
            message("m2", parentId = "u2", timestamp = 5),
            message("m3", parentId = "u3", timestamp = 6),
        )

        val root = plan.runFor("u1")
        val left = plan.runFor("u2")
        val right = plan.runFor("u3")
        assertEquals(3, plan.runs.size)
        assertEquals(root.id, left.parentRunId)
        assertEquals(root.id, right.parentRunId)
        assertTrue(left.id != right.id)
    }

    @Test
    fun orphanModelTree_getsOneDeterministicAmbiguousRun() {
        val plan = plan(
            message("m1", parentId = "missing", timestamp = 1),
            message("m2", parentId = "m1", timestamp = 2),
        )

        assertEquals(1, plan.runs.size)
        assertNull(plan.runs.single().boundaryMessageId)
        assertNull(plan.runs.single().parentRunId)
        assertTrue(plan.runs.single().legacyAmbiguous)
        assertEquals(listOf("m1", "m2"), plan.assignments.map { it.messageId })
    }

    @Test
    fun outputIsIndependentOfInputOrder_andParentsPrecedeChildren() {
        val messages = listOf(
            message("m1", parentId = "u1", timestamp = 1),
            message("u2", parentId = "m1", participant = Participant.USER, timestamp = 2),
            message("m2", parentId = "u2", timestamp = 3),
            message("u1", participant = Participant.USER, timestamp = 10),
        )

        val forward = LegacyRunBackfillPlanner.plan("conversation", messages)
        val reversed = LegacyRunBackfillPlanner.plan("conversation", messages.reversed())

        assertEquals(forward, reversed)
        assertEquals(listOf("u1", "m1", "u2", "m2"), forward.assignments.map { it.messageId })
        assertEquals(messages.map { it.id }.toSet(), forward.assignments.map { it.messageId }.toSet())
        assertEquals(listOf(0, null, 0, null), forward.assignments.map { it.consumedAtPass })
    }

    @Test
    fun legacyTerminalState_prefersErrorThenInterruptedThenCompleted() {
        val failed = plan(
            message("u1", participant = Participant.USER, timestamp = 1),
            message("m1", parentId = "u1", status = MessageStatus.STOPPED, timestamp = 2),
            message(
                "e1",
                parentId = "m1",
                participant = Participant.ERROR,
                status = MessageStatus.ERROR,
                timestamp = 3,
            ),
        ).runs.single()
        val interrupted = plan(
            message("u1", participant = Participant.USER, timestamp = 1),
            message("m1", parentId = "u1", status = MessageStatus.THINKING, timestamp = 2),
        ).runs.single()
        val completed = plan(
            message("u1", participant = Participant.USER, timestamp = 1),
            message("m1", parentId = "u1", timestamp = 2),
        ).runs.single()

        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals(RunEndReason.PROVIDER_ERROR, failed.endReason)
        assertEquals(RunStatus.STOPPED, interrupted.status)
        assertEquals(RunEndReason.PROCESS_RECOVERED, interrupted.endReason)
        assertEquals(RunStatus.COMPLETED, completed.status)
        assertEquals(RunEndReason.MODEL_COMPLETED, completed.endReason)
    }

    @Test
    fun selectedRunBranches_preserveRootAndDescendantSelections() {
        val messages = listOf(
            message("u1", participant = Participant.USER, timestamp = 1),
            message("m1", parentId = "u1", timestamp = 2),
            message("u2", parentId = "m1", participant = Participant.USER, timestamp = 3),
            message("u3", parentId = "m1", participant = Participant.USER, timestamp = 4),
            message("m2", parentId = "u2", timestamp = 5),
            message("m3", parentId = "u3", timestamp = 6),
        )
        val plan = LegacyRunBackfillPlanner.plan("conversation", messages)

        val selections = LegacyRunBackfillPlanner.selectedRunBranches(
            messages = messages,
            plan = plan,
            selectedMessageBranches = mapOf(null to "u1", "m1" to "u2"),
        )

        val rootRun = plan.runFor("u1")
        assertEquals(rootRun.id, selections[null])
        assertEquals(plan.runFor("u2").id, selections[rootRun.id])
    }

    @Test
    fun selectedRunBranches_followChosenModelInsideAmbiguousRun() {
        val messages = listOf(
            message("u1", participant = Participant.USER, timestamp = 1),
            message("m1", parentId = "u1", timestamp = 2),
            message("m2", parentId = "u1", timestamp = 3),
            message("u2", parentId = "m1", participant = Participant.USER, timestamp = 4),
            message("u3", parentId = "m2", participant = Participant.USER, timestamp = 5),
        )
        val plan = LegacyRunBackfillPlanner.plan("conversation", messages)
        val ambiguousRun = plan.runFor("u1")
        assertTrue(ambiguousRun.legacyAmbiguous)

        val left = LegacyRunBackfillPlanner.selectedRunBranches(
            messages,
            plan,
            mapOf(null to "u1", "u1" to "m1"),
        )
        val right = LegacyRunBackfillPlanner.selectedRunBranches(
            messages,
            plan,
            mapOf(null to "u1", "u1" to "m2"),
        )

        assertEquals(plan.runFor("u2").id, left[ambiguousRun.id])
        assertEquals(plan.runFor("u3").id, right[ambiguousRun.id])
    }

    private fun plan(vararg messages: LegacyMessageRecord): LegacyRunBackfillPlan =
        LegacyRunBackfillPlanner.plan("conversation", messages.toList())

    private fun message(
        id: String,
        parentId: String? = null,
        participant: Participant = Participant.MODEL,
        status: MessageStatus = MessageStatus.SUCCESS,
        timestamp: Long,
    ) = LegacyMessageRecord(
        id = id,
        parentId = parentId,
        participant = participant,
        status = status,
        timestamp = timestamp,
    )

    private fun LegacyRunBackfillPlan.runFor(messageId: String): PlannedLegacyRun {
        val runId = assignments.single { it.messageId == messageId }.runId
        return runs.single { it.id == runId }
    }

    private fun LegacyRunBackfillPlan.messageIds(runId: String): List<String> =
        assignments.filter { it.runId == runId }.map { it.messageId }
}
