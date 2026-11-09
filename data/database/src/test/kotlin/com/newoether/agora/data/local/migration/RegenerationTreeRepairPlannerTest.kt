package com.newoether.agora.data.local.migration

import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegenerationTreeRepairPlannerTest {
    @Test
    fun repeatedInput_becomesAssistantSiblingAndMovesSelections() {
        val plan = RegenerationTreeRepairPlanner.plan(
            runs = listOf(
                run("source", parentRunId = "previous", startedAt = 1),
                run("regeneration", parentRunId = "previous", startedAt = 3),
            ),
            messages = listOf(
                message("user", null, Participant.USER, "source", 0, 1, "same"),
                message("answer", "user", Participant.MODEL, "source", 1, 2),
                message("cloned-user", null, Participant.USER, "regeneration", 0, 3, "same"),
                message("new-answer", "cloned-user", Participant.MODEL, "regeneration", 1, 4),
            ),
            messageSelections = mapOf(
                null to "cloned-user",
                "cloned-user" to "new-answer",
            ),
            runSelections = mapOf("previous" to "regeneration"),
        )

        assertEquals("source", plan.runParentUpdates["regeneration"])
        assertEquals(setOf("cloned-user"), plan.deletedMessageIds)
        assertEquals("user", plan.messageParentUpdates["new-answer"])
        assertEquals(0L, plan.runSequenceUpdates["new-answer"])
        assertEquals("user", plan.messageSelections[null])
        assertEquals("new-answer", plan.messageSelections["user"])
        assertEquals("source", plan.runSelections["previous"])
        assertEquals("regeneration", plan.runSelections["source"])
    }

    @Test
    fun differentEditedInput_remainsUserSibling() {
        val plan = RegenerationTreeRepairPlanner.plan(
            runs = listOf(
                run("source", parentRunId = null, startedAt = 1),
                run("edit", parentRunId = null, startedAt = 3),
            ),
            messages = listOf(
                message("user", null, Participant.USER, "source", 0, 1, "original"),
                message("answer", "user", Participant.MODEL, "source", 1, 2),
                message("edited-user", null, Participant.USER, "edit", 0, 3, "changed"),
                message("edited-answer", "edited-user", Participant.MODEL, "edit", 1, 4),
            ),
            messageSelections = emptyMap(),
            runSelections = emptyMap(),
        )

        assertTrue(plan.inferredRunIds.isEmpty())
        assertTrue(plan.deletedMessageIds.isEmpty())
        assertTrue(plan.messageParentUpdates.isEmpty())
    }

    @Test
    fun oldMultiInputRegeneration_removesEveryLeadingCloneButKeepsLaterQueueInput() {
        val plan = RegenerationTreeRepairPlanner.plan(
            runs = listOf(
                run("source", parentRunId = null, startedAt = 1),
                run("regeneration", parentRunId = null, startedAt = 4),
            ),
            messages = listOf(
                message("user", null, Participant.USER, "source", 0, 1, "same"),
                message("source-answer", "user", Participant.MODEL, "source", 1, 2),
                message("clone-0", null, Participant.USER, "regeneration", 0, 4, "same"),
                message("clone-1", "clone-0", Participant.USER, "regeneration", 1, 5, "steer"),
                message("regen-answer", "clone-1", Participant.MODEL, "regeneration", 2, 6),
                message("real-queue", "regen-answer", Participant.USER, "regeneration", 3, 7, "later"),
            ),
            messageSelections = emptyMap(),
            runSelections = emptyMap(),
        )

        assertEquals(setOf("clone-0", "clone-1"), plan.deletedMessageIds)
        assertEquals("user", plan.messageParentUpdates["regen-answer"])
        assertEquals(0L, plan.runSequenceUpdates["regen-answer"])
        assertEquals(1L, plan.runSequenceUpdates["real-queue"])
        assertFalse("real-queue" in plan.deletedMessageIds)
    }

    @Test
    fun everyLegacyAssistantSibling_isReparentedAndSelectedBranchIsPreserved() {
        val plan = RegenerationTreeRepairPlanner.plan(
            runs = listOf(
                run("source", parentRunId = null, startedAt = 1),
                run("legacy-regeneration", parentRunId = null, startedAt = 4),
            ),
            messages = listOf(
                message("user", null, Participant.USER, "source", 0, 1, "same"),
                message("source-answer", "user", Participant.MODEL, "source", 1, 2),
                message("clone", null, Participant.USER, "legacy-regeneration", 0, 4, "same"),
                message("older-answer", "clone", Participant.MODEL, "legacy-regeneration", 1, 5),
                message("selected-answer", "clone", Participant.MODEL, "legacy-regeneration", 2, 6),
            ),
            messageSelections = mapOf(
                null to "clone",
                "clone" to "selected-answer",
            ),
            runSelections = mapOf(null to "legacy-regeneration"),
        )

        assertEquals("user", plan.messageParentUpdates["older-answer"])
        assertEquals("user", plan.messageParentUpdates["selected-answer"])
        assertEquals("selected-answer", plan.messageSelections["user"])
    }

    @Test
    fun originalOutputSelection_doesNotCreateRunSelfEdge() {
        val plan = RegenerationTreeRepairPlanner.plan(
            runs = listOf(
                run("source", parentRunId = null, startedAt = 1),
                run("regeneration", parentRunId = null, startedAt = 3),
            ),
            messages = listOf(
                message("user", null, Participant.USER, "source", 0, 1, "same"),
                message("answer", "user", Participant.MODEL, "source", 1, 2),
                message("clone", null, Participant.USER, "regeneration", 0, 3, "same"),
                message("new-answer", "clone", Participant.MODEL, "regeneration", 1, 4),
            ),
            messageSelections = mapOf(null to "user", "user" to "answer"),
            runSelections = mapOf(null to "source"),
        )

        assertFalse("source" in plan.runSelections)
        assertEquals("source", plan.runSelections[null])
    }

    private fun run(id: String, parentRunId: String?, startedAt: Long) =
        V17RunRecord(id, parentRunId, startedAt)

    private fun message(
        id: String,
        parentId: String?,
        participant: Participant,
        runId: String,
        sequence: Long,
        timestamp: Long,
        fingerprint: String = "",
    ) = V17MessageRecord(
        id = id,
        parentId = parentId,
        participant = participant,
        timestamp = timestamp,
        runId = runId,
        runSequence = sequence,
        inputFingerprint = fingerprint,
    )
}
